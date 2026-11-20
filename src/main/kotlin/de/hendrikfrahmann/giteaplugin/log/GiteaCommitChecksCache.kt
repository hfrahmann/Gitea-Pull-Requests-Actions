package de.hendrikfrahmann.giteaplugin.log

import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJobState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.nullOnHttpStatus
import de.hendrikfrahmann.giteaplugin.api.request.loadCombinedStatus
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaCiCheck
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestStatusViewModel
import de.hendrikfrahmann.giteaplugin.refresh.GiteaRefreshService
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/** What Gitea reports for a commit: its checks (empty when no CI reported anything), or that it doesn't know the commit. */
sealed interface GiteaCommitChecks {
  data class Reported(val checks: List<GiteaCiCheck>) : GiteaCommitChecks {
    val isPending: Boolean get() = checks.any { it.job.status == CodeReviewCIJobState.PENDING }
  }

  /** `404`: not pushed (yet), or not in this repository. */
  data object NotOnServer : GiteaCommitChecks
}

/**
 * The commit statuses shown in the Git log, per repository and commit, for the IDE session. Gitea has no request for the
 * statuses of several commits, so each commit costs one `GET /commits/{sha}/status`; this keeps that to once per commit:
 * finished checks don't change, so they are kept. Running checks are asked again at the fast refresh interval, commits
 * without checks (or not on the server) after [GiteaSettings.logChecksRecheckMinutes] – CI may only start after the push.
 */
@Service(Service.Level.PROJECT)
class GiteaCommitChecksCache(val cs: CoroutineScope) {
  data class Key(val server: GiteaServerPath, val repository: String, val sha: String)

  private class Entry(val checks: GiteaCommitChecks, val loadedAt: Long)

  private val entries = object : LinkedHashMap<Key, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>): Boolean = size > MAX_ENTRIES
  }
  private val inFlight = ConcurrentHashMap<Key, Deferred<GiteaCommitChecks?>>()
  private val requests = Semaphore(PARALLEL_REQUESTS)

  fun key(connection: GiteaProjectConnection, sha: String): Key =
    Key(connection.repo.repository.serverPath, connection.repo.repository.path.toString(), sha)

  /** The last known checks, also when they are due for a reload. */
  fun peek(key: Key): GiteaCommitChecks? = synchronized(entries) { entries[key]?.checks }

  /** No checks known yet, or they may have changed since. */
  fun isDue(key: Key, now: Long = System.currentTimeMillis()): Boolean {
    val entry = synchronized(entries) { entries[key] } ?: return true
    val age = now - entry.loadedAt
    return when (val checks = entry.checks) {
      is GiteaCommitChecks.Reported -> when {
        checks.isPending -> age >= GiteaRefreshService.scheduler.interval(true).inWholeMilliseconds
        checks.checks.isEmpty() -> recheckEmpty(age)
        else -> false
      }
      GiteaCommitChecks.NotOnServer -> recheckEmpty(age)
    }
  }

  /** [GiteaSettings.logChecksRecheckMinutes]; `0`: never again in this session. */
  private fun recheckEmpty(age: Long): Boolean {
    val minutes = GiteaSettings.getInstance().logChecksRecheckMinutes
    return minutes > 0 && age >= minutes.minutes.inWholeMilliseconds
  }

  /**
   * Asks Gitea for the checks of [sha]; requests for the same commit from several log tabs are shared. `null` if the
   * request failed – the error is logged, the log column just stays empty for the commit.
   */
  suspend fun load(connection: GiteaProjectConnection, sha: String): GiteaCommitChecks? {
    val key = key(connection, sha)
    val deferred = inFlight.computeIfAbsent(key) {
      cs.async(start = CoroutineStart.LAZY) {
        try {
          requests.withPermit { request(connection, sha) }?.also { checks ->
            synchronized(entries) { entries[key] = Entry(checks, System.currentTimeMillis()) }
          }
        }
        finally {
          inFlight.remove(key)
        }
      }
    }
    return deferred.await()
  }

  @Suppress("TooGenericExceptionCaught") // any failure only leaves the commit's cell empty
  private suspend fun request(connection: GiteaProjectConnection, sha: String): GiteaCommitChecks? =
    try {
      val status = nullOnHttpStatus { connection.api.loadCombinedStatus(connection.repo.repository.path, sha) }
      when (status) {
        null -> GiteaCommitChecks.NotOnServer
        else -> GiteaCommitChecks.Reported(GiteaPullRequestStatusViewModel.ciChecks(status, connection.repo.repository.serverPath, remoteRoot(connection)))
      }
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load the checks of $sha in ${connection.repo.repository}: ${e.localizedMessage}")
      null
    }

  /** Like the PR details: Gitea Actions write their links relative to the server's `ROOT_URL`. */
  private fun remoteRoot(connection: GiteaProjectConnection) =
    connection.repository.htmlUrl?.let { GiteaServerPath.remoteRootOf(it, connection.repo.repository.path.toString()) }

  companion object {
    private val LOG = logger<GiteaCommitChecksCache>()

    private const val PARALLEL_REQUESTS = 4
    private const val MAX_ENTRIES = 5000
    private const val INITIAL_CAPACITY = 256
    private const val LOAD_FACTOR = 0.75f

    fun getInstance(project: Project): GiteaCommitChecksCache = project.service()
  }
}
