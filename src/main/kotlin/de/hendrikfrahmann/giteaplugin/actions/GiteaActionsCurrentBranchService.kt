package de.hendrikfrahmann.giteaplugin.actions

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaRun
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.nullOnHttpStatus
import de.hendrikfrahmann.giteaplugin.api.request.GiteaActionRunsFilter
import de.hendrikfrahmann.giteaplugin.api.request.getBranch
import de.hendrikfrahmann.giteaplugin.api.request.loadRuns
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.pullrequest.review.GiteaCurrentBranchPullRequestService
import de.hendrikfrahmann.giteaplugin.refresh.GiteaRefreshService
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnectionManager
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<GiteaActionsCurrentBranchService>()

/**
 * Gitea Actions runs of the checked-out branch in the connected repository (the "Current Branch" tab, the status bar
 * widget and finish notifications). Polls adaptively – often while a run is queued or running, rarely otherwise – and right away
 * when the branch, its head or its PR changes, so a push shows its run within seconds.
 *
 * Runs are matched by the branch (push runs) and by commits (PR runs have no branch, only the PR head sha): the checked-out
 * commit, the PR head and the branch's tip on Gitea – so a run of a commit that isn't pulled yet (a push from elsewhere,
 * "Update Branch" in the PR details) shows up too. See [GiteaCurrentBranchRuns].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Service(Service.Level.PROJECT)
class GiteaActionsCurrentBranchService(private val project: Project, parentCs: CoroutineScope) {
  private val cs = parentCs.childScope(Dispatchers.Default)
  private val refreshRequests = MutableStateFlow(0)

  /** Runs of the current branch; [runs] is `null` while the first load is running. [localHead] is the checked-out commit. */
  data class State(
    val connection: GiteaProjectConnection,
    val branch: String?,
    val pullRequest: GiteaPullRequestDTO?,
    val runs: Result<List<GiteaRun>>?,
    val localHead: String? = null,
  ) {
    val loadedRuns: List<GiteaRun> get() = runs?.getOrNull().orEmpty()

    /** The run ran on the checked-out commit (and not only on a newer commit of the branch on Gitea). */
    fun isCheckedOut(run: GiteaRun): Boolean = GiteaCurrentBranchRuns.isCheckedOut(run, localHead)
  }

  /** `null` without a connection or when Actions are disabled for the repository. */
  val state: StateFlow<State?> = GiteaProjectConnectionManager.getInstance(project).connectionState.flatMapLatest { connection ->
    if (connection == null || !connection.repository.hasActions) {
      flowOf(null)
    }
    else {
      pollRuns(connection)
    }
  }.stateIn(cs, SharingStarted.Eagerly, null)

  /** Running and failed runs for the status bar; `null` when there is nothing to show. */
  val summary: StateFlow<GiteaActionsSummary?> = state.map { state -> state?.runs?.getOrNull()?.let(GiteaActionsSummary::of) }
    .distinctUntilChanged().stateIn(cs, SharingStarted.Eagerly, null)

  fun refresh() {
    refreshRequests.update { it + 1 }
  }

  private fun pollRuns(connection: GiteaProjectConnection) = channelFlow {
    val repository = connection.repo.gitRepository
    val prService = GiteaCurrentBranchPullRequestService.getInstance(project)
    // branch switches, commits, pushes and fetches, and PR changes wake the loop up
    val changes = MutableStateFlow(0)
    launch { repositoryChanges(repository) { changes.update { it + 1 } } }
    launch { prService.current.map { it?.pullRequest?.head?.sha }.distinctUntilChanged().collect { changes.update { it + 1 } } }

    val wakeUps = combine(changes, refreshRequests, ::Pair)

    var previous: List<GiteaRun>? = null
    var lastBranch: String? = null
    while (true) {
      val seen = wakeUps.first()
      val branch = branchName(connection, repository)
      val head = repository.currentRevision
      val pullRequest = currentPullRequest(prService, connection)
      if (branch != lastBranch) {
        previous = null
        send(State(connection, branch, pullRequest, null, head))
      }
      lastBranch = branch
      val result = tryLoadRuns(connection, branch, remoteBranchName(connection, repository), setOfNotNull(head, pullRequest?.head?.sha))
      send(State(connection, branch, pullRequest, result, head))
      result.getOrNull()?.let { runs ->
        previous?.let { notifyFinished(connection, it, runs) }
        previous = runs
      }
      val active = result.getOrNull().orEmpty().any { it.status.isActive }
      GiteaRefreshService.scheduler.awaitNextPoll("Gitea Actions runs of $branch", active) { wakeUps.first { it != seen } }
    }
  }

  /** The current branch's PR, if it belongs to this connection. */
  private fun currentPullRequest(prService: GiteaCurrentBranchPullRequestService, connection: GiteaProjectConnection) =
    prService.current.value?.takeIf { it.projectVm.connection.id == connection.id }?.pullRequest

  /** A failed load is shown in the widget, not thrown: the loop keeps polling. */
  private suspend fun tryLoadRuns(connection: GiteaProjectConnection, branch: String?, remoteBranch: String?,
                                  heads: Set<String>): Result<List<GiteaRun>> =
    try {
      Result.success(loadRuns(connection, branch, remoteBranch, heads))
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load the Gitea Actions runs of $branch", e)
      Result.failure(e)
    }

  /** [remoteBranch] is looked up on Gitea for its tip, which may be newer than anything fetched; `null` skips that. */
  private suspend fun loadRuns(connection: GiteaProjectConnection, branch: String?, remoteBranch: String?,
                               heads: Set<String>): List<GiteaRun> = coroutineScope {
    val repository = connection.repo.repository.path
    val pageSize = GiteaSettings.getInstance().runsPageSize
    val byBranch = branch?.let { async { connection.api.loadRuns(repository, GiteaActionRunsFilter(branch = it), 1, pageSize).workflowRuns } }
    val remoteHead = remoteBranch?.let { branchTip(connection, it) }
    val byHead = GiteaCurrentBranchRuns.heads(heads, remoteHead).map { sha ->
      async { connection.api.loadRuns(repository, GiteaActionRunsFilter(headSha = sha), 1, pageSize).workflowRuns }
    }
    GiteaCurrentBranchRuns.merge(byHead.map { it.await() } + listOfNotNull(byBranch?.await()), pageSize).map(GiteaRun::from)
  }

  /** The branch's newest commit on Gitea; `null` if it isn't pushed or can't be read – the other queries still count. */
  private suspend fun branchTip(connection: GiteaProjectConnection, branch: String): String? =
    try {
      nullOnHttpStatus { connection.api.getBranch(connection.repo.repository.path, branch) }?.commit?.id
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not read the tip of branch $branch", e)
      null
    }

  /** A run that was queued or running at the last poll has finished. */
  private suspend fun notifyFinished(connection: GiteaProjectConnection, before: List<GiteaRun>, after: List<GiteaRun>) {
    if (!GiteaSettings.getInstance().notifyRunFinished) {
      return
    }
    val wasActive = before.filter { it.status.isActive }.associateBy { it.id }
    val finished = after.filter { it.id in wasActive && it.status.isFinished && it.status != GiteaActionStatus.SKIPPED }
    if (finished.isEmpty()) {
      return
    }
    withContext(Dispatchers.EDT) {
      for (run in finished) {
        val (key, type) = when (run.status) {
          GiteaActionStatus.SUCCESS -> "actions.notification.run.succeeded" to NotificationType.INFORMATION
          GiteaActionStatus.CANCELLED -> "actions.notification.run.cancelled" to NotificationType.INFORMATION
          else -> "actions.notification.run.failed" to NotificationType.WARNING
        }
        NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.ACTIONS_GROUP)
          .createNotification(GiteaBundle.message(key, run.workflowFile ?: "", run.number, run.title), type)
          .setDisplayId(GiteaNotificationIds.ACTIONS_RUN_FINISHED)
          .addAction(NotificationAction.createSimpleExpiring(GiteaBundle.message("actions.notification.show")) {
            GiteaActionsNavigator.getInstance(project).showRun(run.id)
          })
          .addAction(NotificationAction.createSimple(GiteaBundle.message("actions.open.in.browser")) {
            BrowserUtil.browse(GiteaWebUrls.actionsRun(connection.repo.repository.getWebURI(), run.id))
          })
          .notify(project)
      }
    }
  }

  /** The branch runs are reported for: the tracked branch on the connected remote, else the local branch name. */
  private fun branchName(connection: GiteaProjectConnection, repository: GitRepository): String? {
    val branch = repository.currentBranch ?: return null
    val tracked = repository.getBranchTrackInfo(branch.name)?.remoteBranch
    return tracked?.takeIf { it.remote == connection.repo.gitRemote }?.nameForRemoteOperations ?: branch.name
  }

  /** The branch to look up on Gitea: the tracked branch on the connected remote, the local name if untracked, else `null`. */
  private fun remoteBranchName(connection: GiteaProjectConnection, repository: GitRepository): String? {
    val branch = repository.currentBranch ?: return null
    val tracked = repository.getBranchTrackInfo(branch.name)?.remoteBranch
    return GiteaCurrentBranchRuns.remoteBranch(branch.name, tracked?.remote, tracked?.nameForRemoteOperations, connection.repo.gitRemote)
  }

  private suspend fun repositoryChanges(repository: GitRepository, onChange: () -> Unit) {
    val connection = repository.project.messageBus.connect()
    try {
      connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
        if (it == repository) {
          onChange()
        }
      })
      awaitCancellation()
    }
    finally {
      connection.disconnect()
    }
  }

  companion object {
    fun getInstance(project: Project): GiteaActionsCurrentBranchService = project.service()
  }
}

