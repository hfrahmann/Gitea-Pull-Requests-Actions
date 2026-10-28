package de.hendrikfrahmann.giteaplugin.actions.ui.tree

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaStep
import de.hendrikfrahmann.giteaplugin.actions.ui.GiteaActionsUiUtil
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import java.awt.Component
import java.awt.Dimension
import javax.accessibility.AccessibleContext
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * Status icon and name on the left, a few grey details right-aligned (the platform tree paints renderers across the
 * whole row; with `SHRINK_LONG_RENDERER` long names are cut, not the details). Runs show `#number • workflow • age`,
 * in the Workflows tab also the branch; everything else is in the log panel's header.
 */
class GiteaActionsTreeRenderer : TreeCellRenderer {
  private val nameRenderer = object : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
      customizeName(this, (value as? DefaultMutableTreeNode)?.data ?: return)
    }
  }
  /** What screen readers announce for the row being rendered: name and status (from the name renderer) and details. */
  private var announcedText: String = ""
  private val details = SimpleColoredComponent().apply {
    isOpaque = false
    ipad = JBUI.insets(0, 12, 0, 4)
  }
  /** Name left, details right; in a narrow row the name keeps at least half of it and the details are cut. */
  private val panel = object : JPanel(null) {
    override fun doLayout() {
      val namePreferred = nameRenderer.preferredSize.width
      val detailsWidth = minOf(details.preferredSize.width, maxOf(0, width - minOf(namePreferred, width / 2)))
      nameRenderer.setBounds(0, 0, width - detailsWidth, height)
      details.setBounds(width - detailsWidth, 0, detailsWidth, height)
    }

    override fun getPreferredSize(): Dimension {
      val name = nameRenderer.preferredSize
      val detail = details.preferredSize
      return Dimension(name.width + detail.width, maxOf(name.height, detail.height))
    }

    // JTree reads the renderer's accessible name; a plain panel has none (the node's toString() would be read)
    override fun getAccessibleContext(): AccessibleContext {
      if (accessibleContext == null) {
        accessibleContext = object : AccessibleJPanel() {
          override fun getAccessibleName(): String = announcedText
        }
      }
      return accessibleContext
    }
  }.apply {
    isOpaque = false
    add(nameRenderer)
    add(details)
  }

  override fun getTreeCellRendererComponent(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int,
                                            hasFocus: Boolean): Component {
    nameRenderer.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
    nameRenderer.isOpaque = false
    details.clear()
    val node = (value as? DefaultMutableTreeNode)?.data
    val text = node?.let(::detailsText)
    if (!text.isNullOrEmpty()) {
      val attributes = when {
        selected && tree.hasFocus() -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, UIUtil.getTreeSelectionForeground(true))
        else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
      }
      details.append(text, attributes)
    }
    announcedText = listOfNotNull(nameRenderer.getAccessibleContext().accessibleName, text).filter { it.isNotBlank() }.joinToString(", ")
    return panel
  }

  private fun customizeName(renderer: ColoredTreeCellRenderer, node: GiteaActionsNode) = with(renderer) {
    // the status is only an icon on screen: its tooltip is also announced by screen readers with the name
    node.status?.let { icon = GiteaIcons.withToolTip(GiteaActionsUiUtil.statusIcon(it, animated = true), GiteaActionsUiUtil.statusText(it)) }
    when (node) {
      is GiteaActionsNode.PullRequest -> {
        icon = AllIcons.Vcs.Merge
        append("#${node.number} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append(node.title)
      }
      is GiteaActionsNode.Workflow -> {
        icon = AllIcons.Nodes.Folder
        append(node.workflow.name)
      }
      is GiteaActionsNode.Run -> append(node.run.title)
      is GiteaActionsNode.Job -> append(node.job.name)
      is GiteaActionsNode.Step -> append(node.step.name)
      is GiteaActionsNode.Artifacts -> {
        icon = AllIcons.Nodes.Folder
        append(GiteaBundle.message("actions.artifacts"))
      }
      is GiteaActionsNode.Artifact -> {
        icon = AllIcons.FileTypes.Archive
        append(node.artifact.name, when {
          node.artifact.expired -> EXPIRED
          else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        })
      }
      is GiteaActionsNode.Attempts -> {
        icon = AllIcons.Vcs.History
        append(GiteaBundle.message("actions.attempts"))
      }
      is GiteaActionsNode.Attempt -> {
        icon = AllIcons.Vcs.History
        append(GiteaBundle.message("actions.attempt", node.attempt))
      }
      is GiteaActionsNode.LoadMore -> append(GiteaBundle.message("actions.load.more"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES)
      is GiteaActionsNode.Message -> {
        if (node.isError) {
          icon = AllIcons.General.Error
        }
        append(node.text, when {
          node.isError -> SimpleTextAttributes.ERROR_ATTRIBUTES
          else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        })
      }
      GiteaActionsNode.Loading -> {
        icon = AnimatedIcon.Default.INSTANCE
        append(GiteaBundle.message("actions.loading"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
      }
    }
  }

  /** The grey, right-aligned part of a row. */
  private fun detailsText(node: GiteaActionsNode): String? = when (node) {
    is GiteaActionsNode.Run -> runDetails(node)
    is GiteaActionsNode.Workflow -> join(node.workflow.id.takeIf { it != node.workflow.name },
                                         GiteaBundle.message("actions.workflow.disabled").takeIf { node.workflow.isDisabled })
    is GiteaActionsNode.Job -> node.job.durationMillis()?.let(GiteaActionsUiUtil::formatDuration)
    is GiteaActionsNode.Step -> stepDuration(node.step)
    is GiteaActionsNode.Artifacts -> node.artifacts.size.toString()
    is GiteaActionsNode.Artifact -> join(StringUtil.formatFileSize(node.artifact.sizeInBytes),
                                         GiteaBundle.message("actions.artifact.expired").takeIf { node.artifact.expired })
    else -> null
  }

  /**
   * `#12 • main • Build • yesterday` – the branch (or PR) only where the tab shows runs of several branches;
   * "checked out" for runs of the local commit in "Current Branch".
   */
  private fun runDetails(node: GiteaActionsNode.Run): String {
    val run = node.run
    val origin = (run.branch ?: run.pullRequestNumber?.let { GiteaBundle.message("actions.run.pull.request", it) }).takeIf { node.showBranch }
    val checkedOut = GiteaBundle.message("actions.run.checked.out").takeIf { node.isCheckedOut }
    return join("#${run.number}", origin, checkedOut, node.workflowName, run.createdAt?.let { DateFormatUtil.formatPrettyDateTime(it) })
  }

  private fun stepDuration(step: GiteaStep): String? {
    val start = step.startedAt ?: return null
    val end = step.completedAt ?: return null
    return GiteaActionsUiUtil.formatDuration(end.time - start.time)
  }

  private fun join(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" • ")

  private companion object {
    val EXPIRED = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getInactiveTextColor())
  }
}
