package de.hendrikfrahmann.giteaplugin.actions.ui.toolwindow

import com.intellij.collaboration.ui.toolwindow.dontHideOnEmptyContent
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.cancelOnDispose
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.GiteaActionsTreeComponentFactory
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaAnonymousLoginLink
import de.hendrikfrahmann.giteaplugin.pullrequest.action.GiteaPullRequestsActionKeys
import de.hendrikfrahmann.giteaplugin.pullrequest.action.GiteaSwitchProjectAndAccountAction
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaSelectorComponentFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel
import de.hendrikfrahmann.giteaplugin.refresh.shownEvents
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import de.hendrikfrahmann.giteaplugin.util.mapScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * "Gitea Actions" tool window, separate from the PR tool window but on the same repository
 * connection. Tabs "Current Branch" and "Workflows", plus closable "PR #N" / "Run #N" tabs opened by navigation.
 */
class GiteaActionsToolWindowFactory : ToolWindowFactory, DumbAware {
  override suspend fun manage(toolWindow: ToolWindow, toolWindowManager: ToolWindowManager) {
    toolWindow.project.service<GiteaActionsToolWindowController>().manageToolWindow(toolWindow)
  }

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) =
    project.service<GiteaActionsToolWindowController>().manageContent(toolWindow)

  override fun shouldBeAvailable(project: Project): Boolean = false

  companion object {
    const val ID: String = "Gitea Actions"
  }
}

@Service(Service.Level.PROJECT)
internal class GiteaActionsToolWindowController(private val project: Project, parentCs: CoroutineScope) {
  private val cs = parentCs.childScope(Dispatchers.EDT)
  private val vm get() = GiteaActionsToolWindowViewModel.getInstance(project)

  suspend fun manageToolWindow(toolWindow: ToolWindow) {
    coroutineScope {
      launch {
        // same availability as the PR tool window: the project has a Gitea remote
        GiteaToolWindowProjectViewModel.getInstance(project).isAvailable.collect { withContext(Dispatchers.EDT) { toolWindow.isAvailable = it } }
      }
      launch {
        vm.activationRequests.collect { withContext(Dispatchers.EDT) { toolWindow.activate(null) } }
      }
      launch {
        toolWindow.shownEvents().collect { vm.refreshAll() }
      }
    }
  }

  fun manageContent(toolWindow: ToolWindow) {
    toolWindow.dontHideOnEmptyContent()
    toolWindow.setAdditionalGearActions(DefaultActionGroup(GiteaSwitchProjectAndAccountAction(),
                                                           ActionManager.getInstance().getAction("Gitea.Open.GeneralSettings")))
    // the switch action reads the PR tool window's project VM (shared connection)
    val projectVm = GiteaToolWindowProjectViewModel.getInstance(project)
    toolWindow.contentManager.addUiDataProvider { sink -> sink[GiteaPullRequestsActionKeys.PROJECT_VM] = projectVm }
    cs.launch {
      vm.connection.distinctUntilChangedBy { it?.id }.collectLatest { connection ->
        coroutineScope {
          when {
            connection == null -> showSelector(this, toolWindow)
            // Gitea answers 401 for runs and workflows without a token, even for public repositories
            connection.isAnonymous -> showLoginRequired(toolWindow, connection)
            !connection.repository.hasActions -> showDisabled(toolWindow, connection)
            else -> showTabs(this, toolWindow, connection)
          }
          awaitCancellation()
        }
      }
    }.cancelOnDispose(toolWindow.contentManager)
  }

