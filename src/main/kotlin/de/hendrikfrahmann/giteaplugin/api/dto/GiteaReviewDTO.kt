package de.hendrikfrahmann.giteaplugin.api.dto

import java.util.Date

/** `GET …/pulls/{index}/reviews` */
data class GiteaReviewDTO(
  val id: Long,
  val user: GiteaUserDTO? = null,
  val team: GiteaTeamDTO? = null,
  /** `APPROVED`, `PENDING`, `COMMENT`, `REQUEST_CHANGES`, `REQUEST_REVIEW` */
  val state: String,
  val body: String? = null,
  val commitId: String? = null,
  val stale: Boolean = false,
  val official: Boolean = false,
  val dismissed: Boolean = false,
  val commentsCount: Long = 0,
  val htmlUrl: String? = null,
  val submittedAt: Date? = null,
  val updatedAt: Date? = null,
)

/** `GET …/pulls/{index}/reviews/{id}/comments`. `position` = line in the new file, `original_position` = line in the old file. */
data class GiteaReviewCommentDTO(
  val id: Long,
  val body: String = "",
  val user: GiteaUserDTO? = null,
  /** Set on the thread's root comment only. */
  val resolver: GiteaUserDTO? = null,
  val pullRequestReviewId: Long = 0,
  val path: String,
  val position: Int = 0,
  val originalPosition: Int = 0,
  val commitId: String? = null,
  val originalCommitId: String? = null,
  val diffHunk: String? = null,
  val htmlUrl: String? = null,
  val createdAt: Date? = null,
  val updatedAt: Date? = null,
)
