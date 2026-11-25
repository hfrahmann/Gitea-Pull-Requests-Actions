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

/** `Gitea.Open.GeneralSettings`: `Tools | Gitea` (refresh, with the child pages Pull Requests, Actions and MCP). */
class GiteaOpenGeneralSettingsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    ShowSettingsUtil.getInstance().showSettingsDialog(e.project, GiteaGeneralSettingsConfigurable::class.java)
  }
}

/** `Gitea.Open.ActionsSettings`: `Tools | Gitea | Actions` (runs, artifacts, local workflow runs). */
class GiteaOpenActionsSettingsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    ShowSettingsUtil.getInstance().showSettingsDialog(e.project, GiteaActionsSettingsConfigurable::class.java)
  }
}