  /** Not connected: the PR tool window's repository/account selector (they share the connection). */
  private fun showSelector(cs: CoroutineScope, toolWindow: ToolWindow) {
    val panel = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground() }
    replaceContents(toolWindow, listOf(ContentFactory.getInstance().createContent(panel, "", false).apply { isCloseable = false }))
    cs.launch {
      GiteaToolWindowProjectViewModel.getInstance(project).selectorVm
        .mapScoped { selectorVm -> selectorVm?.let { GiteaSelectorComponentFactory.create(this, project, it) } }
        .collect { selector ->
          panel.removeAll()
          if (selector != null) {
            panel.add(selector, BorderLayout.NORTH)
          }
          panel.revalidate()
          panel.repaint()
        }
    }
  }

  /** Actions are switched off for the repository. */
  private fun showDisabled(toolWindow: ToolWindow, connection: GiteaProjectConnection) {
    val webUri = connection.repo.repository.getWebURI()
    showMessage(toolWindow, GiteaBundle.message("actions.disabled", connection.repo.repository.path.toString()),
                ActionLink(GiteaBundle.message("actions.disabled.settings")) { BrowserUtil.browse("$webUri/settings") })
  }

  /** Read-only connection without an account: Actions need a login. */
  private fun showLoginRequired(toolWindow: ToolWindow, connection: GiteaProjectConnection) {
    val server = connection.repo.repository.serverPath
    showMessage(toolWindow, GiteaBundle.message("actions.login.required", server.host),
                GiteaAnonymousLoginLink.create(project, server, GiteaBundle.message("anonymous.banner.login")))
  }

  private fun showMessage(toolWindow: ToolWindow, text: String, link: ActionLink) {
    val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(MESSAGE_GAP), 0)).apply {
      border = JBUI.Borders.empty(MESSAGE_INSET)
      background = UIUtil.getListBackground()
      add(JBLabel(text, SwingConstants.LEFT).apply {
        foreground = UIUtil.getContextHelpForeground()
      })
      add(link)
    }
    val wrapper = JPanel(BorderLayout()).apply {
      background = UIUtil.getListBackground()
      add(panel, BorderLayout.NORTH)
    }
    replaceContents(toolWindow, listOf(ContentFactory.getInstance().createContent(wrapper, "", false).apply { isCloseable = false }))
  }

  /** "Current Branch" and "Workflows", plus the "PR #N" / "Run #N" tabs the view model asks for. */
  private fun showTabs(cs: CoroutineScope, toolWindow: ToolWindow, connection: GiteaProjectConnection) {
    val contentManager = toolWindow.contentManager
    val createContent = { tab: GiteaActionsTab -> createTabContent(cs, connection, tab) }
    replaceContents(toolWindow, listOf(createContent(GiteaActionsTab.CurrentBranch), createContent(GiteaActionsTab.Workflows)))
    val listener = tabsListener()
    contentManager.addContentManagerListener(listener)
    cs.launch {
      try {
        vm.extraTabs.collect { tabs ->
          syncExtraTabs(contentManager, tabs, createContent)
          selectRequested(toolWindow)
        }
      }
      finally {
        contentManager.removeContentManagerListener(listener)
      }
    }
    cs.launch {
      vm.selectedTab.collect { selectRequested(toolWindow) }
    }
  }

  /** A tab of its own scope; the tree sets the title of a "Run #N" tab once the run is known. */
  private fun createTabContent(cs: CoroutineScope, connection: GiteaProjectConnection, tab: GiteaActionsTab): Content {
    val tabCs = cs.childScope()
    var content: Content? = null
    val component = GiteaActionsTreeComponentFactory.create(tabCs, project, connection, tab) { title -> content?.displayName = title }
    content = ContentFactory.getInstance().createContent(component, tabTitle(tab), false)
    content.isCloseable = tab.isClosable
    content.putUserData(TAB_KEY, tab)
    content.setDisposer { tabCs.cancel() }
    return content
  }

  /** Tells the view model when the user closes or selects a tab. */
  private fun tabsListener() = object : ContentManagerListener {
    override fun contentRemoved(event: ContentManagerEvent) {
      event.content.getUserData(TAB_KEY)?.takeIf { it.isClosable }?.let(vm::closeTab)
    }

    override fun selectionChanged(event: ContentManagerEvent) {
      if (event.operation == ContentManagerEvent.ContentOperation.add) {
        event.content.getUserData(TAB_KEY)?.let(vm::tabSelected)
      }
    }
  }

  /** Removes closable tabs that are no longer wanted and adds the new ones. */
  private fun syncExtraTabs(contentManager: ContentManager, tabs: Collection<GiteaActionsTab>, createContent: (GiteaActionsTab) -> Content) {
    val shown = contentManager.contents.mapNotNull { content -> content.getUserData(TAB_KEY)?.let { it to content } }.toMap()
    for ((tab, content) in shown) {
      if (tab.isClosable && tab !in tabs) {
        contentManager.removeContent(content, true)
      }
    }
    for (tab in tabs.filter { it !in shown }) {
      contentManager.addContent(createContent(tab))
    }
  }

  private fun selectRequested(toolWindow: ToolWindow) {
    val requested = vm.selectedTab.value
    val content = toolWindow.contentManager.contents.find { it.getUserData(TAB_KEY) == requested } ?: return
    if (toolWindow.contentManager.selectedContent != content) {
      toolWindow.contentManager.setSelectedContent(content)
    }
  }

  private fun replaceContents(toolWindow: ToolWindow, contents: List<Content>) {
    val contentManager = toolWindow.contentManager
    contentManager.removeAllContents(true)
    contents.forEach(contentManager::addContent)
  }

  private fun tabTitle(tab: GiteaActionsTab): String = when (tab) {
    GiteaActionsTab.CurrentBranch -> GiteaBundle.message("actions.tab.current.branch")
    GiteaActionsTab.Workflows -> GiteaBundle.message("actions.tab.workflows")
    is GiteaActionsTab.PullRequest -> GiteaBundle.message("actions.tab.pull.request", tab.number)
    is GiteaActionsTab.Run -> GiteaBundle.message("actions.tab.run")
  }

  companion object {
    private val TAB_KEY = Key.create<GiteaActionsTab>("Gitea.Actions.Tab")
    private const val MESSAGE_GAP = 6
    private const val MESSAGE_INSET = 10
  }
}
