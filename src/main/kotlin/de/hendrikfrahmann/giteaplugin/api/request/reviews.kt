package de.hendrikfrahmann.giteaplugin.api.request

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCommentDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewCommentDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.api.getJsonList
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.loadJsonValue
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.sendIgnoringBody

/*
 * Review requests. Semantics verified on 28.1:
 * - one pending review per user and PR; creating another PENDING review appends to it
 * - positions are 1-based lines of the diff merge base → commit_id (new side: new_position, old side: old_position)
 * - review comments are edited/deleted through the issue comment endpoints
 */

/** `GET …/pulls/{index}/reviews/{id}/comments` – including replies (they are added to the original review). */
suspend fun GiteaApi.loadReviewComments(repository: GiteaRepositoryPath, number: Long, reviewId: Long): List<GiteaReviewCommentDTO> =
  getJsonList<GiteaReviewCommentDTO>(server.repoApiUri(repository, "pulls", number.toString(), "reviews", reviewId.toString(), "comments")).body

/** Review event / state names of the API. */
enum class GiteaReviewEvent { APPROVED, REQUEST_CHANGES, COMMENT, PENDING }

/** A new inline comment; exactly one of [newPosition] (added/context line) and [oldPosition] (removed line) is set. */
data class GiteaNewReviewComment(
  val path: String,
  val body: String,
  val newPosition: Int? = null,
  val oldPosition: Int? = null,
)

data class GiteaCreateReviewRequest(
  val event: GiteaReviewEvent,
  val body: String = "",
  val commitId: String? = null,
  val comments: List<GiteaNewReviewComment> = emptyList(),
)

/**
 * `POST …/pulls/{index}/reviews`. With [GiteaReviewEvent.PENDING] the comments go to the user's pending review
 * (created if needed); with [GiteaReviewEvent.COMMENT] and one comment it is a single comment. Every call adds a
 * "reviewed" entry to the PR timeline, also with [GiteaReviewEvent.PENDING].
 */
suspend fun GiteaApi.createReview(repository: GiteaRepositoryPath, number: Long, request: GiteaCreateReviewRequest): GiteaReviewDTO =
  loadJsonValue<GiteaReviewDTO>(request(server.repoApiUri(repository, "pulls", number.toString(), "reviews"))
                                  .jsonBody("POST", request).build()).body

private data class GiteaSubmitReviewRequest(val event: GiteaReviewEvent, val body: String)

/** `POST …/pulls/{index}/reviews/{id}` – submits the pending review. */
suspend fun GiteaApi.submitReview(repository: GiteaRepositoryPath, number: Long, reviewId: Long, event: GiteaReviewEvent, body: String)
  : GiteaReviewDTO =
  loadJsonValue<GiteaReviewDTO>(request(server.repoApiUri(repository, "pulls", number.toString(), "reviews", reviewId.toString()))
                                  .jsonBody("POST", GiteaSubmitReviewRequest(event, body)).build()).body

/** `DELETE …/pulls/{index}/reviews/{id}` – discards a pending review with its comments. */
suspend fun GiteaApi.deleteReview(repository: GiteaRepositoryPath, number: Long, reviewId: Long) {
  sendIgnoringBody(request(server.repoApiUri(repository, "pulls", number.toString(), "reviews", reviewId.toString())).DELETE().build())
}

private data class GiteaCommentBody(val body: String)

/** `POST …/pulls/{index}/comments/{id}/replies` (28+) – the reply is added to the review of [commentId]. */
suspend fun GiteaApi.replyToReviewComment(repository: GiteaRepositoryPath, number: Long, commentId: Long, body: String) {
  sendIgnoringBody(request(server.repoApiUri(repository, "pulls", number.toString(), "comments", commentId.toString(), "replies"))
                     .jsonBody("POST", GiteaCommentBody(body)).build())
}

/** `PATCH /repos/{o}/{r}/issues/comments/{id}` – works for review comments too. */
suspend fun GiteaApi.editComment(repository: GiteaRepositoryPath, commentId: Long, body: String): GiteaCommentDTO =
  loadJsonValue<GiteaCommentDTO>(request(server.repoApiUri(repository, "issues", "comments", commentId.toString()))
                                   .jsonBody("PATCH", GiteaCommentBody(body)).build()).body

/** `DELETE /repos/{o}/{r}/issues/comments/{id}` – works for review comments too. */
suspend fun GiteaApi.deleteComment(repository: GiteaRepositoryPath, commentId: Long) {
  sendIgnoringBody(request(server.repoApiUri(repository, "issues", "comments", commentId.toString())).DELETE().build())
}

/** `POST /repos/{o}/{r}/pulls/comments/{id}/resolve|unresolve` – on the thread's root comment. */
suspend fun GiteaApi.setReviewThreadResolved(repository: GiteaRepositoryPath, rootCommentId: Long, resolved: Boolean) {
  val action = when {
    resolved -> "resolve"
    else -> "unresolve"
  }
  sendIgnoringBody(request(server.repoApiUri(repository, "pulls", "comments", rootCommentId.toString(), action))
                     .jsonBody("POST", emptyMap<String, Any>()).build())
}
