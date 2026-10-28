package de.hendrikfrahmann.giteaplugin.pullrequest.data

import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaTimelineEventDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaPushData
import java.util.Date

private val LOG = logger<GiteaTimelineItem>()

/** One entry of the PR conversation, in web UI order. [id] is the timeline comment id (stable across reloads). */
sealed interface GiteaTimelineItem {
  val id: Long
  val actor: GiteaUserDTO?
  val date: Date?

  data class Comment(
    override val id: Long,
    override val actor: GiteaUserDTO?,
    override val date: Date?,
    val body: String,
    val updatedAt: Date?,
  ) : GiteaTimelineItem

  /** A submitted review; [threads] are its inline threads (only on the first timeline entry of the review). */
  data class Review(
    override val id: Long,
    override val actor: GiteaUserDTO?,
    override val date: Date?,
    val reviewId: Long,
    /** `APPROVED`, `REQUEST_CHANGES`, `COMMENT`; `null` if the review is gone. */
    val state: String?,
    val body: String,
    val threads: List<GiteaReviewThread>,
    val dismissed: Boolean,
  ) : GiteaTimelineItem {
    /** Nothing to show but the verdict ("approved", "reviewed"): rendered like an event. */
    val isBare: Boolean get() = body.isBlank() && threads.isEmpty()
  }

  data class Event(
    override val id: Long,
    override val actor: GiteaUserDTO?,
    override val date: Date?,
    val kind: EventKind,
  ) : GiteaTimelineItem

  sealed interface EventKind {
    data class Pushed(val force: Boolean, val commits: List<CommitRef>) : EventKind
    data class Labels(val added: List<GiteaLabelDTO>, val removed: List<GiteaLabelDTO>) : EventKind
    /** Reviewers are user logins or team names. */
    data class ReviewRequests(val added: List<String>, val removed: List<String>) : EventKind
    data class Assignee(val user: GiteaUserDTO?, val removed: Boolean) : EventKind
    data class Title(val old: String, val new: String) : EventKind
    data class Milestone(val added: String?, val removed: String?) : EventKind
    data class BranchDeleted(val ref: String) : EventKind
    data class TargetBranchChanged(val old: String, val new: String) : EventKind
    data class ReviewDismissed(val reviewer: GiteaUserDTO?, val reason: String) : EventKind
    data object Closed : EventKind
    data object Reopened : EventKind
    data object Merged : EventKind
  }

  data class CommitRef(val sha: String, val title: String?)

