package de.hendrikfrahmann.giteaplugin.pullrequest

import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewCommentDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaTimelineEventDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaReviewThread
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaTimelineItem
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaTimelineItem.EventKind
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.timeline.GiteaPullRequestTimelineComponentFactory
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.timeline.GiteaTimelineTexts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GiteaTimelineItemTest {
  private fun event(json: String): GiteaTimelineEventDTO = GiteaJson.mapper.readValue(json, GiteaTimelineEventDTO::class.java)

  private fun event(id: Long, type: String, at: String, user: String = "intellij-dev", extra: String = "") =
    event("""{"id":$id,"type":"$type","created_at":"$at","user":{"id":1,"login":"$user"}$extra}""")

  private fun label(id: Long, at: String, labelId: Long, added: Boolean, user: String = "intellij-dev"): GiteaTimelineEventDTO {
    val body = if (added) {
      "\"body\":\"1\","
    }
    else {
      ""
    }
    return event(id, "label", at, user, """,$body"label":{"id":$labelId,"name":"l$labelId","color":"ee0701"}""")
  }

  private fun review(id: Long, state: String) =
    GiteaJson.mapper.readValue("""{"id":$id,"state":"$state","user":{"id":4,"login":"intellij-reviewer"}}""", GiteaReviewDTO::class.java)

  private fun comment(id: Long, review: Long, hunk: String = "@@ -0,0 +1,4 @@\n+line1\n+line2") =
    GiteaJson.mapper.readValue("""{"id":$id,"body":"c$id","user":{"id":4,"login":"intellij-reviewer"},"pull_request_review_id":$review,
      "path":"probe/wp08.txt","position":2,"commit_id":"58298823","created_at":"2026-10-21T10:00:00Z","diff_hunk":${GiteaJson.mapper.writeValueAsString(hunk)}}""",
                               GiteaReviewCommentDTO::class.java)

  @Test
  fun `order and content like the web UI of PR 221`() {
    // shape of PR #221 on 28.1: review 85 listed three times, label added and removed in one go
    val events = listOf(
      event(614, "pull_push", "2026-10-21T18:04:59+02:00", extra = ""","body":"{\"is_force_push\":false,\"commit_ids\":[\"58298823f3\"]}""""),
      event(616, "review", "2026-10-21T18:04:59+02:00", "intellij-reviewer", ""","review_id":85"""),
      event(618, "review", "2026-10-21T18:04:59+02:00", "intellij-reviewer", ""","review_id":85"""),
      event(619, "review", "2026-10-21T18:05:15+02:00", "intellij-reviewer", ""","review_id":85,"body":"review body""""),
      event(620, "comment", "2026-10-21T18:05:20+02:00", extra = ""","body":"thanks""""),
      event(702, "change_title", "2026-10-21T18:52:34+02:00", extra = ""","old_title":"a","new_title":"b""""),
      label(703, "2026-10-21T18:52:34+02:00", 1, added = true),
      label(704, "2026-10-21T18:52:34+02:00", 1, added = false),
      event(705, "milestone", "2026-10-21T18:52:34+02:00", extra = ""","milestone":{"id":1,"title":"v1.0"}"""),
      event(707, "close", "2026-10-21T18:52:34+02:00"),
      event(709, "something_new", "2026-10-21T18:52:35+02:00"),
    )
    val threads = GiteaReviewThread.build(listOf(review(85, "COMMENT")), mapOf(85L to listOf(comment(615, 85))))
    val items = GiteaTimelineItem.build(events, listOf(review(85, "COMMENT")), threads, mapOf("58298823f3" to "probe"))

    assertEquals(listOf(614L, 616L, 618L, 619L, 620L, 702L, 705L, 707L), items.map { it.id }, "label pair cancels, unknown type skipped")
    assertEquals(EventKind.Pushed(false, listOf(GiteaTimelineItem.CommitRef("58298823f3", "probe"))), (items[0] as GiteaTimelineItem.Event).kind)
    val reviews = items.filterIsInstance<GiteaTimelineItem.Review>()
    assertEquals(listOf(1, 0, 0), reviews.map { it.threads.size }, "threads at the review's first entry")
    assertEquals(listOf(false, true, false), reviews.map { it.isBare })
    assertEquals("review body", reviews[2].body)
    assertEquals(EventKind.Milestone("v1.0", null), (items[6] as GiteaTimelineItem.Event).kind)
  }

  @Test
  fun `label and review request changes of one user within a minute are combined`() {
    val events = listOf(
      label(1, "2026-10-21T10:00:00Z", 1, added = true),
      label(2, "2026-10-21T10:00:30Z", 2, added = true),
      label(3, "2026-10-21T10:01:20Z", 3, added = false), // 50 s after the previous one: still combined
      label(4, "2026-10-21T10:01:25Z", 4, added = true, user = "intellij-reviewer"),
      label(5, "2026-10-21T10:05:00Z", 2, added = false, user = "intellij-reviewer"),
      event(6, "review_request", "2026-10-21T10:06:00Z", extra = ""","assignee":{"id":4,"login":"intellij-reviewer"}"""),
      event(7, "review_request", "2026-10-21T10:06:01Z", extra = ""","assignee_team":{"id":2,"name":"core"}"""),
    )
    val kinds = GiteaTimelineItem.build(events, emptyList(), emptyList(), emptyMap()).map { (it as GiteaTimelineItem.Event).kind }
    assertEquals(4, kinds.size)
    val first = kinds[0] as EventKind.Labels
    assertEquals(listOf(1L, 2L), first.added.map { it.id })
    assertEquals(listOf(3L), first.removed.map { it.id })
    assertEquals(listOf(4L), (kinds[1] as EventKind.Labels).added.map { it.id }, "other user")
    assertEquals(listOf(2L), (kinds[2] as EventKind.Labels).removed.map { it.id }, "more than a minute later")
    assertEquals(EventKind.ReviewRequests(listOf("intellij-reviewer", "core"), emptyList()), kinds[3])
  }

  @Test
  fun `force push and pending reviews`() {
    val events = listOf(
      event(1, "pull_push", "2026-10-21T10:00:00Z", extra = ""","body":"{\"is_force_push\":true,\"commit_ids\":[\"aaa\",\"bbb\"]}""""),
      event(2, "review", "2026-10-21T10:00:01Z", extra = ""","review_id":9"""),
    )
    val items = GiteaTimelineItem.build(events, listOf(review(9, "PENDING")), emptyList(), emptyMap())
    assertEquals(1, items.size, "pending review not shown")
    assertTrue((items.single() as GiteaTimelineItem.Event).kind.let { it is EventKind.Pushed && it.force })
    val html = GiteaTimelineTexts.eventHtml(items.single() as GiteaTimelineItem.Event)
    assertTrue("aaa" in html && "bbb" in html, html)
  }

  @Test
  fun `event text is escaped`() {
    val item = GiteaTimelineItem.build(listOf(event(1, "change_title", "2026-10-21T10:00:00Z",
                                                    extra = ""","old_title":"<b>x</b>","new_title":"a & b"""")), emptyList(), emptyList(), emptyMap())
      .single() as GiteaTimelineItem.Event
    val html = GiteaTimelineTexts.eventHtml(item)
    assertFalse("<b>x</b>" in html, html)
    assertTrue("&lt;b&gt;x&lt;/b&gt;" in html && "a &amp; b" in html, html)
  }

  @Test
  fun `event lines of one bar are separate paragraphs, a single line stays as it is`() {
    val label = "added label <span style=\"color: #000000\">&nbsp;enhancement&nbsp;</span>"
    val milestone = "added this to the <b>1.0</b> milestone"
    assertEquals("<p>$label</p><p>$milestone</p><p>self-assigned this</p>",
                 GiteaTimelineTexts.eventLinesHtml(listOf(label, milestone, "self-assigned this")))
    assertEquals(milestone, GiteaTimelineTexts.eventLinesHtml(listOf(milestone)))
  }

  @Test
  fun `diff hunk anchor is the commented line with three lines of context`() {
    val hunk = "@@ -0,0 +1,6 @@\n+line1\n+line2\n+line3\n+line4\n+line5"
    val threads = GiteaReviewThread.build(listOf(review(85, "COMMENT")), mapOf(85L to listOf(comment(615, 85, hunk))))
    val (patchHunk, anchor) = GiteaPullRequestTimelineComponentFactory.hunkWithAnchor(threads.single())!!
    assertEquals(listOf("line2", "line3", "line4", "line5"), patchHunk.lines.map { it.text })
    assertEquals(3, anchor.start)
    assertEquals(4, anchor.end)
    assertNull(GiteaPullRequestTimelineComponentFactory.hunkWithAnchor(threads.single().copy(diffHunk = null)))
  }

  @Test
  fun `events of one user within two minutes form one entry`() {
    val events = listOf(
      event(1, "close", "2026-10-21T10:00:00Z"),
      event(2, "reopen", "2026-10-21T10:01:00Z"),
      event(3, "change_title", "2026-10-21T10:02:30Z", extra = ""","old_title":"a","new_title":"b""""),
      event(4, "close", "2026-10-21T10:02:40Z", user = "intellij-reviewer"),
      event(5, "comment", "2026-10-21T10:02:50Z", user = "intellij-reviewer", extra = ""","body":"x""""),
      event(6, "reopen", "2026-10-21T10:03:00Z", user = "intellij-reviewer"),
    )
    val groups = GiteaPullRequestTimelineComponentFactory.group(GiteaTimelineItem.build(events, emptyList(), emptyList(), emptyMap()))
    assertEquals(listOf(listOf(1L, 2L, 3L), listOf(4L), listOf(5L), listOf(6L)), groups.map { group -> group.map { it.id } })
  }

  @Test
  fun `commit links`() {
    val item = GiteaTimelineItem.build(listOf(event(1, "pull_push", "2026-10-21T10:00:00Z",
                                                    extra = ""","body":"{\"commit_ids\":[\"0123456789abc\"]}"""")), emptyList(), emptyList(),
                                       mapOf("0123456789abc" to "fix <it>")).single() as GiteaTimelineItem.Event
    val html = GiteaTimelineTexts.eventHtml(item) { "https://gitea/c/$it" }
    assertTrue("<a href=\"https://gitea/c/0123456789abc\">0123456</a>" in html, html)
    assertTrue("fix &lt;it&gt;" in html, html)
  }
}