/** Which runs make up "Current Branch" – kept apart from the service so it can be tested without a project. */
internal object GiteaCurrentBranchRuns {
  /** Commits whose runs are loaded: the given ones (checked-out commit, PR head) and the branch's tip on Gitea. */
  fun heads(local: Set<String>, remoteHead: String?): Set<String> = local + setOfNotNull(remoteHead)

  /**
   * The branch on the connected repository whose tip is looked up: the tracked branch if it is on [connectedRemote],
   * the local name for an untracked branch (pushed without upstream, or not at all: then Gitea answers `404`), `null`
   * when the branch tracks another remote (a fork: its PR head covers it).
   */
  fun <R> remoteBranch(localBranch: String, trackedRemote: R?, trackedName: String?, connectedRemote: R): String? = when {
    trackedRemote == null || trackedName == null -> localBranch
    trackedRemote == connectedRemote -> trackedName
    else -> null
  }

  /** One list from several queries: each run once, newest first, at most [limit]. */
  fun merge(results: List<List<GiteaActionRunDTO>>, limit: Int): List<GiteaActionRunDTO> =
    results.flatten().distinctBy { it.id }.sortedByDescending { it.id }.take(limit)

  /** Runs of the checked-out commit are marked in the tree; runs of newer, not yet pulled commits are not. */
  fun isCheckedOut(run: GiteaRun, localHead: String?): Boolean = localHead != null && run.headSha == localHead
}

/** Status bar numbers: runs in progress, and workflows whose latest run failed. */
data class GiteaActionsSummary(val running: Int, val failed: Int, val total: Int) {
  companion object {
    fun of(runs: List<GiteaRun>): GiteaActionsSummary {
      val latestPerWorkflow = runs.groupBy { it.workflowFile }.values.mapNotNull { list -> list.filter { it.status.isFinished }.maxByOrNull { it.id } }
      return GiteaActionsSummary(runs.count { it.status.isActive }, latestPerWorkflow.count { it.status == GiteaActionStatus.FAILURE }, runs.size)
    }
  }
}
