package de.hendrikfrahmann.giteaplugin.actions.ui.tree

import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.render.RenderingHelper
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import de.hendrikfrahmann.giteaplugin.actions.ui.details.GiteaActionsDetailsPanel
import de.hendrikfrahmann.giteaplugin.actions.ui.toolwindow.GiteaActionsTab
import de.hendrikfrahmann.giteaplugin.actions.ui.toolwindow.GiteaActionsToolWindowViewModel
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeSelectionModel

object GiteaActionsDataKeys {
  @JvmStatic
  val CONTROLLER: DataKey<GiteaActionsTreeController> = DataKey.create("Gitea.Actions.Tree.Controller")

  /** The selected row. */
  @JvmStatic
  val SELECTED_NODE: DataKey<GiteaActionsNode> = DataKey.create("Gitea.Actions.Tree.Selected")
}

object GiteaActionsTreeComponentFactory {
  const val TOOLBAR_PLACE: String = "Gitea.Actions.Toolbar"
  const val POPUP_PLACE: String = "Gitea.Actions.Tree.Popup"

  /** Toolbar + runs tree of one tab; [cs] lives as long as the tab (EDT dispatcher). */
  fun create(cs: CoroutineScope, project: Project, connection: GiteaProjectConnection, tab: GiteaActionsTab,
             setTitle: (String) -> Unit = {}): JComponent = ActionsTabPanel(cs, project, connection, tab, setTitle).component

  /** Like the Services tool window: the tree on the left, a page for the selected run, job or artifact on the right. */
  private class ActionsTabPanel(
    cs: CoroutineScope,
    private val project: Project,
    connection: GiteaProjectConnection,
    private val tab: GiteaActionsTab,
    setTitle: (String) -> Unit,
  ) {
    private val model = GiteaActionsTreeModel()
    private val tree = createTree(model)
    private val controller = GiteaActionsTreeController(cs, project, connection, tab, tree, model, setTitle)
    private val detailsPanel = GiteaActionsDetailsPanel(project, connection, cs) { run, jobId -> controller.focusRun(run.id, jobId = jobId) }

    val component: JComponent = object : JPanel(BorderLayout()), UiDataProvider {
      override fun uiDataSnapshot(sink: DataSink) {
        sink[GiteaActionsDataKeys.CONTROLLER] = controller
        sink[GiteaActionsDataKeys.SELECTED_NODE] = selectedNode()?.data
        sink[CommonDataKeys.PROJECT] = project
        sink[PlatformDataKeys.TREE_EXPANDER] = DefaultTreeExpander(tree)
      }
    }

    init {
      tree.addTreeSelectionListener { showSelectionDetails() }
      installActivation()
      PopupHandler.installPopupMenu(tree, "Gitea.Actions.Tree.Popup", POPUP_PLACE)
      component.add(OnePixelSplitter(false, "Gitea.Actions.Log.Splitter", TREE_PROPORTION).apply {
        firstComponent = createTreeSide()
        secondComponent = detailsPanel.component
      }, BorderLayout.CENTER)
      controller.start()
      if (tab is GiteaActionsTab.Run) {
        cs.launch { selectRequestedJobs(tab.runId) }
      }
      TreeUtil.installActions(tree)
    }

    private fun createTree(model: GiteaActionsTreeModel): Tree = Tree(model).apply {
      isRootVisible = false
      showsRootHandles = true
      selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
      cellRenderer = GiteaActionsTreeRenderer()
      putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
      // long run titles are cut, the right-aligned details stay visible
      putClientProperty(RenderingHelper.SHRINK_LONG_RENDERER, true)
      TreeUIHelper.getInstance().installTreeSpeedSearch(this)
    }

    private fun selectedNode(): DefaultMutableTreeNode? = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode

    private fun showSelectionDetails() {
      when (val data = selectedNode()?.data) {
        is GiteaActionsNode.Job -> detailsPanel.showJob(data.run, controller.workflowName(data.run), data.job)
        is GiteaActionsNode.Step -> detailsPanel.showJob(data.run, controller.workflowName(data.run), data.job, data.step.number)
        is GiteaActionsNode.Run -> detailsPanel.showRun(data.run, data.workflowName)
        is GiteaActionsNode.Artifact -> detailsPanel.showArtifact(data.run, data.artifact)
        // keep the page while a group row (workflow, attempts, artifacts) is selected
        else -> Unit
      }
    }

    /** Double-click / Enter on "Load more" loads more runs; other rows expand (their page follows the selection). */
    private fun installActivation() {
      object : DoubleClickListener() {
        override fun onDoubleClick(event: MouseEvent): Boolean = activate()
      }.installOn(tree)
      tree.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
          if (e.keyCode == KeyEvent.VK_ENTER && activate()) {
            e.consume()
          }
        }
      })
    }

    private fun activate(): Boolean {
      val node = selectedNode() ?: return false
      if (node.data !is GiteaActionsNode.LoadMore) {
        return false
      }
      controller.loadMore(node)
      return true
    }

    /** Toolbar with a permanent bottom line, level with the log panel's header (SimpleToolWindowPanel draws it only when scrolled). */
    private fun createTreeSide(): JComponent {
      val actions = ActionManager.getInstance().getAction("Gitea.Actions.Toolbar") as ActionGroup
      val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true).apply { targetComponent = component }
      return JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply {
          border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.headerBorderBackground())
          add(toolbar.component, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
      }
    }

    /** A "Run #N" tab selects jobs that were asked for from outside (PR details, notifications). */
    private suspend fun selectRequestedJobs(runId: Long) {
      val toolWindowVm = GiteaActionsToolWindowViewModel.getInstance(project)
      toolWindowVm.jobRequests.collect { request ->
        if (request != null && request.runId == runId) {
          toolWindowVm.jobRequestHandled(request)
          controller.selectJob(request.jobId)
        }
      }
    }
  }

  private const val TREE_PROPORTION = 0.4f
}
