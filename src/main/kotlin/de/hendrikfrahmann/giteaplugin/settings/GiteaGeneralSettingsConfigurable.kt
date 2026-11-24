package de.hendrikfrahmann.giteaplugin.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle

/**
 * `Settings | Tools | Gitea` – how often the views refresh; child pages [GiteaPullRequestsSettingsConfigurable],
 * [GiteaActionsSettingsConfigurable] and [GiteaMcpSettingsConfigurable]. The accounts are on [GiteaSettingsConfigurable].
 * All pages store in [GiteaSettings].
 */
class GiteaGeneralSettingsConfigurable : BoundConfigurable(GiteaBundle.message("settings.general.display.name"), HELP_TOPIC) {
  override fun createPanel(): DialogPanel {
    val settings = GiteaSettings.getInstance()
    return panel {
      refreshGroup(settings)
    }
  }

  @Suppress("DialogTitleCapitalization") // the units after the number fields are lower case
  private fun Panel.refreshGroup(settings: GiteaSettings) {
    group(GiteaBundle.message("settings.refresh.group")) {
      row(GiteaBundle.message("settings.refresh.fast")) {
        intTextField(GiteaSettings.REFRESH_FAST_RANGE)
          .columns(SETTINGS_NUMBER_COLUMNS)
          .bindIntText({ settings.refreshFastSeconds }, { settings.refreshFastSeconds = it })
          .gap(RightGap.SMALL)
        label(GiteaBundle.message("settings.seconds"))
      }
      row(GiteaBundle.message("settings.refresh.slow")) {
        intTextField(GiteaSettings.REFRESH_SLOW_RANGE)
          .columns(SETTINGS_NUMBER_COLUMNS)
          .bindIntText({ settings.refreshSlowSeconds }, { settings.refreshSlowSeconds = it })
          .gap(RightGap.SMALL)
        label(GiteaBundle.message("settings.seconds"))
      }
      row(GiteaBundle.message("settings.refresh.log.checks")) {
        intTextField(GiteaSettings.LOG_CHECKS_RECHECK_RANGE)
          .columns(SETTINGS_NUMBER_COLUMNS)
          .bindIntText({ settings.logChecksRecheckMinutes }, { settings.logChecksRecheckMinutes = it })
          .gap(RightGap.SMALL)
        label(GiteaBundle.message("settings.minutes"))
      }.rowComment(GiteaBundle.message("settings.refresh.log.checks.comment"))
      row {
        checkBox(GiteaBundle.message("settings.refresh.pause"))
          .bindSelected({ settings.pauseRefreshInBackground }, { settings.pauseRefreshInBackground = it })
          .comment(GiteaBundle.message("settings.refresh.pause.comment"))
      }
    }
  }

  companion object {
    const val HELP_TOPIC: String = "settings.gitea.general"
  }
}

/** Width of the number fields on the Gitea settings pages. */
internal const val SETTINGS_NUMBER_COLUMNS = 4
