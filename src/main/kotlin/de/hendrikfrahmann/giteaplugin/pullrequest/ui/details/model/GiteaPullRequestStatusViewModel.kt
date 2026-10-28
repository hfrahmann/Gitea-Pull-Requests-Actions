package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model

import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJob
import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJobState
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewStatusViewModel
import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCombinedStatusDTO
import de.hendrikfrahmann.giteaplugin.api.dto.hasConflicts
import de.hendrikfrahmann.giteaplugin.api.request.loadCombinedStatus
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestSnapshot
import de.hendrikfrahmann.giteaplugin.refresh.GiteaRefreshService
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI

/** Conflicts and the CI statuses of the head commit (Gitea Actions and external CI report as commit statuses). */
class GiteaPullRequestStatusViewModel(
  parentCs: CoroutineScope,
  private val connection: GiteaProjectConnection,
  snapshot: StateFlow<GiteaPullRequestSnapshot>,
) : CodeReviewStatusViewModel {
  private val cs = parentCs.childScope()
  private val server = connection.repo.repository.serverPath
  private val remoteRoot = connection.repository.htmlUrl?.let { GiteaServerPath.remoteRootOf(it, connection.repo.repository.path.toString()) }

  override val hasConflicts: SharedFlow<Boolean> = snapshot.map { it.pullRequest.hasConflicts }
    .stateIn(cs, SharingStarted.Eagerly, false)

  /**
   * The head's statuses: from the snapshot, then re-read at the fast refresh interval while a check is pending – otherwise "Checks in
   * progress" stays until the PR is reloaded, long after the runs finished.
   */
  private val liveStatus = MutableStateFlow(snapshot.value.status)

  /** The jobs with Gitea's status text, which the platform's [CodeReviewCIJob] has no field for. */
  private val ciChecks: StateFlow<List<GiteaCiCheck>> = liveStatus.map { ciChecks(it, server, remoteRoot) }
    .stateIn(cs, SharingStarted.Eagerly, emptyList())

  override val ciJobs: SharedFlow<List<CodeReviewCIJob>> = ciChecks.map { checks -> checks.map { it.job } }
    .stateIn(cs, SharingStarted.Eagerly, emptyList())

  init {
    cs.launch {
      snapshot.collectLatest { current ->
        liveStatus.value = current.status
        val sha = current.pullRequest.head?.sha ?: return@collectLatest
        while (liveStatus.value?.statuses.orEmpty().any { it.status == "pending" }) {
          GiteaRefreshService.scheduler.awaitNextPoll("Gitea status of $sha", active = true)
          try {
            liveStatus.value = connection.api.loadCombinedStatus(connection.repo.repository.path, sha).takeIf { it.statuses.isNotEmpty() }
          }
          catch (e: CancellationException) {
            throw e
          }
          catch (e: Exception) {
            // keep the last known statuses; the next PR refresh tries again
            LOG.info("Could not refresh the status of $sha", e)
            break
          }
        }
      }
    }
  }

  private val _showJobsDetailsRequests = MutableSharedFlow<List<CodeReviewCIJob>>()
  override val showJobsDetailsRequests: SharedFlow<List<CodeReviewCIJob>> = _showJobsDetailsRequests.asSharedFlow()

  override fun showJobsDetails() {
    cs.launch { _showJobsDetailsRequests.emit(ciJobs.replayCache.firstOrNull().orEmpty()) }
  }

  /** Gitea's status text of [job] ("Successful in 1m 2s", "Failing after 3s", or whatever an external CI wrote). */
  fun descriptionOf(job: CodeReviewCIJob): String? = ciChecks.value.firstOrNull { it.job == job }?.description

  companion object {
    private val LOG = logger<GiteaPullRequestStatusViewModel>()

    /**
     * One job per status context. Gitea Actions write relative links (`/owner/repo/actions/runs/…`, with Gitea's subpath),
     * so those and links to the server's `ROOT_URL` ([remoteRoot]) are rebased; links of external CI stay as they are.
     */
    fun ciJobs(status: GiteaCombinedStatusDTO?, server: GiteaServerPath, remoteRoot: URI? = null): List<CodeReviewCIJob> =
      ciChecks(status, server, remoteRoot).map { it.job }

    /** [ciJobs] with the status's `description`; none when it is blank or already the job's name (a status without context). */
    fun ciChecks(status: GiteaCombinedStatusDTO?, server: GiteaServerPath, remoteRoot: URI? = null): List<GiteaCiCheck> =
      status?.statuses.orEmpty().map { s ->
        val state = when (s.status) {
          "success" -> CodeReviewCIJobState.SUCCESS
          "pending" -> CodeReviewCIJobState.PENDING
          "skipped" -> CodeReviewCIJobState.SKIPPED
          else -> CodeReviewCIJobState.FAILED
        }
        val url = s.targetUrl?.takeIf { it.isNotBlank() }?.let { runCatching { detailsUrl(it, server, remoteRoot) }.getOrNull() }
        val name = s.context ?: s.description.orEmpty()
        GiteaCiCheck(CodeReviewCIJob(name, state, false, url), s.description?.trim()?.takeIf { it.isNotEmpty() && it != name })
      }

    private fun detailsUrl(targetUrl: String, server: GiteaServerPath, remoteRoot: URI?): String {
      val uri = URI.create(targetUrl.trim())
      return when {
        !uri.isAbsolute -> server.rebase(targetUrl, remoteRoot).toString()
        server.owns(uri) -> uri.toString()
        remoteRoot != null && uri.toString().startsWith(remoteRoot.toString()) -> server.rebase(uri.toString(), remoteRoot).toString()
        else -> uri.toString()
      }
    }
  }
}

/** A CI job of the PR head and Gitea's text for its status. */
data class GiteaCiCheck(val job: CodeReviewCIJob, val description: String?)
