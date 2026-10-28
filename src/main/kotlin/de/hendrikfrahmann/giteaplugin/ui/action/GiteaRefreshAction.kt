package de.hendrikfrahmann.giteaplugin.ui.action

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbAwareAction

/**
 * Reload of a Gitea view on the IDE's Refresh shortcut (`use-shortcut-of="Refresh"`); enabled where [refresher] finds
 * the view in the data context.
 */
abstract class GiteaRefreshAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = refresher(e.dataContext) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    refresher(e.dataContext)?.invoke()
  }

  /** The reload of the view in [context]; `null` outside of it. */
  abstract fun refresher(context: DataContext): (() -> Unit)?
}

/**
 * The Refresh shortcut is shared with other actions (on macOS Cmd+R is also Replace and Rerun), which then ran as well
 * or instead. Inside a Gitea view only its reload stays.
 */
internal class GiteaRefreshActionPromoter : ActionPromoter {
  override fun suppress(actions: List<AnAction>, context: DataContext): List<AnAction>? {
    val ownRefreshApplies = actions.any { it is GiteaRefreshAction && it.refresher(context) != null }
    if (!ownRefreshApplies) {
      return null
    }
    return actions.filter { it !is GiteaRefreshAction }
  }
}
