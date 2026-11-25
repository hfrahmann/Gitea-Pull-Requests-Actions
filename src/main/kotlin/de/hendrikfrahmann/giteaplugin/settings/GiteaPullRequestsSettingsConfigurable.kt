package de.hendrikfrahmann.giteaplugin.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle

/** `Settings | Tools | Gitea | Pull Requests`; child of [GiteaGeneralSettingsConfigurable]. */
class GiteaPullRequestsSettingsConfigurable
  : BoundConfigurable(GiteaBundle.message("settings.pull.requests.display.name"), HELP_TOPIC) {

  override fun createPanel(): DialogPanel {
    val settings = GiteaSettings.getInstance()
    return panel {
      row {
        checkBox(GiteaBundle.message("settings.reviewers.all.users"))
          .bindSelected({ settings.reviewersFromAllUsers }, { settings.reviewersFromAllUsers = it })
      }
      row {
        checkBox(GiteaBundle.message("settings.open.timeline.from.list"))
          .bindSelected({ settings.openTimelineFromList }, { settings.openTimelineFromList = it })
          .comment(GiteaBundle.message("settings.open.timeline.from.list.comment"))
      }
    }
  }

  companion object {
    const val HELP_TOPIC: String = "settings.gitea.pull.requests"
  }
}
