package de.hendrikfrahmann.giteaplugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import com.intellij.ui.treeStructure.Tree
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaRun
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.GiteaActionsNode
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.GiteaActionsTreeRenderer
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaRepositoryDTO
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.clone.GiteaCloneItem
import de.hendrikfrahmann.giteaplugin.clone.GiteaCloneItemRenderer
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import javax.swing.JComponent
import javax.swing.tree.DefaultMutableTreeNode

/** What screen readers announce where the screen shows state only as an icon. */
class GiteaAccessibilityTest : BasePlatformTestCase() {
  fun `test actions tree rows announce name and status, not the node's toString`(): Unit = forbidSlowOperations {
    val run = GiteaRun(7, 3, 1, "Fix build", "ci.yml", "refs/heads/main", "push", GiteaActionStatus.FAILURE, "main", null, null, null,
                       null, null, null, null)
    val job = GiteaJob(11, 7, 1, "build", GiteaActionStatus.FAILURE, null, emptyList(), null, null)
    val node = DefaultMutableTreeNode(GiteaActionsNode.Job(run, job, latestAttempt = true))
    val tree = Tree(DefaultMutableTreeNode())

    val component = GiteaActionsTreeRenderer().getTreeCellRendererComponent(tree, node, false, false, true, 0, false) as JComponent
    val name = component.accessibleContext.accessibleName

    assertTrue(name, name.startsWith("build"))
    assertTrue(name, GiteaBundle.message("actions.status.failure") in name)
    assertFalse(name, "GiteaRun(" in name)
  }

  fun `test private repositories are announced as private`(): Unit = forbidSlowOperations {
    val account = GiteaAccount("intellij-dev", GiteaServerPath("https://git.example.com"))
    val renderer = GiteaCloneItemRenderer { false }
    val list = JBList<GiteaCloneItem>()
    fun announced(private: Boolean): String {
      val item = GiteaCloneItem.Repository(account, GiteaRepositoryDTO(1, "repo", "intellij-dev/repo", isPrivate = private))
      return (renderer.getListCellRendererComponent(list, item, 0, false, false) as JComponent).accessibleContext.accessibleName
    }

    assertEquals("intellij-dev/repo, ${GiteaBundle.message("repositories.private")}", announced(private = true))
    assertEquals("intellij-dev/repo", announced(private = false))
  }
}
