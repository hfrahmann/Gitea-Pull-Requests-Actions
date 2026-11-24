package de.hendrikfrahmann.giteaplugin.ui

import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJob
import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJobState
import com.intellij.collaboration.ui.codereview.list.search.ChooserPopupUtil
import com.intellij.collaboration.ui.codereview.list.search.PopupConfig
import com.intellij.collaboration.ui.codereview.list.search.ShowDirection
import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsNavigator
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaCiCheck
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import org.jetbrains.annotations.Nls
import javax.swing.Icon
import javax.swing.JList

/**
 * The checks of a commit in a popup (PR details "Details", the Git log's "Gitea Checks" column): name and Gitea's status
 * text in grey ("Successful in 1m 2s"). A picked Gitea Actions job opens in the Actions tool window with its log, other
 * checks in the browser. A check without a link (Gitea writes skipped jobs with an empty `target_url`) says so in a balloon
 * instead of doing nothing.
 */
object GiteaCiChecksPopup {
  suspend fun chooseAndOpen(point: RelativePoint, navigator: GiteaActionsNavigator, checks: List<GiteaCiCheck>,
                            showDirection: ShowDirection = ShowDirection.ABOVE,
                            title: @Nls String = GiteaBundle.message("pull.request.details.ci.popup.title")) {
    val selected = ChooserPopupUtil.showChooserPopup(
      point,
      checks,
      // own renderer: the platform's presentation greys its short text, which is also what speed search matches – keep that the name
      { check -> check.job.name },
      object : ColoredListCellRenderer<GiteaCiCheck>() {
        override fun customizeCellRenderer(list: JList<out GiteaCiCheck>, value: GiteaCiCheck, index: Int, selected: Boolean,
                                           hasFocus: Boolean) {
          icon = value.job.status.icon()
          append(value.job.name)
          value.description?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
      },
      PopupConfig(
        // not the platform's "Open in Browser": Gitea jobs open in the Actions tool window
        title = title,
        alwaysShowSearchField = false,
        showDirection = showDirection,
      ),
    ) ?: return
    open(point, navigator, selected.job)
  }

  /** [job] in the Actions tool window if it is a Gitea Actions job of the connected repository, else in the browser. */
  fun open(point: RelativePoint, navigator: GiteaActionsNavigator, job: CodeReviewCIJob) {
    val url = job.detailsUrl
    if (url == null) {
      showNoTarget(point, job)
      return
    }
    if (!navigator.showJobLink(url)) {
      BrowserUtil.browse(url)
    }
  }

  private fun showNoTarget(point: RelativePoint, job: CodeReviewCIJob) {
    val key = when (job.status) {
      CodeReviewCIJobState.SKIPPED -> "pull.request.details.ci.no.target.skipped"
      else -> "pull.request.details.ci.no.target"
    }
    JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(HtmlChunk.text(GiteaBundle.message(key, job.name)).toString(), MessageType.INFO, null)
      .setFadeoutTime(BALLOON_FADEOUT_MS)
      .createBalloon()
      .show(point, Balloon.Position.above)
  }

  private fun CodeReviewCIJobState.icon(): Icon = when (this) {
    CodeReviewCIJobState.FAILED -> CIBuildStatusIcons.failed
    CodeReviewCIJobState.PENDING -> AnimatedIcon.Default.INSTANCE
    CodeReviewCIJobState.SKIPPED -> CIBuildStatusIcons.skipped
    CodeReviewCIJobState.SUCCESS -> CIBuildStatusIcons.success
  }

  private const val BALLOON_FADEOUT_MS = 8000L
}
