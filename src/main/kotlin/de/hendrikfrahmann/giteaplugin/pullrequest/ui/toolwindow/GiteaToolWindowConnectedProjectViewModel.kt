package de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow

import com.intellij.collaboration.ui.icon.AsyncImageIconsProvider
import com.intellij.collaboration.ui.icon.CachingIconsProvider
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.collaboration.ui.toolwindow.ReviewToolwindowProjectViewModel
import com.intellij.collaboration.ui.toolwindow.ReviewToolwindowTabs
import com.intellij.collaboration.ui.toolwindow.ReviewToolwindowTabsStateHolder
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ListSelection
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.IconUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.ImageUtil
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.labelsFlow
import de.hendrikfrahmann.giteaplugin.api.request.loadAssignees
import de.hendrikfrahmann.giteaplugin.api.request.milestonesFlow
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestCheckout
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestDataProvider
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestSeenState
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestShort
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestState
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestViewedState
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestsLoader
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaReviewThread
import de.hendrikfrahmann.giteaplugin.pullrequest.file.GiteaVirtualFileSystem
import de.hendrikfrahmann.giteaplugin.pullrequest.review.GiteaCurrentBranchPullRequestService
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaCommentContext
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model.GiteaPullRequestCreateViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff.GiteaPullRequestDiffViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersHistoryModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsPersistentFiltersHistory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.list.GiteaPullRequestsListViewModel
import de.hendrikfrahmann.giteaplugin.refresh.GiteaRefreshService
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import icons.CollaborationToolsIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Nls
import java.awt.Image
import java.util.Date
import javax.imageio.ImageIO
import javax.swing.Icon
import kotlin.time.Duration.Companion.seconds

