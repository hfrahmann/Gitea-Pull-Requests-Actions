package de.hendrikfrahmann.giteaplugin.pullrequest.ui.timeline

import com.intellij.collaboration.ui.CollaborationToolsUIUtil
import com.intellij.collaboration.ui.codereview.timeline.StatusMessageType
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.ColorUtil
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaTimelineItem
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaTimelineItem.EventKind
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle

/**
 * What the timeline says about events and review verdicts, as HTML. Bundle templates are plain text without markup
 * characters; everything users wrote (titles, names, labels, reasons) is escaped before it goes in.
 */
internal object GiteaTimelineTexts {
  private const val SHORT_SHA_LENGTH = 7
  private const val FORCE_PUSH_SHA_LENGTH = 10

  /** [commitUrl] links commits of push events; `null` shows the sha as code. */
  fun eventHtml(item: GiteaTimelineItem.Event, commitUrl: (String) -> String? = { null }): String = when (val kind = item.kind) {
    is EventKind.Pushed -> pushHtml(kind, commitUrl)
    is EventKind.Labels -> labelsHtml(kind)
    is EventKind.ReviewRequests -> reviewRequestsHtml(kind)
    is EventKind.Assignee -> assigneeHtml(kind, item.actor)
    is EventKind.Title -> message("pull.request.timeline.event.title", HtmlChunk.text(kind.old).wrapWith("s").toString(), bold(kind.new))
    is EventKind.Milestone -> milestoneHtml(kind)
    is EventKind.BranchDeleted -> message("pull.request.timeline.event.branch.deleted", code(kind.ref))
    is EventKind.TargetBranchChanged -> message("pull.request.timeline.event.target.branch", code(kind.old), code(kind.new))
    is EventKind.ReviewDismissed -> reviewDismissedHtml(kind)
    EventKind.Closed -> plainText("pull.request.timeline.event.closed")
    EventKind.Reopened -> plainText("pull.request.timeline.event.reopened")
    EventKind.Merged -> plainText("pull.request.timeline.event.merged")
  }

  /**
   * Several event lines of one bar, one paragraph each: the word-wrapping pane loses a `<br>` between them, so the next
   * event ran on right after the previous one ("…milestoneself-assigned this"). A single line (e.g. a push with its own
   * paragraphs) stays as it is.
   */
  fun eventLinesHtml(lines: List<String>): String =
    lines.singleOrNull() ?: lines.joinToString("") { HtmlChunk.raw(it).wrapWith("p").toString() }

  /** "approved", "requested changes" or "reviewed" – "dismissed" in front when the review was dismissed. */
  fun reviewVerdict(item: GiteaTimelineItem.Review): String {
    val key = when (item.state) {
      "APPROVED" -> "pull.request.timeline.review.approved"
      "REQUEST_CHANGES" -> "pull.request.timeline.review.changes"
      else -> "pull.request.timeline.review.commented"
    }
    val verdict = GiteaBundle.message(key)
    val text = when {
      item.dismissed -> GiteaBundle.message("pull.request.timeline.review.dismissed", verdict)
      else -> verdict
    }
    return HtmlChunk.text(text).toString()
  }

  fun reviewMessageType(item: GiteaTimelineItem.Review): StatusMessageType = when {
    item.dismissed -> StatusMessageType.SECONDARY_INFO
    item.state == "APPROVED" -> StatusMessageType.SUCCESS
    item.state == "REQUEST_CHANGES" -> StatusMessageType.WARNING
    else -> StatusMessageType.INFO
  }

  fun eventMessageType(kind: EventKind): StatusMessageType = when (kind) {
    EventKind.Merged -> StatusMessageType.SUCCESS
    EventKind.Closed -> StatusMessageType.ERROR
    else -> StatusMessageType.SECONDARY_INFO
  }

