package de.hendrikfrahmann.giteaplugin.pullrequest.action

import com.intellij.collaboration.ui.codereview.changes.CodeReviewChangeListComponentFactory
import com.intellij.collaboration.ui.codereview.details.data.ReviewRequestState
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModel
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginMethod
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaAnonymousLoginLink
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestState
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaPullRequestDetailsComponentFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestDetailsViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaRefreshAction
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import java.awt.datatransfer.StringSelection
import java.net.URI

/** `Gitea.PullRequest.Show.List` – Git | Gitea | View Pull Requests. */
class GiteaShowPullRequestsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val available = e.project?.let { GiteaToolWindowProjectViewModel.getInstance(it).isAvailable.value } == true
    e.presentation.isEnabledAndVisible = available
  }

  override fun actionPerformed(e: AnActionEvent) {
    GiteaToolWindowProjectViewModel.getInstance(e.project ?: return).activateAndAwaitProject { viewList() }
  }
}

/** `Gitea.Repositories.Show` – Git | Gitea | Show Repositories and the tool window's gear menu: the Repositories tab. */
class GiteaShowRepositoriesAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val available = e.project?.let { GiteaToolWindowProjectViewModel.getInstance(it).isAvailable.value } == true
    e.presentation.isEnabledAndVisible = available
  }

  override fun actionPerformed(e: AnActionEvent) {
    GiteaToolWindowProjectViewModel.getInstance(e.project ?: return).activateAndAwaitProject { openRepositories() }
  }
}

/**
 * `Gitea.PullRequest.Checkout` – fetch and check out the head of the selected PR (list) or the open one (details).
 * Loads the PR first when only the list item is known.
 */
class GiteaCheckoutPullRequestAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val details = e.getData(GiteaPullRequestsActionKeys.DETAILS_VM)
    val selected = e.getData(GiteaPullRequestsActionKeys.SELECTED)
    e.presentation.isEnabledAndVisible = details != null ||
                                         (selected != null && selected.state == GiteaPullRequestState.OPEN &&
                                          e.getData(GiteaPullRequestsActionKeys.CONNECTED_PROJECT_VM) != null)
  }

  override fun actionPerformed(e: AnActionEvent) {
    e.getData(GiteaPullRequestsActionKeys.DETAILS_VM)?.let { it.branchesVm.fetchAndCheckoutRemoteBranch(); return }
    val projectVm = e.getData(GiteaPullRequestsActionKeys.CONNECTED_PROJECT_VM) ?: return
    val selected = e.getData(GiteaPullRequestsActionKeys.SELECTED) ?: return
    projectVm.checkoutPullRequest(selected.number)
  }
}

/** `Gitea.PullRequest.Show` – opens the selected PR in a tab (Enter / double-click in the list), and its timeline if the setting says so. */
class GiteaShowPullRequestAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible =
      e.getData(GiteaPullRequestsActionKeys.CONNECTED_PROJECT_VM) != null && e.getData(GiteaPullRequestsActionKeys.SELECTED) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val projectVm = e.getData(GiteaPullRequestsActionKeys.CONNECTED_PROJECT_VM) ?: return
    val selected = e.getData(GiteaPullRequestsActionKeys.SELECTED) ?: return
    projectVm.openPullRequest(selected.number, focus = false)
    if (GiteaSettings.getInstance().openTimelineFromList) {
      projectVm.openTimeline(selected.number, focus = false)
    }
  }
}

/** Shared by Open in Browser and Copy Link on the selected PR. */
abstract class GiteaPullRequestLinkAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = pullRequestUri(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    pullRequestUri(e)?.let(::handle)
  }

  protected abstract fun handle(uri: URI)

  /** The PR of the details tab, else the one selected in the list. */
  private fun pullRequestUri(e: AnActionEvent): URI? {
    e.getData(GiteaPullRequestsActionKeys.DETAILS_VM)?.let { return URI.create(it.url) }
    val projectVm = e.getData(GiteaPullRequestsActionKeys.CONNECTED_PROJECT_VM) ?: return null
    val selected = e.getData(GiteaPullRequestsActionKeys.SELECTED) ?: return null
    return GiteaWebUrls.pullRequest(projectVm.connection.repo.repository.getWebURI(), selected.number)
  }
}

/** `Gitea.PullRequest.Open.In.Browser` */
class GiteaOpenPullRequestInBrowserAction : GiteaPullRequestLinkAction() {
  override fun handle(uri: URI) = BrowserUtil.browse(uri)
}

/** `Gitea.PullRequest.Copy.Link` */
class GiteaCopyPullRequestLinkAction : GiteaPullRequestLinkAction() {
  override fun handle(uri: URI) = CopyPasteManager.getInstance().setContents(StringSelection(uri.toString()))
}

/** `Gitea.PullRequest.List.Refresh` */
class GiteaRefreshPullRequestsAction : GiteaRefreshAction() {
  override fun refresher(context: DataContext): (() -> Unit)? = context.getData(GiteaPullRequestsActionKeys.LIST_VM)?.let { vm -> vm::refresh }
}

/** Gear menu: back to the repository/account selector. */
class GiteaSwitchProjectAndAccountAction : DumbAwareAction(GiteaBundle.messagePointer("pull.request.toolwindow.switch.project")) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.getData(GiteaPullRequestsActionKeys.PROJECT_VM)?.canSwitchProject?.value == true
  }

  override fun actionPerformed(e: AnActionEvent) {
    e.getData(GiteaPullRequestsActionKeys.PROJECT_VM)?.switchProject()
  }
}

