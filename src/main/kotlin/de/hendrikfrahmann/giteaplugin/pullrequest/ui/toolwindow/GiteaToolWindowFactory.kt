package de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow

import com.intellij.collaboration.ui.toolwindow.ReviewToolwindowViewModel
import com.intellij.collaboration.ui.toolwindow.dontHideOnEmptyContent
import com.intellij.collaboration.ui.toolwindow.manageReviewToolwindowTabs
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.IconManager
import com.intellij.ui.JBColor
import com.intellij.util.cancelOnDispose
import com.intellij.util.ui.JBUI
import de.hendrikfrahmann.giteaplugin.pullrequest.action.GiteaPullRequestsActionKeys
import de.hendrikfrahmann.giteaplugin.pullrequest.action.GiteaSwitchProjectAndAccountAction
import de.hendrikfrahmann.giteaplugin.refresh.shownEvents
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.UIManager

/**
 * "Gitea Pull Requests" tool window (not "Pull Requests" – that id belongs to the GitHub plugin). Available only while
 * the project has a Gitea remote; content lives in project services, so it survives hiding the tool window.
 */
class GiteaToolWindowFactory : ToolWindowFactory, DumbAware {
  // experimental platform API (isolated here, as GitLab does): keeps availability in sync with the remotes
  override suspend fun manage(toolWindow: ToolWindow, toolWindowManager: ToolWindowManager) {
    toolWindow.project.service<GiteaPullRequestsToolWindowController>().manageToolWindow(toolWindow)
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) =
    project.service<GiteaPullRequestsToolWindowController>().manageContent(toolWindow)

  override fun shouldBeAvailable(project: Project): Boolean = false

  companion object {
    const val ID: String = "Gitea Pull Requests"
  }
}

@Service(Service.Level.PROJECT)
internal class GiteaPullRequestsToolWindowController(private val project: Project, parentCs: CoroutineScope) {
  private val cs = parentCs.childScope(Dispatchers.EDT)

  suspend fun manageToolWindow(toolWindow: ToolWindow) {
    val vm = GiteaToolWindowProjectViewModel.getInstance(project)
    coroutineScope {
      launch {
        vm.isAvailable.collect { withContext(Dispatchers.EDT) { toolWindow.isAvailable = it } }
      }
      launch {
        vm.activationRequests.collect { withContext(Dispatchers.EDT) { toolWindow.activate(null) } }
      }
      launch {
        toolWindow.shownEvents().collect { vm.connectedProjectVm.value?.refreshOnShow() }
      }
      launch {
        manageIconBadge(toolWindow, vm)
      }
    }
  }

  /** A dot on the stripe icon while the list has unseen PRs, like GitHub's (in the selected button's color while active). */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Suppress("UsePropertyAccessSyntax") // `toolWindow.icon` is read-only in Kotlin, it needs the setter
  private suspend fun manageIconBadge(toolWindow: ToolWindow, vm: GiteaToolWindowProjectViewModel) {
    val badgeColor = JBColor.lazy {
      UIManager.getColor("ToolWindow.Button.selectedForeground")?.takeIf { toolWindow.isActive } ?: JBUI.CurrentTheme.IconBadge.INFORMATION
    }
    vm.connectedProjectVm.flatMapLatest { it?.listVm?.hasUpdates ?: flowOf(false) }.distinctUntilChanged().collect { hasUpdates ->
      val icon = when {
        hasUpdates -> IconManager.getInstance().withIconBadge(GiteaIcons.GiteaToolWindow, badgeColor)
        else -> GiteaIcons.GiteaToolWindow
      }
      withContext(Dispatchers.EDT) { toolWindow.setIcon(icon) }
    }
  }

  fun manageContent(toolWindow: ToolWindow) {
    toolWindow.component.putClientProperty(ToolWindowContentUi.HIDE_ID_LABEL, "true")
    toolWindow.dontHideOnEmptyContent()
    toolWindow.setTitleActions(listOf(ActionManager.getInstance().getAction("Gitea.PullRequest.Create")))
    toolWindow.setAdditionalGearActions(DefaultActionGroup(GiteaSwitchProjectAndAccountAction(),
                                                           ActionManager.getInstance().getAction("Gitea.Repositories.Show")))

    val vm = GiteaToolWindowProjectViewModel.getInstance(project)
    toolWindow.contentManager.addUiDataProvider { sink ->
      sink[GiteaPullRequestsActionKeys.PROJECT_VM] = vm
      sink[GiteaPullRequestsActionKeys.CONNECTED_PROJECT_VM] = vm.connectedProjectVm.value
    }

    cs.launch {
      val reviewToolWindowVm = object : ReviewToolwindowViewModel<GiteaToolWindowConnectedProjectViewModel> {
        override val projectVm: StateFlow<GiteaToolWindowConnectedProjectViewModel?> = vm.connectedProjectVm
      }
      manageReviewToolwindowTabs(this, toolWindow, reviewToolWindowVm, GiteaReviewTabComponentFactory(project, vm),
                                 GiteaBundle.message("pull.request.toolwindow.list.tab"))
      awaitCancellation()
    }.cancelOnDispose(toolWindow.contentManager)
  }
}
