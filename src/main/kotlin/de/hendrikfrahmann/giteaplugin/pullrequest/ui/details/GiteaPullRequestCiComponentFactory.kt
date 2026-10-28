package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details

import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJob
import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJobState
import com.intellij.collaboration.ui.codereview.list.search.ChooserPopupUtil
import com.intellij.collaboration.ui.codereview.list.search.PopupConfig
import com.intellij.collaboration.ui.codereview.list.search.ShowDirection
import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsNavigator
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestStatusViewModel
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Nls
import java.awt.Point
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList

/**
 * The CI row of the PR details in the platform look (`CodeReviewDetailsStatusComponentFactory.createCiComponent`), but
 * a Gitea Actions job picked in "Details" opens in the Actions tool window with its log; external CI keeps its
 * browser link. The link says "Details" without the platform's ellipsis, and "Show Runs" lives in the "more" and context
 * menus (`Gitea.PullRequest.ShowActions`): both keep the line short enough for the narrow tool window.
 */
object GiteaPullRequestCiComponentFactory {
  fun create(cs: CoroutineScope, project: Project, statusVm: GiteaPullRequestStatusViewModel): JComponent {
    val navigator = GiteaActionsNavigator.getInstance(project)
    val detailsLink = ActionLink(GiteaBundle.message("pull.request.details.ci.details")) { statusVm.showJobsDetails() }
    val row = GiteaStatusRow("Code review status: CI", listOf(detailsLink))

    cs.launch(Dispatchers.EDT) {
      statusVm.ciJobs.collect { jobs ->
        row.isVisible = jobs.isNotEmpty()
        row.icon = pipelineIcon(jobs)
        row.text = pipelineText(jobs)
      }
    }
    cs.launch(Dispatchers.EDT) {
      statusVm.showJobsDetailsRequests.collectLatest { jobs -> chooseAndOpenJob(detailsLink, navigator, jobs, statusVm::descriptionOf) }
    }
    return row
  }

  /**
   * The jobs popup: name and Gitea's status text in grey ("Successful in 1m 2s"); a Gitea Actions job opens in the Actions
   * tool window, others in the browser. A check without a link (Gitea writes skipped jobs with an empty `target_url`)
   * says so in a balloon instead of doing nothing.
   */
  private suspend fun chooseAndOpenJob(anchor: JComponent, navigator: GiteaActionsNavigator, jobs: List<CodeReviewCIJob>,
                                       descriptionOf: (CodeReviewCIJob) -> String?) {
    val selected = ChooserPopupUtil.showChooserPopup(
      RelativePoint(anchor, Point()),
      jobs,
      // own renderer: the platform's presentation greys its short text, which is also what speed search matches – keep that the name
      { job -> job.name },
      object : ColoredListCellRenderer<CodeReviewCIJob>() {
        override fun customizeCellRenderer(list: JList<out CodeReviewCIJob>, value: CodeReviewCIJob, index: Int, selected: Boolean,
                                           hasFocus: Boolean) {
          icon = value.status.icon()
          append(value.name)
          descriptionOf(value)?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
      },
      PopupConfig(
        // not the platform's "Open in Browser": Gitea jobs open in the Actions tool window
        title = GiteaBundle.message("pull.request.details.ci.popup.title"),
        alwaysShowSearchField = false,
        showDirection = ShowDirection.ABOVE,
      ),
    ) ?: return
    val url = selected.detailsUrl
    if (url == null) {
      showNoTarget(anchor, selected)
      return
    }
    if (!navigator.showJobLink(url)) {
      BrowserUtil.browse(url)
    }
  }

  private fun showNoTarget(anchor: JComponent, job: CodeReviewCIJob) {
    val key = when (job.status) {
      CodeReviewCIJobState.SKIPPED -> "pull.request.details.ci.no.target.skipped"
      else -> "pull.request.details.ci.no.target"
    }
    JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(HtmlChunk.text(GiteaBundle.message(key, job.name)).toString(), MessageType.INFO, null)
      .setFadeoutTime(BALLOON_FADEOUT_MS)
      .createBalloon()
      .show(RelativePoint.getNorthWestOf(anchor), Balloon.Position.above)
  }

  private fun pipelineIcon(jobs: List<CodeReviewCIJob>): Icon {
    val failed = jobs.count { it.status == CodeReviewCIJobState.FAILED }
    val pending = jobs.count { it.status == CodeReviewCIJobState.PENDING }
    return when {
      failed == 0 && pending == 0 -> CIBuildStatusIcons.success
      pending != 0 && failed != 0 -> CIBuildStatusIcons.failedInProgress
      // like running jobs in the Actions tool window (the platform's "pending" is a plain dot)
      pending != 0 -> AnimatedIcon.Default.INSTANCE
      else -> CIBuildStatusIcons.failed
    }
  }

  private fun pipelineText(jobs: List<CodeReviewCIJob>): @Nls String {
    val failed = jobs.count { it.status == CodeReviewCIJobState.FAILED }
    val pending = jobs.count { it.status == CodeReviewCIJobState.PENDING }
    return when {
      failed == 0 && pending == 0 -> CollaborationToolsBundle.message("review.details.status.ci.passed")
      pending != 0 && failed != 0 -> CollaborationToolsBundle.message("review.details.status.ci.progress.and.failed")
      pending != 0 -> CollaborationToolsBundle.message("review.details.status.ci.progress")
      else -> CollaborationToolsBundle.message("review.details.status.ci.failed")
    }
  }

  private fun CodeReviewCIJobState.icon(): Icon = when (this) {
    CodeReviewCIJobState.FAILED -> CIBuildStatusIcons.failed
    CodeReviewCIJobState.PENDING -> AnimatedIcon.Default.INSTANCE
    CodeReviewCIJobState.SKIPPED -> CIBuildStatusIcons.skipped
    CodeReviewCIJobState.SUCCESS -> CIBuildStatusIcons.success
  }

  private const val BALLOON_FADEOUT_MS = 8000L
}
