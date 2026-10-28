package de.hendrikfrahmann.giteaplugin.actions.ui

import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ClickListener
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsCurrentBranchService
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsNavigator
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsSummary
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * "2 running · 1 failed" for the runs of the current branch; click opens the Actions tool window. Empty (and so
 * invisible) without a Gitea repository, with Actions disabled or without runs.
 */
class GiteaActionsStatusBarWidgetFactory : StatusBarWidgetFactory {
  override fun getId(): String = ID

  override fun getDisplayName(): @NlsContexts.ConfigurableName String = GiteaBundle.message("actions.status.bar.name")

  override fun isAvailable(project: Project): Boolean = true

  override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = GiteaActionsStatusBarWidget(project, scope)

  override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

  companion object {
    const val ID: String = "Gitea.Actions"
  }
}

private class GiteaActionsStatusBarWidget(private val project: Project, parentCs: CoroutineScope) : CustomStatusBarWidget {
  private val cs = parentCs.childScope(Dispatchers.EDT)

  private val label = JBLabel().apply {
    border = JBUI.Borders.empty(0, 4)
    isVisible = false
  }

  init {
    object : ClickListener() {
      override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
        GiteaActionsNavigator.getInstance(project).showCurrentBranch()
        return true
      }
    }.installOn(label, true)
    cs.launch {
      GiteaActionsCurrentBranchService.getInstance(project).summary.collect(::show)
    }
  }

  private fun show(summary: GiteaActionsSummary?) {
    if (summary == null || summary.total == 0) {
      label.isVisible = false
      return
    }
    val parts = listOfNotNull(GiteaBundle.message("actions.status.bar.running", summary.running).takeIf { summary.running > 0 },
                              GiteaBundle.message("actions.status.bar.failed", summary.failed).takeIf { summary.failed > 0 })
    label.icon = when {
      summary.running > 0 -> AnimatedIcon.Default.INSTANCE
      summary.failed > 0 -> CIBuildStatusIcons.failed
      else -> CIBuildStatusIcons.success
    }
    label.text = parts.joinToString(" · ")
    label.toolTipText = when {
      parts.isEmpty() -> GiteaBundle.message("actions.status.bar.passed.tooltip")
      else -> GiteaBundle.message("actions.status.bar.tooltip", parts.joinToString(", "))
    }
    label.isVisible = true
  }

  override fun ID(): String = GiteaActionsStatusBarWidgetFactory.ID

  override fun getComponent(): JComponent = label

  override fun dispose() {
    cs.cancel()
  }
}
