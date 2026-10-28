package de.hendrikfrahmann.giteaplugin.pullrequest.data

import com.intellij.diff.util.Side
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewCommentDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import git4idea.changes.GitTextFilePatchWithHistory
import java.util.Date

data class GiteaReviewComment(
  val id: Long,
  val author: GiteaUserDTO?,
  val body: String,
  val createdAt: Date?,
  val updatedAt: Date?,
  val reviewId: Long,
  /** Part of the current user's pending review (only visible to them). */
  val isPending: Boolean,
)

/**
 * An inline discussion: Gitea has no thread id and no `in_reply_to`; replies are added to the review of the comment
 * they answer, at the same path/line/commit. So a thread = the comments of one review at one (path, side, line, commit),
 * oldest first; the root carries the `resolver`.
 */
data class GiteaReviewThread(
  val path: String,
  /** [Side.LEFT] = removed line (line in the merge base), [Side.RIGHT] = added or context line (line at [commitSha]). */
  val side: Side,
  /** 1-based, as in the API. */
  val line: Int,
  val commitSha: String,
  val reviewId: Long,
  val comments: List<GiteaReviewComment>,
  val resolver: GiteaUserDTO?,
  /** Diff hunk up to the commented line (as the API sends it), for the timeline. */
  val diffHunk: String? = null,
  /** The PR head the review was made on – [line] counts in that version (Gitea's [commitSha] is only the line's blame commit). */
  val reviewCommitSha: String? = null,
) {
  val root: GiteaReviewComment get() = comments.first()
  val id: Long get() = root.id
  val isResolved: Boolean get() = resolver != null
  val isPending: Boolean get() = root.isPending

  /**
   * Where the thread is in the current diff (0-based line), or `null` if it can't be placed – the commit or file is no
   * longer part of the PR (force push, rebase): such threads are outdated and must not be attached to some other line.
   *
   * A new-side [line] counts in the PR head of the review ([reviewCommitSha]). Gitea stores the line's blame commit as
   * [commitSha] – for an unchanged line an older commit of the base branch, which the PR's file history doesn't know
   * (verified on 28.1). So the review's head is tried first, then [commitSha], then the current [headSha].
   */
  fun mapToDiff(patch: GitTextFilePatchWithHistory, mergeBaseSha: String, headSha: String? = null): Pair<Side, Int>? {
    val candidates = when {
      side == Side.LEFT -> listOf(mergeBaseSha)
      else -> listOfNotNull(reviewCommitSha, commitSha, headSha).distinct()
    }
    return candidates.firstNotNullOfOrNull { fromCommit ->
      try {
        patch.mapLine(fromCommit, line - 1, side)
      }
      catch (_: Exception) {
        null
      }
    }
  }

  companion object {
    /**
     * Threads of all [reviews] (without `REQUEST_REVIEW` placeholders); [commentsByReview] are the reviews' comments.
     * File-level comments (no position) are skipped – Gitea's API can't create them either.
     */
    fun build(reviews: List<GiteaReviewDTO>, commentsByReview: Map<Long, List<GiteaReviewCommentDTO>>): List<GiteaReviewThread> =
      reviews.filter { it.state != "REQUEST_REVIEW" }.flatMap { review ->
        val pending = review.state == "PENDING"
        commentsByReview[review.id].orEmpty()
          .mapNotNull { comment -> comment.location()?.let { it to comment } }
          .groupBy(keySelector = { it.first }, valueTransform = { it.second })
          .map { (location, comments) ->
            val sorted = comments.sortedWith(compareBy<GiteaReviewCommentDTO> { it.createdAt }.thenBy { it.id })
            GiteaReviewThread(
              path = location.path, side = location.side, line = location.line, commitSha = location.commit, reviewId = review.id,
              comments = sorted.map { GiteaReviewComment(it.id, it.user, it.body, it.createdAt, it.updatedAt, review.id, pending) },
              resolver = sorted.first().resolver,
              diffHunk = sorted.first().diffHunk,
              reviewCommitSha = review.commitId,
            )
          }
      }.sortedWith(compareBy({ it.path }, { it.line }, { it.root.createdAt }))

    private data class Location(val path: String, val side: Side, val line: Int, val commit: String)

    private fun GiteaReviewCommentDTO.location(): Location? {
      val commit = commitId ?: originalCommitId ?: return null
      return when {
        position > 0 -> Location(path, Side.RIGHT, position, commit)
        originalPosition > 0 -> Location(path, Side.LEFT, originalPosition, commit)
        else -> null
      }
    }
  }
}