/** `Gitea.PullRequest.Refresh` – reloads the details of the open PR tab. */
class GiteaRefreshPullRequestAction : GiteaRefreshAction() {
  override fun refresher(context: DataContext): (() -> Unit)? = context.getData(GiteaPullRequestsActionKeys.DETAILS_VM)?.let { vm -> vm::refresh }
}

/** `Gitea.PullRequest.Changes.Show.Diff` */
class GiteaShowPullRequestDiffAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.getData(CodeReviewChangeListViewModel.DATA_KEY) != null &&
                                         !e.getData(CodeReviewChangeListComponentFactory.SELECTED_CHANGES).isNullOrEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    e.getData(CodeReviewChangeListViewModel.DATA_KEY)?.showDiffPreview()
  }
}

/** `Gitea.PullRequest.Changes.Mark.Viewed` / `.Not.Viewed` – only for the cumulative change list (whole PR). */
abstract class GiteaPullRequestViewedStateAction(private val viewed: Boolean) : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val vm = e.getData(CodeReviewChangeListViewModel.DATA_KEY) as? CodeReviewChangeListViewModel.WithViewedState
    val selected = e.getData(CodeReviewChangeListComponentFactory.SELECTED_CHANGES).orEmpty()
    e.presentation.isVisible = vm != null
    e.presentation.isEnabled = vm != null && selected.any { vm.detailsByChange.value[it]?.isRead != viewed }
  }

  override fun actionPerformed(e: AnActionEvent) {
    val vm = e.getData(CodeReviewChangeListViewModel.DATA_KEY) as? CodeReviewChangeListViewModel.WithViewedState ?: return
    vm.setViewedState(e.getData(CodeReviewChangeListComponentFactory.SELECTED_CHANGES).orEmpty(), viewed)
  }
}

class GiteaMarkViewedAction : GiteaPullRequestViewedStateAction(true)
class GiteaMarkNotViewedAction : GiteaPullRequestViewedStateAction(false)

/**
 * Edit reviewers / assignees / labels / milestone of the PR from the details or timeline context menu (`Gitea.PullRequest.Edit.*`);
 * only with write access. The chooser opens where the menu was.
 */
abstract class GiteaEditPullRequestMetadataAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val vm = e.getData(GiteaPullRequestsActionKeys.DETAILS_VM)
    e.presentation.isEnabledAndVisible = vm != null && vm.editVm.canEdit &&
                                         GiteaPullRequestDetailsViewModel.requestState(vm.snapshot.value.pullRequest) != ReviewRequestState.MERGED
  }

  override fun actionPerformed(e: AnActionEvent) {
    val vm = e.getData(GiteaPullRequestsActionKeys.DETAILS_VM) ?: return
    edit(vm, JBPopupFactory.getInstance().guessBestPopupLocation(e.dataContext))
  }

  protected abstract fun edit(vm: GiteaPullRequestDetailsViewModel, point: RelativePoint)
}

class GiteaEditReviewersAction : GiteaEditPullRequestMetadataAction() {
  override fun edit(vm: GiteaPullRequestDetailsViewModel, point: RelativePoint) =
    GiteaPullRequestDetailsComponentFactory.editReviewers(vm.uiScope, point, vm)
}

class GiteaEditAssigneesAction : GiteaEditPullRequestMetadataAction() {
  override fun edit(vm: GiteaPullRequestDetailsViewModel, point: RelativePoint) =
    GiteaPullRequestDetailsComponentFactory.editAssignees(vm.uiScope, point, vm)
}

class GiteaEditLabelsAction : GiteaEditPullRequestMetadataAction() {
  override fun edit(vm: GiteaPullRequestDetailsViewModel, point: RelativePoint) =
    GiteaPullRequestDetailsComponentFactory.editLabels(vm.uiScope, point, vm)
}

class GiteaEditMilestoneAction : GiteaEditPullRequestMetadataAction() {
  override fun edit(vm: GiteaPullRequestDetailsViewModel, point: RelativePoint) =
    GiteaPullRequestDetailsComponentFactory.editMilestone(vm.uiScope, point, vm)
}

/**
 * `Gitea.PullRequest.Create` – Git | Gitea | Create Pull Request and "+" in the tool window title: the "New Pull Request"
 * tab. Needs a connection with an account (anonymous ones are read-only).
 */
class GiteaCreatePullRequestAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val vm = e.project?.let { GiteaToolWindowProjectViewModel.getInstance(it) }
    if (e.place == ActionPlaces.TOOLWINDOW_TITLE) {
      val connected = vm?.connectedProjectVm?.value
      e.presentation.isEnabledAndVisible = connected != null && !connected.connection.isAnonymous
      e.presentation.icon = AllIcons.General.Add
    }
    else {
      e.presentation.isEnabledAndVisible = vm?.isAvailable?.value == true
    }
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    GiteaToolWindowProjectViewModel.getInstance(project).activateAndAwaitProject {
      if (connection.isAnonymous) {
        GiteaAnonymousLoginLink.logIn(project, null, connection.repo.repository.serverPath, GiteaLoginMethod.OAUTH)
      }
      else {
        openCreatePullRequest()
      }
    }
  }
}
