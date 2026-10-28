package de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff

import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentUIUtil
import com.intellij.diff.util.Side
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaCommentComponents
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaCommentComponents.SubmittableText
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaCommentComponents.action
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaCommentComponents.runCatchingUi
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Swing components of inline review threads (diff inlays), built from [GiteaCommentComponents]. */
object GiteaReviewThreadComponentFactory {
  private val componentType = CodeReviewChatItemUIUtil.ComponentType.COMPACT

  fun createThread(cs: CoroutineScope, reviewVm: GiteaChangeReviewViewModel, mapped: GiteaMappedThread): JComponent {
    val diffVm = reviewVm.diffVm
    val content = GiteaCommentComponents.createThreadBody(cs, diffVm.commentContext, diffVm.reviewData, mapped.thread, componentType) { comment ->
      suggestionOf(comment.body)?.takeIf { mapped.side == Side.RIGHT }?.let { suggestion ->
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
          isOpaque = false
          border = JBUI.Borders.emptyLeft(componentType.contentLeftShift)
          add(ActionLink(GiteaBundle.message("review.suggestion.apply")) {
            if (!reviewVm.applySuggestion(mapped, suggestion)) {
              NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
                .createNotification(GiteaBundle.message("review.suggestion.apply.failed"), NotificationType.WARNING)
                .setDisplayId(GiteaNotificationIds.REVIEW_CHANGE_FAILED)
                .notify(diffVm.project)
            }
          })
        }
      }
    }
    // same space above the first comment as the Reply/Resolve row leaves below the last one
    content.border = JBUI.Borders.emptyTop(THREAD_LINKS_VERTICAL_PADDING * 2)
    return withTopGap(CodeReviewCommentUIUtil.createEditorInlayPanel(content))
  }

  /** Top/bottom border of the Reply/Resolve row in [GiteaCommentComponents.createThreadBody]. */
  private const val THREAD_LINKS_VERTICAL_PADDING = 4

  /** The bubble indented from the code and with some space to the commented line (like the other Gitea plugin). */
  private fun withTopGap(inlay: JComponent): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(8, 32, 4, 0)
    add(inlay)
  }

  /** Content of the first ```suggestion block, `null` if there is none. */
  fun suggestionOf(body: String): String? =
    Regex("```suggestion[^\\n]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)

  /** A new comment at a diff line: "Add to review" (pending) or "Add single comment". */
  fun createNewComment(cs: CoroutineScope, reviewVm: GiteaChangeReviewViewModel, newComment: GiteaNewCommentViewModel): JComponent {
    val ctx = reviewVm.diffVm.commentContext
    val pending = SubmittableText(ctx.project, cs, "") { text ->
      newComment.text.value = text
      reviewVm.submitNewComment(newComment, pending = true)
    }
    val single = action(GiteaBundle.message("review.comment.add.single")) {
      newComment.text.value = pending.text.value
      cs.launch { runCatchingUi { reviewVm.submitNewComment(newComment, pending = false) } }
    }
    val suggestion = newComment.lineText?.takeIf { newComment.side == Side.RIGHT }?.let { line ->
      action(GiteaBundle.message("review.suggestion.insert")) {
        val block = "```suggestion\n$line\n```"
        pending.text.value = when {
          pending.text.value.isBlank() -> block
          else -> pending.text.value.trimEnd() + "\n" + block
        }
      }
    }
    val field = GiteaCommentComponents.createTextField(cs, ctx, componentType, pending, GiteaBundle.message("review.comment.add.to.review"),
                                                       onCancel = { reviewVm.cancelNewComment(newComment.side, newComment.line) },
                                                       secondary = listOf(single), additional = listOfNotNull(suggestion))
    pending.requestFocus()
    return withTopGap(CodeReviewCommentUIUtil.createEditorInlayPanel(JPanel().apply {
      isOpaque = false
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      border = JBUI.Borders.empty(componentType.inputPaddingInsets)
      add(field)
    }))
  }
}
