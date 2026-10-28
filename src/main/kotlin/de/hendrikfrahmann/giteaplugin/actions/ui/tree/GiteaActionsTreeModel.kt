package de.hendrikfrahmann.giteaplugin.actions.ui.tree

import com.intellij.util.concurrency.annotations.RequiresEdt
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Tree model whose children are replaced by key ([GiteaActionsNode.key]): rows that stay keep their tree node (and so
 * their expansion and selection), new rows are inserted, missing ones removed. Expandable rows without loaded
 * children get a [GiteaActionsNode.Loading] placeholder, which also shows the expand handle.
 */
class GiteaActionsTreeModel : DefaultTreeModel(DefaultMutableTreeNode(null)) {
  val rootNode: DefaultMutableTreeNode get() = root as DefaultMutableTreeNode

  @RequiresEdt
  fun setChildren(parent: DefaultMutableTreeNode, items: List<GiteaActionsNode>) {
    val existing = parent.childNodes().associateBy { it.data?.key }
    // add before removing: a parent that is empty for a moment gets collapsed by JTree (e.g. when the loading
    // placeholder is replaced by the loaded rows)
    addMissing(parent, items, existing)
    removeStale(parent, items)
    val nodes = parent.childNodes().associateBy { it.data?.key }
    for ((index, item) in items.withIndex()) {
      val node = nodes[item.key] ?: continue
      moveTo(parent, node, index)
      if (existing[item.key] != null) {
        update(node, item)
      }
    }
  }

  /** Drops loaded children so the next expand loads them again. */
  @RequiresEdt
  fun resetChildren(node: DefaultMutableTreeNode) {
    if (isLoadPending(node)) {
      return
    }
    node.childNodes().toList().forEach(::removeNodeFromParent)
    insertNodeInto(placeholder(), node, 0)
  }

  fun isLoadPending(node: DefaultMutableTreeNode): Boolean =
    node.childCount == 1 && (node.firstChild as DefaultMutableTreeNode).userObject == GiteaActionsNode.Loading

  private fun addMissing(parent: DefaultMutableTreeNode, items: List<GiteaActionsNode>, existing: Map<String?, DefaultMutableTreeNode>) {
    for (item in items) {
      if (existing[item.key] == null) {
        insertNodeInto(createNode(item), parent, parent.childCount)
      }
    }
  }

  private fun removeStale(parent: DefaultMutableTreeNode, items: List<GiteaActionsNode>) {
    val keys = items.mapTo(HashSet()) { it.key }
    for (child in parent.childNodes()) {
      if (child.data?.key !in keys) {
        removeNodeFromParent(child)
      }
    }
  }

  /** Moving forgets the node's expansion; the controller restores it. */
  private fun moveTo(parent: DefaultMutableTreeNode, node: DefaultMutableTreeNode, index: Int) {
    if (parent.getIndex(node) != index) {
      removeNodeFromParent(node)
      insertNodeInto(node, parent, index)
    }
  }

  /** New data for a kept row; an expandable row gets a loading placeholder, a no longer expandable one loses its children. */
  private fun update(node: DefaultMutableTreeNode, item: GiteaActionsNode) {
    val before = node.data
    node.userObject = item
    if (before != item) {
      nodeChanged(node)
    }
    if (item.isExpandable && node.childCount == 0) {
      insertNodeInto(placeholder(), node, 0)
    }
    if (!item.isExpandable && node.childCount > 0) {
      node.childNodes().toList().forEach(::removeNodeFromParent)
    }
  }

  private fun createNode(item: GiteaActionsNode) = DefaultMutableTreeNode(item).apply {
    if (item.isExpandable) {
      add(placeholder())
    }
  }

  private fun placeholder() = DefaultMutableTreeNode(GiteaActionsNode.Loading)

  companion object {
    /**
     * The hidden root starts without children, so `JTree` takes it for a leaf and never expands it – rows added later
     * stay invisible. Expand it once it has children.
     */
    @RequiresEdt
    fun ensureRootExpanded(tree: JTree, model: GiteaActionsTreeModel) {
      val path = TreePath(model.rootNode)
      if (model.rootNode.childCount > 0 && !tree.isExpanded(path)) {
        tree.expandPath(path)
      }
    }
  }
}

val DefaultMutableTreeNode.data: GiteaActionsNode? get() = userObject as? GiteaActionsNode

fun DefaultMutableTreeNode.childNodes(): List<DefaultMutableTreeNode> = children().toList().map { it as DefaultMutableTreeNode }
