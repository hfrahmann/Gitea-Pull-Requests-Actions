package de.hendrikfrahmann.giteaplugin.pullrequest.review

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.request.findOpenPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.findOpenPullRequestByHead
import de.hendrikfrahmann.giteaplugin.api.request.getPullRequest
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowConnectedProjectViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import git4idea.GitRemoteBranch
import git4idea.branch.GitBranchSyncStatus
import git4idea.branch.GitBrancher
import git4idea.remote.hosting.GitCodeReviewUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.ui.branch.GitCurrentBranchPresenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<GiteaCurrentBranchPullRequestService>()

/**
 * The open PR of the checked-out branch in the connected repository: the PR whose head is the branch the current branch
 * tracks (also on a fork remote), a `pull/N` branch from a checkout, or – detached – the PR whose head is the current
 * revision. Drives the branch widget, the branch popup
 * actions and the review mode in editors; also notices pushes of the current branch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Service(Service.Level.PROJECT)
class GiteaCurrentBranchPullRequestService(private val project: Project, parentCs: CoroutineScope) {
  private val cs = parentCs.childScope(Dispatchers.Default)
  private val refreshCount = MutableStateFlow(0)

  data class Current(val projectVm: GiteaToolWindowConnectedProjectViewModel, val pullRequest: GiteaPullRequestDTO) {
    val number: Long get() = pullRequest.number
    val gitRepository: GitRepository get() = projectVm.connection.repo.gitRepository
  }

  /** What the current branch points at; changes trigger a new lookup. */
  internal data class BranchState(val branch: String?, val revision: String?, val headRepository: GiteaRepositoryPath, val headBranch: String?,
                                 val remoteRevision: String?)

  val current: StateFlow<Current?> = GiteaToolWindowProjectViewModel.getInstance(project).connectedProjectVm.flatMapLatest { vm ->
    if (vm == null) {
      return@flatMapLatest flowOf(null)
    }
    val repository = vm.connection.repo.gitRepository
    val states = repositoryChanges(repository).onStart { emit(Unit) }.map { branchState(vm, repository) }.distinctUntilChanged()
    combine(states, refreshCount) { state, _ -> state }
      .mapLatest { state -> findPullRequest(vm, state)?.let { Current(vm, it) } }
  }.stateIn(cs, SharingStarted.Eagerly, null)

  /**
   * Local branch against the PR head: incoming = the PR has commits the branch lacks ("Update Branch"). Recomputed on
   * every repository change too: a pull or fetch changes the answer while the PR (and so [current]) stays the same.
   */
  val syncStatus: StateFlow<GitBranchSyncStatus?> = current.flatMapLatest { current ->
    if (current == null) {
      flowOf(null)
    }
    else {
      repositoryChanges(current.gitRepository).onStart { emit(Unit) }.mapLatest { syncStatusOf(current) }
    }
  }.distinctUntilChanged().stateIn(cs, SharingStarted.Eagerly, null)

  init {
    cs.launch {
      combine(current, syncStatus, ::Pair).collect {
        project.messageBus.syncPublisher(GitCurrentBranchPresenter.PRESENTATION_UPDATED).presentationUpdated()
      }
    }
    cs.launch { watchPushes() }
  }

  /** Looks the PR of the current branch up again (after creating one, refreshing the list, …). */
  fun refresh() {
    refreshCount.update { it + 1 }
  }

  fun showPullRequest() {
    val current = current.value ?: return
    current.projectVm.openPullRequest(current.number, focus = true)
  }

  /** Fetches the PR head and merges it into the current branch ("Update Branch" in the branch popup). */
  fun updateBranch() {
    val current = current.value ?: return
    cs.launch {
      val remoteBranch = fetchPullRequestHead(current) ?: return@launch
      withContext(Dispatchers.EDT) {
        GitBrancher.getInstance(project).merge(remoteBranch, GitBrancher.DeleteOnMergeOption.NOTHING, listOf(current.gitRepository))
      }
      refresh()
    }
  }

  private suspend fun syncStatusOf(current: Current): GitBranchSyncStatus? {
    val repository = current.gitRepository
    return syncStatus(repository.currentRevision, current.pullRequest.head?.sha) { ancestor, descendant ->
      GitCodeReviewUtils.testIsAncestor(repository, ancestor, descendant)
    }
  }

  /** Fetches the PR head – the tracked branch, else `refs/pull/N/head` – and returns it as remote branch; `null` on failure. */
  private suspend fun fetchPullRequestHead(current: Current): GitRemoteBranch? {
    val connection = current.projectVm.connection
    val remote = connection.repo.gitRemote
    val repository = current.gitRepository
    val number = current.number
    val tracked = repository.currentBranch?.let { repository.getBranchTrackInfo(it.name) }?.remoteBranch
    val (refspec, mergeRef) = when {
      tracked != null -> tracked.nameForRemoteOperations to tracked.name
      else -> "refs/pull/$number/head:refs/remotes/${remote.name}/pull/$number" to "${remote.name}/pull/$number"
    }
    try {
      GitCodeReviewUtils.fetch(repository, tracked?.remote ?: remote, refspec)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not fetch the head of pull request #$number", e)
      return null
    }
    repository.update()
    return repository.branches.findRemoteBranch(mergeRef)
  }

  private fun branchState(vm: GiteaToolWindowConnectedProjectViewModel, repository: GitRepository): BranchState {
    val branch = repository.currentBranch
    val tracked = branch?.let { repository.getBranchTrackInfo(it.name) }?.remoteBranch
    val headMapping = tracked?.let { remoteBranch ->
      GiteaProjectsManager.getInstance(project).knownRepositoriesState.value
        .find { it.gitRepository == repository && it.gitRemote == remoteBranch.remote }
    }
    return BranchState(
      branch = branch?.name,
      revision = repository.currentRevision,
      headRepository = headMapping?.repository?.path ?: vm.connection.repo.repository.path,
      headBranch = tracked?.nameForRemoteOperations ?: branch?.name,
      remoteRevision = tracked?.let { repository.branches.getHash(it)?.asString() },
    )
  }

  private suspend fun findPullRequest(vm: GiteaToolWindowConnectedProjectViewModel, state: BranchState): GiteaPullRequestDTO? {
    if (state.branch == null && state.revision == null) {
      return null
    }
    return try {
      lookUpPullRequest(vm.connection, state)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not find the pull request of branch ${state.branch}", e)
      null
    }
  }

  /** Nothing on the default branch; else a `pull/N` checkout, a PR into the default branch, or any open PR with this head. */
  private suspend fun lookUpPullRequest(connection: GiteaProjectConnection, state: BranchState): GiteaPullRequestDTO? {
    val repository = connection.repo.repository.path
    if (state.headBranch == connection.repository.defaultBranch && state.headRepository == repository) {
      return null
    }
    return pullRequestOfPullBranch(connection, state)
           ?: pullRequestIntoDefaultBranch(connection, state)
           ?: connection.api.findOpenPullRequestByHead(repository, state.headRepository, state.headBranch, state.revision)
  }

  /** A `pull/N` branch from a PR checkout: that PR, if it's still open. */
  private suspend fun pullRequestOfPullBranch(connection: GiteaProjectConnection, state: BranchState): GiteaPullRequestDTO? {
    val repository = connection.repo.repository.path
    val number = PULL_BRANCH.matchEntire(state.headBranch.orEmpty())?.groupValues?.get(1)?.toLong() ?: return null
    if (state.headRepository != repository) {
      return null
    }
    return connection.api.getPullRequest(repository, number).takeIf { it.state == "open" }
  }

  private suspend fun pullRequestIntoDefaultBranch(connection: GiteaProjectConnection, state: BranchState): GiteaPullRequestDTO? {
    val headBranch = state.headBranch ?: return null
    val defaultBranch = connection.repository.defaultBranch ?: return null
    return connection.api.findOpenPullRequest(connection.repo.repository.path, defaultBranch, state.headRepository, headBranch)
  }

  /**
   * Push detection without the platform's push hooks (`GitPushNotificationCustomizer` and `GitPushRepoResult` are internal API): a
   * push shows as the tracked remote branch moving to the unchanged local head (or a new upstream at the local head).
   * Then a notification offers "View Pull Request" or "Create Pull Request…".
   */
  private suspend fun watchPushes() {
    GiteaToolWindowProjectViewModel.getInstance(project).connectedProjectVm.collectLatest { vm ->
      if (vm == null || vm.connection.isAnonymous) {
        return@collectLatest
      }
      val repository = vm.connection.repo.gitRepository
      var previous: BranchState? = branchState(vm, repository)
      repositoryChanges(repository).collect {
        val state = branchState(vm, repository)
        val before = previous
        previous = state
        if (isPush(before, state)) {
          notifyPushed(vm, state)
        }
      }
    }
  }

  private suspend fun notifyPushed(vm: GiteaToolWindowConnectedProjectViewModel, state: BranchState) {
    val headBranch = state.headBranch ?: return
    val connection = vm.connection
    if (headBranch == connection.repository.defaultBranch && state.headRepository == connection.repo.repository.path) {
      return
    }
    val known = GiteaProjectsManager.getInstance(project).knownRepositoriesState.value.any { it.repository.path == state.headRepository }
    if (!known) {
      return
    }
    val existing = findPullRequest(vm, state)
    refresh()
    withContext(Dispatchers.EDT) {
      pushNotification(vm, headBranch, existing).setDisplayId(GiteaNotificationIds.PUSH_PULL_REQUEST).notify(project)
    }
  }

  /** "View Pull Request" for the branch's PR, else "Create Pull Request…". */
  private fun pushNotification(vm: GiteaToolWindowConnectedProjectViewModel, headBranch: String, existing: GiteaPullRequestDTO?): Notification {
    val group = NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
    if (existing == null) {
      return group.createNotification(GiteaBundle.message("pull.request.push.none", headBranch), NotificationType.INFORMATION)
        .addAction(NotificationAction.createSimpleExpiring(GiteaBundle.message("pull.request.push.create")) { vm.openCreatePullRequest() })
    }
    val text = GiteaBundle.message("pull.request.push.existing", headBranch, existing.number, existing.title)
    return group.createNotification(text, NotificationType.INFORMATION)
      .addAction(NotificationAction.createSimpleExpiring(GiteaBundle.message("pull.request.push.view")) {
        vm.openPullRequest(existing.number, focus = true)
      })
      .addAction(NotificationAction.createSimple(GiteaBundle.message("pull.request.create.notification.open")) {
        BrowserUtil.browse(GiteaWebUrls.pullRequest(vm.connection.repo.repository.getWebURI(), existing.number))
      })
  }

  private fun repositoryChanges(repository: GitRepository): Flow<Unit> = callbackFlow {
    val connection = repository.project.messageBus.connect()
    connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
      if (it == repository) {
        trySend(Unit)
      }
    })
    awaitClose { connection.disconnect() }
  }

  companion object {
    private val PULL_BRANCH = Regex("pull/(\\d+)")

    /**
     * Same branch and local head, but the tracked remote branch moved to that head (or appeared there): a push. A fetch
     * moves the remote branch away from the local head, a pull moves the local head.
     */
    internal fun isPush(before: BranchState?, after: BranchState): Boolean =
      before != null && after.branch != null && after.branch == before.branch && after.revision == before.revision &&
      after.remoteRevision != null && after.remoteRevision == after.revision && before.remoteRevision != after.remoteRevision

    /**
     * Incoming = the PR [head] has commits the local [revision] lacks ("Update Branch"), outgoing = local commits not
     * yet pushed to the PR. A PR head that isn't fetched yet (git can't compare it) counts as incoming.
     */
    internal suspend fun syncStatus(revision: String?, head: String?,
                                    isAncestor: suspend (ancestor: String, descendant: String) -> Boolean): GitBranchSyncStatus? {
      if (revision == null || head == null) {
        return null
      }
      if (revision == head) {
        return GitBranchSyncStatus.SYNCED
      }
      return try {
        when {
          isAncestor(head, revision) -> GitBranchSyncStatus(false, true)
          else -> GitBranchSyncStatus(true, !isAncestor(revision, head))
        }
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (_: Exception) {
        GitBranchSyncStatus(true, false)
      }
    }

    fun getInstance(project: Project): GiteaCurrentBranchPullRequestService = project.service()
  }
}
