package de.hendrikfrahmann.giteaplugin.pullrequest.data

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaBranchDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCombinedStatusDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaRepositoryDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.api.request.countCommitsBetween
import de.hendrikfrahmann.giteaplugin.api.request.getBranch
import de.hendrikfrahmann.giteaplugin.api.request.getPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.getRepository
import de.hendrikfrahmann.giteaplugin.api.request.loadCombinedStatus
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestReviews
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val LOG = logger<GiteaPullRequestDataProvider>()

/** What the details view shows; loaded together so the parts never disagree. */
data class GiteaPullRequestSnapshot(
  val pullRequest: GiteaPullRequestDTO,
  /** Oldest first, including `REQUEST_REVIEW` placeholders. */
  val reviews: List<GiteaReviewDTO>,
  /** `null` when the head commit has no statuses or the request failed. */
  val status: GiteaCombinedStatusDTO?,
  /** The target branch with its effective protection and `user_can_merge`; `null` if it couldn't be loaded. */
  val baseBranch: GiteaBranchDTO? = null,
  /** Commits on the base branch since the merge base (open PRs only, `0` when up to date, `null` if unknown). */
  val behindBy: Int? = null,
  /** The repository now (merge styles, delete-branch default, permissions) – they may change after connecting; `null` if unknown. */
  val repository: GiteaRepositoryDTO? = null,
)

/**
 * One PR of the connected repository: details (+ reviews, CI status) and, computed lazily per head/merge-base, the changes.
 * [refresh] reloads the details; the changes are recomputed only when the head or the merge base moved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GiteaPullRequestDataProvider(
  parentCs: CoroutineScope,
  private val project: Project,
  private val connection: GiteaProjectConnection,
  val number: Long,
) {
  private val cs = parentCs.childScope()
  private val repository = connection.repo.repository.path
  private val mutex = Mutex()

  private val _snapshot = MutableStateFlow<Result<GiteaPullRequestSnapshot>?>(null)
  val snapshot: StateFlow<Result<GiteaPullRequestSnapshot>?> = _snapshot.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  /** Changes for the current head and merge base; `null` while the details are not loaded. */
  val changes: StateFlow<Deferred<GiteaPullRequestChanges>?> = snapshot
    .map { it?.getOrNull()?.pullRequest }
    .filterNotNull()
    .map { ChangesKey(it.head?.sha, it.mergeBase, it.base?.sha) }
    .distinctUntilChanged()
    .mapLatest { key ->
      cs.async(start = CoroutineStart.LAZY) {
        val pr = snapshot.value?.getOrNull()?.pullRequest ?: error("Pull request not loaded")
        GiteaPullRequestChangesLoader(project, connection, pr).load().also { LOG.debug("Loaded changes for #$number at $key") }
      }
    }
    .stateIn(cs, SharingStarted.Eagerly, null)

  private val reviewDataLazy = lazy {
    GiteaPullRequestReviewData(cs, connection.api, repository, number, connection.currentUser?.login, connection.currentUser)
  }

  /** Reviews and inline threads, loaded on first use (diff, timeline). */
  val reviewData: GiteaPullRequestReviewData by reviewDataLazy

  private val timelineDataLazy = lazy { GiteaPullRequestTimelineData(cs, connection.api, repository, number) }

  /** Conversation (timeline events, comments), loaded on first use (timeline editor). */
  val timelineData: GiteaPullRequestTimelineData by timelineDataLazy

  init {
    refresh()
  }

  /** Reloads details, reviews and status (and review threads / timeline once used); keeps showing the old data while loading. */
  fun refresh() {
    cs.launch { load() }
    if (reviewDataLazy.isInitialized()) {
      reviewData.reload()
    }
    if (timelineDataLazy.isInitialized()) {
      timelineData.reload()
    }
  }

  private suspend fun load() = mutex.withLock {
    _isLoading.value = true
    try {
      _snapshot.value = Result.success(loadSnapshot())
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load pull request #$number", e)
      // keep the last good data if there is some
      if (_snapshot.value?.isSuccess != true) {
        _snapshot.value = Result.failure(e)
      }
    }
    finally {
      _isLoading.value = false
    }
  }

  private suspend fun loadSnapshot(): GiteaPullRequestSnapshot = coroutineScope {
    val api = connection.api
    val reviews = async { api.loadPullRequestReviews(repository, number) }
    val repositorySettings = async { optional("repository") { api.getRepository(repository) } }
    val pr = api.getPullRequest(repository, number)
    val isOpen = pr.state == "open" && !pr.merged
    val status = async { pr.head?.sha?.let { sha -> optional("status of $sha") { api.loadCombinedStatus(repository, sha) } } }
    val baseBranch = async { pr.base?.ref?.takeIf { isOpen }?.let { ref -> optional("branch $ref") { api.getBranch(repository, ref) } } }
    val behindBy = async { commitsBehindBase(pr).takeIf { isOpen } }
    GiteaPullRequestSnapshot(pr, reviews.await(), status.await()?.takeIf { it.statuses.isNotEmpty() }, baseBranch.await(), behindBy.await(),
                             repositorySettings.await())
  }

  /** Commits on the base branch since the merge base; `0` when up to date, `null` if unknown. */
  private suspend fun commitsBehindBase(pr: GiteaPullRequestDTO): Int? {
    val mergeBase = pr.mergeBase
    val baseSha = pr.base?.sha
    // verified on 28.1: `merge_base` stays at the fork point while `base.sha` is the branch tip
    if (mergeBase == null || baseSha == null || mergeBase == baseSha) {
      return 0
    }
    return optional("compare $mergeBase...$baseSha") { connection.api.countCommitsBetween(repository, mergeBase, baseSha) }
  }

  /** Extra information that the details can do without. */
  private suspend fun <T> optional(what: String, load: suspend () -> T): T? =
    try {
      load()
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load $what of #$number", e)
      null
    }

  private data class ChangesKey(val head: String?, val mergeBase: String?, val base: String?)
}
