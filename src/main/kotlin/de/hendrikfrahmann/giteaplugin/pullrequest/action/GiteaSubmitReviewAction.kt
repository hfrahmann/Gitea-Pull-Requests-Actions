package de.hendrikfrahmann.giteaplugin.pullrequest.action

import com.intellij.CommonBundle
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.ColorUtil
import com.intellij.ui.InplaceButton
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReviewEvent
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestReviewState
import de.hendrikfrahmann.giteaplugin.pullrequest.review.GiteaCurrentBranchPullRequestService
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff.GiteaPullRequestDiffViewModel
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionListener
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * `Gitea.PullRequest.Review.Submit` in the review diff toolbar: submits the pending review (or a review with just a body)
 * as Approve / Request changes / Comment, or discards the pending review. Gitea refuses approving or requesting changes
 * on one's own PR.
 */
class GiteaSubmitReviewAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val vm = diffVm(e)
    e.presentation.isEnabledAndVisible = vm != null && !vm.connection.isAnonymous
    val pending = pendingCommentCount(vm?.reviewData?.state?.value?.getOrNull())
    e.presentation.text = when {
      pending > 0 -> GiteaBundle.message("action.Gitea.PullRequest.Review.Submit.pending.text", pending)
      else -> GiteaBundle.message("action.Gitea.PullRequest.Review.Submit.text")
    }
  }

  override fun actionPerformed(e: AnActionEvent) {
    val vm = diffVm(e) ?: return
    val component = e.inputEvent?.component as? JComponent
    val popup = SubmitReviewPopup(vm).create()
    when {
      e.isFromActionToolbar && e.inputEvent is MouseEvent && component != null -> popup.show(RelativePoint.getSouthWestOf(component))
      else -> popup.showInFocusCenter()
    }
  }

  /** The review diff's PR, else (branch popup) the PR of the current branch. */
  private fun diffVm(e: AnActionEvent): GiteaPullRequestDiffViewModel? =
    e.getData(DiffDataKeys.DIFF_CONTEXT)?.getUserData(GiteaPullRequestDiffViewModel.KEY)
    ?: e.project?.serviceIfCreated<GiteaCurrentBranchPullRequestService>()?.current?.value?.let { it.projectVm.diffViewModel(it.number) }
}

private fun pendingCommentCount(state: GiteaPullRequestReviewState?): Int =
  state?.threads?.filter { it.isPending }?.sumOf { it.comments.size } ?: 0

/** Title with the number of pending comments, discard and close buttons, the review text and the three verdicts. */
private class SubmitReviewPopup(vm: GiteaPullRequestDiffViewModel) {
  private val reviewData = vm.reviewData
  private val state = reviewData.state.value?.getOrNull()
  private val pullRequest = vm.dataProvider.snapshot.value?.getOrNull()?.pullRequest
  private val isOwnPullRequest = pullRequest?.user?.login.equals(vm.connection.currentUser?.login, ignoreCase = true)

  // own scope: the popup closes before the request finishes
  private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val textArea = JBTextArea(TEXT_ROWS, TEXT_COLUMNS).apply {
    lineWrap = true
    wrapStyleWord = true
    emptyText.text = GiteaBundle.message("review.submit.placeholder")
  }
  private lateinit var popup: JBPopup

  fun create(): JBPopup {
    val panel = JPanel(BorderLayout(0, JBUI.scale(GAP))).apply {
      border = JBUI.Borders.empty(INSET)
      add(header(), BorderLayout.NORTH)
      add(JBScrollPane(textArea), BorderLayout.CENTER)
      add(verdictButtons(), BorderLayout.SOUTH)
    }
    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, textArea)
      .setRequestFocus(true)
      .setMovable(true)
      .setResizable(true)
      .setCancelOnClickOutside(true)
      .createPopup()
    return popup
  }

  private fun header(): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    add(titleLabel(), BorderLayout.CENTER)
    add(headerButtons(), BorderLayout.EAST)
  }

  private fun titleLabel(): JBLabel {
    val title = HtmlBuilder().append(HtmlChunk.text(GiteaBundle.message("review.submit.title")).bold())
    val pending = pendingCommentCount(state)
    if (pending > 0) {
      title.nbsp().append(HtmlChunk.text(GiteaBundle.message("review.submit.pending.short", pending))
                            .wrapWith(HtmlChunk.font(ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground()))))
    }
    return JBLabel(title.wrapWithHtmlBody().toString())
  }

  /** "Discard" (only with a pending review) and "Close". */
  private fun headerButtons(): JComponent = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(GAP), 0)).apply {
    isOpaque = false
    if (state?.hasPendingReview == true) {
      add(InplaceButton(IconButton(GiteaBundle.message("review.submit.discard"), AllIcons.Actions.GC), ActionListener { discard() }))
    }
    val close = IconButton(CommonBundle.getCloseButtonText(), AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)
    add(InplaceButton(close, ActionListener { popup.cancel() }))
  }

  private fun verdictButtons(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(BUTTON_GAP), 0)).apply {
    isOpaque = false
    add(verdictButton(GiteaBundle.message("review.submit.approve"), GiteaReviewEvent.APPROVED,
                      ownPullRequestToolTip = GiteaBundle.message("review.submit.approve.own")))
    add(verdictButton(GiteaBundle.message("review.submit.request.changes"), GiteaReviewEvent.REQUEST_CHANGES,
                      ownPullRequestToolTip = GiteaBundle.message("review.submit.request.changes.own")))
    add(verdictButton(GiteaBundle.message("review.submit.comment"), GiteaReviewEvent.COMMENT, ownPullRequestToolTip = null))
  }

  /** [ownPullRequestToolTip] `!= null`: Gitea refuses this verdict on one's own PR, so the button is disabled there. */
  private fun verdictButton(text: @Nls String, event: GiteaReviewEvent, ownPullRequestToolTip: @Nls String?): JButton = JButton(text).apply {
    if (isOwnPullRequest && ownPullRequestToolTip != null) {
      isEnabled = false
      toolTipText = ownPullRequestToolTip
    }
    addActionListener { submit(event) }
  }

  private fun submit(event: GiteaReviewEvent) {
    popup.closeOk(null)
    cs.launch { notifyOnError { reviewData.submitReview(event, textArea.text, pullRequest?.head?.sha) } }
  }

  private fun discard() {
    popup.closeOk(null)
    cs.launch { notifyOnError { reviewData.discardPendingReview() } }
  }

  private companion object {
    const val TEXT_ROWS = 5
    const val TEXT_COLUMNS = 50
    const val GAP = 8
    const val BUTTON_GAP = 6
    const val INSET = 10
  }
}

private suspend fun notifyOnError(block: suspend () -> Unit) {
  try {
    block()
  }
  catch (e: CancellationException) {
    throw e
  }
  catch (e: Exception) {
    NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
      .createNotification(GiteaBundle.message("review.change.failed"), e.localizedMessage.orEmpty(), NotificationType.ERROR)
      .setDisplayId(GiteaNotificationIds.REVIEW_CHANGE_FAILED)
      .notify(null)
  }
}
