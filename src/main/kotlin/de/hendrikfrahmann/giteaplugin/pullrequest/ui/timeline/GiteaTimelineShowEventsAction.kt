package de.hendrikfrahmann.giteaplugin.pullrequest.ui.timeline

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings

/** `Gitea.PullRequest.Timeline.Show.Events` – system events in timelines on/off (all open timelines follow). */
class GiteaTimelineShowEventsAction : DumbAwareToggleAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.isEnabledAndVisible = e.getData(GiteaPullRequestTimelineViewModel.DATA_KEY) != null
  }

  override fun isSelected(e: AnActionEvent): Boolean = GiteaSettings.getInstance().showTimelineEvents

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    GiteaSettings.getInstance().showTimelineEvents = state
  }
}
