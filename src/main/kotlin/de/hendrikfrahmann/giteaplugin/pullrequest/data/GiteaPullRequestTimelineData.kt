package de.hendrikfrahmann.giteaplugin.pullrequest.data

import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaTimelineEventDTO
import de.hendrikfrahmann.giteaplugin.api.request.createIssueComment
import de.hendrikfrahmann.giteaplugin.api.request.deleteComment
import de.hendrikfrahmann.giteaplugin.api.request.editComment
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestCommits
import de.hendrikfrahmann.giteaplugin.api.request.loadTimeline
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val LOG = logger<GiteaPullRequestTimelineData>()

/** Raw conversation of a PR; reviews and their threads come from [GiteaPullRequestReviewData]. */
data class GiteaPullRequestTimeline(
  val events: List<GiteaTimelineEventDTO>,
  /** Commit sha → subject, for push events. */
  val commitTitles: Map<String, String>,
)

/** Loads the timeline of PR [number] and changes its conversation comments; every change reloads it. */
class GiteaPullRequestTimelineData(
  parentCs: CoroutineScope,
  private val api: GiteaApi,
  private val repository: GiteaRepositoryPath,
  private val number: Long,
) {
  private val cs = parentCs.childScope()
  private val mutex = Mutex()

  private val _state = MutableStateFlow<Result<GiteaPullRequestTimeline>?>(null)
  val state: StateFlow<Result<GiteaPullRequestTimeline>?> = _state.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  init {
    reload()
  }

  fun reload() {
    cs.launch { load() }
  }

  suspend fun addComment(body: String) = change { api.createIssueComment(repository, number, body) }

  suspend fun editComment(commentId: Long, body: String) = change { api.editComment(repository, commentId, body) }

  suspend fun deleteComment(commentId: Long) = change { api.deleteComment(repository, commentId) }

  private suspend fun load() = mutex.withLock {
    _isLoading.value = true
    try {
      _state.value = Result.success(loadTimeline())
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load the timeline of #$number", e)
      // keep the last good data if there is some
      if (_state.value?.isSuccess != true) {
        _state.value = Result.failure(e)
      }
    }
    finally {
      _isLoading.value = false
    }
  }

  private suspend fun loadTimeline(): GiteaPullRequestTimeline = coroutineScope {
    val commits = async {
      try {
        api.loadPullRequestCommits(repository, number).associate { it.sha to (it.commit?.message ?: "").substringBefore('\n') }
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        LOG.info("Could not load the commits of #$number", e)
        emptyMap()
      }
    }
    GiteaPullRequestTimeline(api.loadTimeline(repository, number), commits.await())
  }

  /**
   * Runs in this data's scope, not the caller's: a comment field whose inlay or editor is rebuilt while the request runs
   * (diff recreated, PR refreshed) would otherwise cancel the reload after the server already made the change – the
   * comment then never showed up and was submitted again.
   */
  private suspend fun change(block: suspend () -> Unit) {
    cs.async {
      try {
        block()
      }
      finally {
        withContext(NonCancellable) { load() }
      }
    }.await()
  }
}
