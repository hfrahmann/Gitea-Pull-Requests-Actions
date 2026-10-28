package de.hendrikfrahmann.giteaplugin

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vcs.annotate.AnnotationGutterActionProvider
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.actions.ui.GiteaActionsStatusBarWidgetFactory
import de.hendrikfrahmann.giteaplugin.actions.ui.toolwindow.GiteaActionsToolWindowFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowFactory
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaAnnotationGutterActionProvider
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaPullRequestAnnotationGutterActionProvider
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import java.awt.GraphicsEnvironment

class GiteaPluginSmokeTest : BasePlatformTestCase() {
  fun `test plugin and Git4Idea are present`() {
    // In tests the plugin is loaded from the classpath, so class loading is checked via runIde instead
    // isEnabled, not just present: a plugin whose dependency fails to load is present but disabled
    assertTrue("Gitea plugin not enabled", PluginManagerCore.isLoaded(PluginId.getId("de.hendrikfrahmann.giteaplugin")))
    assertTrue("Git4Idea not enabled", PluginManagerCore.isLoaded(PluginId.getId("Git4Idea")))
  }

  fun `test bundle and icons resolve`() {
    assertEquals("Gitea Pull Requests", GiteaBundle.message("toolwindow.stripe.Gitea_Pull_Requests"))
    // headless (Linux CI) the platform hands out a 1x1 placeholder until icons are activated
    val headless = GraphicsEnvironment.isHeadless()
    if (headless) {
      IconLoader.activate()
    }
    try {
      assertEquals(16, GiteaIcons.Gitea.iconWidth)
      assertEquals(16, GiteaIcons.GiteaToolWindow.iconWidth)
    }
    finally {
      if (headless) {
        IconLoader.deactivate()
      }
    }
  }

  fun `test hosting actions are registered`() {
    val actionManager = ActionManager.getInstance()
    for ((groupId, actionId) in listOf("Git.Hosting.Open.In.Browser.Group" to "Gitea.Open.In.Browser",
                                       "Git.Hosting.Copy.Link.Group" to "Gitea.Copy.Link")) {
      val group = actionManager.getAction(groupId) as DefaultActionGroup
      assertTrue("$actionId not in $groupId", group.childActionsOrStubs.any { actionManager.getId(it) == actionId })
    }
    val providers = AnnotationGutterActionProvider.EP_NAME.extensionList
    assertTrue(providers.any { it is GiteaAnnotationGutterActionProvider })
    assertTrue(providers.any { it is GiteaPullRequestAnnotationGutterActionProvider })
  }

  fun `test pull request tool window and actions are registered`() {
    val actionManager = ActionManager.getInstance()
    for (id in listOf("Gitea.PullRequest.Show.List", "Gitea.PullRequest.Show", "Gitea.PullRequest.Open.In.Browser",
                      "Gitea.PullRequest.Copy.Link", "Gitea.PullRequest.List.Refresh", "Gitea.PullRequest.List.Actions")) {
      assertNotNull(id, actionManager.getAction(id))
    }
    assertTrue(ToolWindowEP.EP_NAME.extensionList.any { it.id == GiteaToolWindowFactory.ID })
  }

  fun `test actions tool window, status bar widget and actions are registered`() {
    val actionManager = ActionManager.getInstance()
    val ids = listOf("Gitea.Actions.Show", "Gitea.Actions.Refresh", "Gitea.Actions.Open.Log", "Gitea.Actions.Open.PullRequest",
                     "Gitea.Actions.Run.Rerun", "Gitea.Actions.Run.Rerun.Failed", "Gitea.Actions.Job.Rerun", "Gitea.Actions.Run.Cancel",
                     "Gitea.Actions.Run.ForceCancel", "Gitea.Actions.Run.Approve", "Gitea.Actions.Run.Delete", "Gitea.Actions.Workflow.Dispatch",
                     "Gitea.Actions.Artifact.Download", "Gitea.Actions.Run.Download.Logs", "Gitea.Actions.Open.In.Browser",
                     "Gitea.Actions.Copy.Link", "Gitea.Actions.Secrets", "Gitea.Actions.Toolbar", "Gitea.Actions.Tree.Popup", "Gitea.PullRequest.ShowActions")
    for (id in ids) {
      val action = actionManager.getAction(id)
      assertNotNull(id, action)
      if (action !is ActionGroup) {
        assertFalse("$id has no text", action!!.templatePresentation.text.isNullOrBlank())
      }
    }
    assertTrue(ToolWindowEP.EP_NAME.extensionList.any { it.id == GiteaActionsToolWindowFactory.ID })
    assertTrue(StatusBarWidgetFactory.EP_NAME.extensionList.any { it.id == GiteaActionsStatusBarWidgetFactory.ID })
  }
}