/** Everything the PR tool window shows for one open [connection]: list, filters, PR tabs. */
class GiteaToolWindowConnectedProjectViewModel(
  parentCs: CoroutineScope,
  private val project: Project,
  val connection: GiteaProjectConnection,
  private val activate: () -> Unit,
) : ReviewToolwindowProjectViewModel<GiteaReviewTab, GiteaReviewTabViewModel> {
  private val cs = parentCs.childScope()
  private val repository = connection.repo.repository

  override val projectName: @Nls String = repository.path.toString()

  /** PRs seen in the list, by number – tabs and actions read from it. */
  private val pullRequests = MutableStateFlow<Map<Long, GiteaPullRequestShort>>(emptyMap())

  /** Cached: [AsyncImageIconsProvider] makes a new icon per call, which would start loading again on every repaint. */
  val avatarIconsProvider: IconsProvider<GiteaUserDTO> = CachingIconsProvider(AsyncImageIconsProvider(cs, AvatarLoader(connection)))

  private val filterVm = GiteaPullRequestsFiltersViewModel(
    cs,
    GiteaPullRequestsFiltersHistoryModel(GiteaPullRequestsPersistentFiltersHistory.getInstance(project)),
    connection.currentUser,
    avatarIconsProvider,
    loadUsers = ::loadFilterUsers,
    labelsBatches = { connection.api.labelsFlow(repository.path) },
    milestonesBatches = { connection.api.milestonesFlow(repository.path) },
  )

  private val loader = GiteaPullRequestsLoader(connection.api, repository.path, connection.currentUser?.login)

  private val seenState = GiteaPullRequestSeenState.getInstance(project)

  override val listVm: GiteaPullRequestsListViewModel = GiteaPullRequestsListViewModel(
    cs, filterVm, repository.path.toString(), avatarIconsProvider, loader::createPager,
    onLoaded = { page -> pullRequests.value += page.associateBy { it.number } },
    isSeen = ::isSeen,
    seenUpdates = seenState.updates,
  )

  private val tabsHelper = ReviewToolwindowTabsStateHolder<GiteaReviewTab, GiteaReviewTabViewModel>()
  override val tabs: StateFlow<ReviewToolwindowTabs<GiteaReviewTab, GiteaReviewTabViewModel>> = tabsHelper.tabs.asStateFlow()

  private val dataProviders = mutableMapOf<Long, GiteaPullRequestDataProvider>()
  private val diffViewModels = mutableMapOf<Long, GiteaPullRequestDiffViewModel>()

  init {
    cs.launch { refreshOpenPullRequestsOnRemoteChanges() }
    cs.launch { pollList() }
  }

  fun findPullRequest(number: Long): GiteaPullRequestShort? = pullRequests.value[number]

  /** Title of PR [number] from its loaded details or the list; `null` if neither is loaded. */
  fun pullRequestTitle(number: Long): String? =
    synchronized(dataProviders) { dataProviders[number] }?.snapshot?.value?.getOrNull()?.pullRequest?.title ?: findPullRequest(number)?.title

  override fun selectTab(tab: GiteaReviewTab?) = tabsHelper.select(tab)
  override fun closeTab(tab: GiteaReviewTab) = tabsHelper.close(tab)

  /** The tool window is shown again: the list and the open PRs may be stale. */
  fun refreshOnShow() {
    listVm.reloadInPlace()
    refreshOpenPullRequests()
  }

  /**
   * Like GitHub: open PRs of the user – authored, requested as reviewer or assigned – get a marker, `false` while they
   * changed since the user last opened them (or were never opened). `null` (no marker) for all others and anonymously.
   */
  private fun isSeen(pr: GiteaPullRequestShort): Boolean? {
    val login = connection.currentUser?.login ?: return null
    val involved = pr.author?.login == login || (pr.reviewers.orEmpty() + pr.assignees).any { it.login == login }
    if (pr.state != GiteaPullRequestState.OPEN || !involved) {
      return null
    }
    val updatedAt = pr.updatedAt ?: return null
    val lastSeen = seenState.lastSeen(seenKey(pr.number)) ?: return false
    return updatedAt.time <= lastSeen
  }

  /** PR [number] was opened (details or timeline); [updatedAt] as loaded from the server, if known. */
  fun markSeen(number: Long, updatedAt: Date? = findPullRequest(number)?.updatedAt) =
    seenState.markSeen(seenKey(number), updatedAt)

  private fun seenKey(number: Long) = GiteaPullRequestViewedState.key(repository.serverPath.toString(), repository.path.toString(), number)

  /** New PRs and changes show up in the list (and as the dot on the tool window icon) also while it is hidden. */
  private suspend fun pollList() {
    while (true) {
      GiteaRefreshService.scheduler.awaitNextPoll("Gitea pull request list of ${repository.path}", active = false)
      listVm.reloadInPlace()
    }
  }

  /** One per PR and connection, shared by its details and timeline tabs. */
  fun dataProvider(number: Long): GiteaPullRequestDataProvider = synchronized(dataProviders) {
    dataProviders.getOrPut(number) { GiteaPullRequestDataProvider(cs, project, connection, number) }
  }

  /** The review diff model of PR [number]; lives as long as the connection, like its data provider. */
  fun diffViewModel(number: Long): GiteaPullRequestDiffViewModel = synchronized(diffViewModels) {
    diffViewModels.getOrPut(number) { GiteaPullRequestDiffViewModel(cs, project, connection, dataProvider(number), avatarIconsProvider) }
  }

  /** Comment UI support of PR [number] (shared by its diff and timeline). */
  fun commentContext(number: Long): GiteaCommentContext = diffViewModel(number).commentContext

  /** Opens the review diff (`giteapr://` editor tab) of PR [number] showing [changes]. */
  @RequiresEdt
  fun openReviewDiff(number: Long, changes: ListSelection<RefComparisonChange>) {
    diffViewModel(number).showChanges(changes)
    openDiffFile(number)
  }

  /** Opens the review diff of PR [number] at [thread] (from the timeline). */
  fun openReviewDiff(number: Long, thread: GiteaReviewThread) {
    cs.launch(Dispatchers.EDT) {
      if (diffViewModel(number).showThread(thread)) {
        openDiffFile(number)
      }
    }
  }

  fun openPullRequest(number: Long, focus: Boolean) {
    markSeen(number)
    tabsHelper.showTab(GiteaReviewTab.PullRequest(number), { tab ->
      GiteaReviewTabViewModel.PullRequest(cs, tab.number, dataProvider(tab.number), findPullRequest(tab.number)?.title)
    })
    if (focus) {
      activate()
    }
  }

  /**
   * Opens the conversation of PR [number] in an editor tab (like GitLab's/GitHub's timeline); without [focus] the
   * list keeps the keyboard, so Enter on the next PR works right away.
   */
  fun openTimeline(number: Long, focus: Boolean = true) {
    markSeen(number)
    cs.launch(Dispatchers.EDT) {
      val file = findFile(number, timeline = true) ?: return@launch
      FileEditorManager.getInstance(project).openFile(file, focus)
    }
  }

  fun viewList() = selectTab(null)

  /** Fetches and checks out the head of PR [number], loading its details first if needed. */
  fun checkoutPullRequest(number: Long) {
    cs.launch {
      val result = dataProvider(number).snapshot.filterNotNull().first()
      result.getOrNull()?.let { GiteaPullRequestCheckout.checkout(connection, it.pullRequest) }
    }
  }

  /** Opens (or selects) the "New Pull Request" tab; not for anonymous connections. */
  fun openCreatePullRequest() {
    if (connection.isAnonymous) {
      return
    }
    tabsHelper.showTab(GiteaReviewTab.NewPullRequest, {
      GiteaReviewTabViewModel.NewPullRequest(cs) { tabCs ->
        GiteaPullRequestCreateViewModel(tabCs, project, connection, GiteaProjectsManager.getInstance(project), avatarIconsProvider,
                                        openPullRequest = { number -> openPullRequest(number, focus = true) },
                                        onCreated = ::pullRequestCreated)
      }
    })
    activate()
  }

  /** Opens (or selects) the tab with all repositories of the accounts. */
  fun openRepositories() {
    tabsHelper.showTab(GiteaReviewTab.Repositories, { GiteaReviewTabViewModel.Repositories(cs) })
    activate()
  }

  /** Replace the create tab by the new PR, refresh the list, notify with a link. */
  private fun pullRequestCreated(pullRequest: GiteaPullRequestDTO) {
    cs.launch(Dispatchers.EDT) {
      closeTab(GiteaReviewTab.NewPullRequest)
      openPullRequest(pullRequest.number, focus = true)
      listVm.refresh()
      GiteaCurrentBranchPullRequestService.getInstance(project).refresh()
      notifyCreated(pullRequest)
    }
  }

  private fun notifyCreated(pullRequest: GiteaPullRequestDTO) {
    val url = GiteaWebUrls.pullRequest(repository.getWebURI(), pullRequest.number)
    NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
      .createNotification(GiteaBundle.message("pull.request.create.notification", pullRequest.number, pullRequest.title), NotificationType.INFORMATION)
      .setDisplayId(GiteaNotificationIds.PULL_REQUEST_CREATED)
      .addAction(NotificationAction.createSimple(GiteaBundle.message("pull.request.create.notification.open")) { BrowserUtil.browse(url) })
      .notify(project)
  }

  /** `/assignees` needs a login: anonymously, the filter offers the people of the PRs loaded so far. */
  private suspend fun loadFilterUsers(): List<GiteaUserDTO> = when {
    connection.isAnonymous ->
      pullRequests.value.values.flatMap { listOfNotNull(it.author) + it.assignees + it.reviewers.orEmpty() }.distinctBy { it.login }
    else -> connection.api.loadAssignees(repository.path)
  }

  /**
   * A push or fetch (e.g. after resolving conflicts locally) changes conflicts, "behind" and CI of open PRs. Gitea
   * recalculates mergeability asynchronously, so they are reloaded once more a few seconds later.
   */
  private suspend fun refreshOpenPullRequestsOnRemoteChanges() {
    val repository = connection.repo.gitRepository
    val remoteBranches = MutableStateFlow(remoteHeads(repository))
    val busConnection = project.messageBus.connect()
    try {
      busConnection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
        if (it == repository) {
          remoteBranches.value = remoteHeads(repository)
        }
      })
      remoteBranches.drop(1).collectLatest {
        delay(1.seconds)
        refreshOpenPullRequests()
        delay(MERGEABILITY_SETTLE)
        refreshOpenPullRequests()
      }
    }
    finally {
      busConnection.disconnect()
    }
  }

  private fun remoteHeads(repository: GitRepository): Map<String, String?> =
    repository.branches.remoteBranches.associate { it.name to repository.branches.getHash(it)?.asString() }

  private fun refreshOpenPullRequests() {
    synchronized(dataProviders) { dataProviders.values.toList() }.forEach { it.refresh() }
  }

  private fun openDiffFile(number: Long) {
    val file = findFile(number, timeline = false) ?: return
    DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
  }

  private fun findFile(number: Long, timeline: Boolean): VirtualFile? {
    val fs = GiteaVirtualFileSystem.getInstance()
    return fs.refreshAndFindFileByPath(fs.getPath(connection.id, project, connection.repo.repository.path.toString(), number, timeline))
  }

  private companion object {
    val MERGEABILITY_SETTLE = 5.seconds
  }

  /** Avatars come from the configured server (`avatar_url` uses `ROOT_URL`); credentials are only sent to that server. */
  private class AvatarLoader(private val connection: GiteaProjectConnection) : AsyncImageIconsProvider.AsyncImageLoader<GiteaUserDTO> {
    override suspend fun load(key: GiteaUserDTO): Image? {
      val url = key.avatarUrl ?: return null
      return try {
        val api = connection.api
        val uri = api.server.rebase(url)
        api.sendAndRead(api.request(uri).setHeader("Accept", "image/*").GET().build()) { stream, _ -> ImageIO.read(stream) }.body
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (_: Exception) {
        null
      }
    }

    override fun createBaseIcon(key: GiteaUserDTO?, iconSize: Int): Icon =
      IconUtil.resizeSquared(CollaborationToolsIcons.Review.DefaultAvatar, iconSize)

    /** Round like the other review tools; Gitea's identicons are square and fill the whole image. */
    override suspend fun postProcess(image: Image): Image = ImageUtil.createCircleImage(ImageUtil.toBufferedImage(image))
  }
}
