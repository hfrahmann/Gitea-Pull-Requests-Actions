package de.hendrikfrahmann.giteaplugin.actions.ui

import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.ui.AnimatedIcon
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import org.jetbrains.annotations.Nls
import javax.swing.Icon

object GiteaActionsUiUtil {
  /** The icons the platform's CI status uses (PR details), so runs look the same everywhere. */
  fun statusIcon(status: GiteaActionStatus, animated: Boolean = false): Icon = when (status) {
    GiteaActionStatus.SUCCESS -> CIBuildStatusIcons.success
    GiteaActionStatus.FAILURE -> CIBuildStatusIcons.failed
    GiteaActionStatus.CANCELLED -> CIBuildStatusIcons.cancelled
    GiteaActionStatus.SKIPPED -> CIBuildStatusIcons.skipped
    GiteaActionStatus.RUNNING -> when {
      animated -> AnimatedIcon.Default.INSTANCE
      else -> CIBuildStatusIcons.inProgress
    }
    GiteaActionStatus.QUEUED -> CIBuildStatusIcons.pending
    GiteaActionStatus.WAITING -> CIBuildStatusIcons.paused
    GiteaActionStatus.UNKNOWN -> CIBuildStatusIcons.info
  }

  fun statusText(status: GiteaActionStatus): @Nls String = GiteaBundle.message("actions.status.${status.name.lowercase()}")

  /** `850ms`, `42s`, `3m 5s`, `1h 2m` */
  fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    return when {
      seconds < 1 -> "${millis}ms"
      seconds < 60 -> "${seconds}s"
      seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
      else -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
    }
  }
}