  companion object {
    /** Gitea's web UI merges label / review request changes of one user made within this many seconds. */
    private const val COMBINE_WINDOW_MS = 60_000L

    /**
     * Timeline [events] → items, like Gitea's web UI: label and review request changes of one user in a row are
     * combined (add + remove of the same label cancel out), reviews get their inline threads from [reviews] /
     * [threads] (Gitea lists a review once per submission, the threads are shown at its first entry).
     * [commitTitles] maps commit sha → subject for push events. Unknown event types are skipped.
     */
    fun build(events: List<GiteaTimelineEventDTO>, reviews: List<GiteaReviewDTO>, threads: List<GiteaReviewThread>,
              commitTitles: Map<String, String>): List<GiteaTimelineItem> {
      val reviewsById = reviews.associateBy { it.id }
      val threadsByReview = threads.filter { !it.isPending }.groupBy { it.reviewId }
      val reviewsShown = mutableSetOf<Long>()
      val items = mutableListOf<GiteaTimelineItem>()
      var combining: Combining? = null

      fun flush() {
        combining?.toItem()?.let(items::add)
        combining = null
      }

      for (event in events) {
        val combinable = Combining.keyOf(event)
        if (combinable != null) {
          val current = combining
          if (current != null && current.accepts(event, combinable)) {
            current.add(event)
          }
          else {
            flush()
            combining = Combining(event, combinable).also { it.add(event) }
          }
          continue
        }
        flush()
        toItem(event, reviewsById, threadsByReview, reviewsShown, commitTitles)?.let(items::add)
      }
      flush()
      return items
    }

    private fun toItem(event: GiteaTimelineEventDTO, reviewsById: Map<Long, GiteaReviewDTO>, threadsByReview: Map<Long, List<GiteaReviewThread>>,
                       reviewsShown: MutableSet<Long>, commitTitles: Map<String, String>): GiteaTimelineItem? {
      fun event(kind: EventKind) = Event(event.id, event.user, event.createdAt, kind)
      return when (event.type) {
        "comment" -> Comment(event.id, event.user, event.createdAt, event.body.orEmpty(), event.updatedAt)
        "review" -> {
          val review = reviewsById[event.reviewId]
          if (review?.state == "PENDING" || review?.state == "REQUEST_REVIEW") {
            return null
          }
          // Gitea lists a review once per submission; its threads are shown at the first entry only
          val isFirstEntry = reviewsShown.add(event.reviewId)
          val reviewThreads = threadsByReview[event.reviewId].orEmpty().takeIf { isFirstEntry }.orEmpty()
          Review(event.id, event.user ?: review?.user, event.createdAt, event.reviewId, review?.state, event.body.orEmpty(), reviewThreads,
                 review?.dismissed == true)
        }
        "pull_push" -> {
          val push = GiteaPushData.parse(event.body) ?: return null
          event(EventKind.Pushed(push.isForcePush, push.commitIds.map { CommitRef(it, commitTitles[it]) }))
        }
        "assignees" -> event(EventKind.Assignee(event.assignee, event.removedAssignee))
        "change_title" -> event(EventKind.Title(event.oldTitle.orEmpty(), event.newTitle.orEmpty()))
        "milestone" -> event(EventKind.Milestone(event.milestone?.title, event.oldMilestone?.title))
        "delete_branch" -> event(EventKind.BranchDeleted(event.oldRef.orEmpty()))
        "change_target_branch" -> event(EventKind.TargetBranchChanged(event.oldRef.orEmpty(), event.newRef.orEmpty()))
        "dismiss_review" -> event(EventKind.ReviewDismissed(event.assignee, event.body.orEmpty()))
        "close" -> event(EventKind.Closed)
        "reopen" -> event(EventKind.Reopened)
        "merge_pull" -> event(EventKind.Merged)
        else -> {
          LOG.debug("Timeline event type ${event.type} is not shown")
          null
        }
      }
    }

    /** Consecutive `label` or `review_request` events of one user (see [COMBINE_WINDOW_MS]). */
    private class Combining(private val first: GiteaTimelineEventDTO, private val type: String) {
      private var lastTime = first.createdAt?.time
      // insertion order = first change; value true = added, false = removed; a reverted change is dropped
      private val changes = linkedMapOf<String, Pair<Any, Boolean>>()

      fun accepts(event: GiteaTimelineEventDTO, type: String): Boolean {
        val time = event.createdAt?.time ?: return false
        val previousTime = lastTime ?: return false
        return type == this.type && event.user?.login == first.user?.login && time - previousTime < COMBINE_WINDOW_MS
      }

      fun add(event: GiteaTimelineEventDTO) {
        lastTime = event.createdAt?.time ?: lastTime
        val (key, value, added) = when (type) {
          "label" -> {
            val label = event.label ?: return
            Triple("label:${label.id}", label, event.body == "1")
          }
          else -> {
            val name = event.assignee?.login ?: event.assigneeTeam?.name ?: return
            Triple("reviewer:$name", name, !event.removedAssignee)
          }
        }
        val previous = changes[key]
        if (previous != null && previous.second != added) {
          changes.remove(key)
        }
        else {
          changes[key] = value to added
        }
      }

      fun toItem(): GiteaTimelineItem? {
        if (changes.isEmpty()) {
          return null
        }
        val added = changes.values.filter { it.second }.map { it.first }
        val removed = changes.values.filter { !it.second }.map { it.first }
        val kind = when (type) {
          "label" -> EventKind.Labels(added.filterIsInstance<GiteaLabelDTO>(), removed.filterIsInstance<GiteaLabelDTO>())
          else -> EventKind.ReviewRequests(added.filterIsInstance<String>(), removed.filterIsInstance<String>())
        }
        return Event(first.id, first.user, first.createdAt, kind)
      }

      companion object {
        fun keyOf(event: GiteaTimelineEventDTO): String? = event.type.takeIf { it == "label" || it == "review_request" }
      }
    }
  }
}
