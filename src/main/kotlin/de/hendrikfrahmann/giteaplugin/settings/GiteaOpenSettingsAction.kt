package de.hendrikfrahmann.giteaplugin.settings

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction

/** `Gitea.Open.Settings`: the Gitea page with the accounts. */
class GiteaOpenSettingsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    ShowSettingsUtil.getInstance().showSettingsDialog(e.project, GiteaSettingsConfigurable::class.java)
  }
}

/** `Gitea.Open.GeneralSettings`: the "Settings" page (refresh, Gitea Actions, …). */
class GiteaOpenGeneralSettingsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    ShowSettingsUtil.getInstance().showSettingsDialog(e.project, GiteaGeneralSettingsConfigurable::class.java)
  }
}