  /** A force push names old and new head; a normal push lists its commits, one paragraph each. */
  private fun pushHtml(kind: EventKind.Pushed, commitUrl: (String) -> String?): String {
    if (kind.force) {
      val oldHead = kind.commits.firstOrNull()?.sha?.take(FORCE_PUSH_SHA_LENGTH).orEmpty()
      val newHead = kind.commits.getOrNull(1)?.sha?.take(FORCE_PUSH_SHA_LENGTH).orEmpty()
      return message("pull.request.timeline.event.force.pushed", code(oldHead), code(newHead))
    }
    // one paragraph per line: a <br> before the first commit link is lost in the word-wrapping pane
    val builder = HtmlBuilder().append(HtmlChunk.p().addText(GiteaBundle.message("pull.request.timeline.event.pushed", kind.commits.size)))
    for ((fullSha, title) in kind.commits) {
      val sha = fullSha.take(SHORT_SHA_LENGTH)
      val line = HtmlBuilder().appendRaw(commitUrl(fullSha)?.let { HtmlChunk.link(it, sha).toString() } ?: code(sha))
      title?.takeIf { it.isNotBlank() }?.let { line.nbsp().append(it) }
      builder.append(line.wrapWith("p"))
    }
    return builder.toString()
  }

  private fun labelsHtml(kind: EventKind.Labels): String = joinChanges(
    kind.added.takeIf { it.isNotEmpty() }?.let { message("pull.request.timeline.event.labels.added", it.size, labelChips(it)) },
    kind.removed.takeIf { it.isNotEmpty() }?.let { message("pull.request.timeline.event.labels.removed", it.size, labelChips(it)) },
  )

  private fun reviewRequestsHtml(kind: EventKind.ReviewRequests): String = joinChanges(
    kind.added.takeIf { it.isNotEmpty() }?.let { message("pull.request.timeline.event.review.requested", boldList(it)) },
    kind.removed.takeIf { it.isNotEmpty() }?.let { message("pull.request.timeline.event.review.request.removed", boldList(it)) },
  )

  private fun assigneeHtml(kind: EventKind.Assignee, actor: GiteaUserDTO?): String {
    val assignee = kind.user?.login
    val isSelf = assignee != null && assignee == actor?.login
    return when {
      isSelf && kind.removed -> plainText("pull.request.timeline.event.self.unassigned")
      isSelf -> plainText("pull.request.timeline.event.self.assigned")
      kind.removed -> message("pull.request.timeline.event.unassigned", bold(assignee.orEmpty()))
      else -> message("pull.request.timeline.event.assigned", bold(assignee.orEmpty()))
    }
  }

  private fun milestoneHtml(kind: EventKind.Milestone): String = when {
    kind.added != null && kind.removed != null -> message("pull.request.timeline.event.milestone.changed", bold(kind.removed), bold(kind.added))
    kind.added != null -> message("pull.request.timeline.event.milestone.added", bold(kind.added))
    else -> message("pull.request.timeline.event.milestone.removed", bold(kind.removed.orEmpty()))
  }

  private fun reviewDismissedHtml(kind: EventKind.ReviewDismissed): String {
    val text = message("pull.request.timeline.event.review.dismissed", bold(kind.reviewer?.login.orEmpty()))
    if (kind.reason.isBlank()) {
      return text
    }
    return HtmlBuilder().appendRaw(text).br().append(kind.reason).toString()
  }

  /** "added X and removed Y" */
  private fun joinChanges(added: String?, removed: String?): String =
    listOfNotNull(added, removed).joinToString(plainText("pull.request.timeline.event.and"))

  /** Label chips in the label's color (like GitHub's timeline). */
  private fun labelChips(labels: List<GiteaLabelDTO>): String = labels.joinToString(" ") { label ->
    val background = label.color?.let { runCatching { CollaborationToolsUIUtil.getLabelBackground(it) }.getOrNull() }
                     ?: return@joinToString bold(label.name)
    val foreground = CollaborationToolsUIUtil.getLabelForeground(background)
    HtmlChunk.span("color: #${ColorUtil.toHex(foreground)}; background: #${ColorUtil.toHex(background)}")
      .child(HtmlChunk.nbsp()).addText(label.name).child(HtmlChunk.nbsp()).toString()
  }

  /** A bundle template filled with already escaped HTML parameters. */
  private fun message(key: String, vararg params: Any): String = GiteaBundle.message(key, *params)

  private fun plainText(key: String): String = HtmlChunk.text(GiteaBundle.message(key)).toString()

  private fun bold(text: String): String = HtmlChunk.text(text).bold().toString()

  private fun boldList(names: List<String>): String = names.joinToString(", ", transform = ::bold)

  private fun code(text: String): String = HtmlChunk.text(text).code().toString()
}
