package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details

import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJob
import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJobState
import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsNavigator
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaCiCheck
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestStatusViewModel
import de.hendrikfrahmann.giteaplugin.ui.GiteaCiChecksPopup
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Nls
import java.awt.Point
import javax.swing.Icon
import javax.swing.JComponent

/**
 * The CI row of the PR details in the platform look (`CodeReviewDetailsStatusComponentFactory.createCiComponent`), but
 * a Gitea Actions job picked in "Details" opens in the Actions tool window with its log ([GiteaCiChecksPopup]); external
 * CI keeps its browser link. The link says "Details" without the platform's ellipsis, and "Show Runs" lives in the "more"
 * and context menus (`Gitea.PullRequest.ShowActions`): both keep the line short enough for the narrow tool window.
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
      statusVm.showJobsDetailsRequests.collectLatest { jobs ->
        val checks = jobs.map { GiteaCiCheck(it, statusVm.descriptionOf(it)) }
        GiteaCiChecksPopup.chooseAndOpen(RelativePoint(detailsLink, Point()), navigator, checks)
      }
    }
    return row
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
}
