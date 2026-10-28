package de.hendrikfrahmann.giteaplugin.api.dto

import java.util.Date

/** Issue/PR conversation comment, `GET /repos/{o}/{r}/issues/{index}/comments` */
data class GiteaCommentDTO(
  val id: Long,
  val body: String = "",
  val user: GiteaUserDTO? = null,
  /** For comments migrated from another forge. */
  val originalAuthor: String? = null,
  val htmlUrl: String? = null,
  val assets: List<GiteaAttachmentDTO> = emptyList(),
  val createdAt: Date? = null,
  val updatedAt: Date? = null,
)

data class GiteaAttachmentDTO(
  val id: Long,
  val name: String,
  val size: Long = 0,
  val uuid: String? = null,
  val browserDownloadUrl: String? = null,
  val createdAt: Date? = null,
)

/** `GET /repos/{o}/{r}/issues/{index}/timeline`. Only fields relevant for PRs; [type] decides which are set. */
data class GiteaTimelineEventDTO(
  val id: Long,
  /** `comment`, `review`, `review_request`, `pull_push`, `label`, `milestone`, `assignees`, `change_title`, `close`, `reopen`, `merge_pull`, … */
  val type: String,
  /** For `pull_push` a JSON string `{"is_force_push":…,"commit_ids":[…]}`. */
  val body: String? = null,
  val user: GiteaUserDTO? = null,
  val createdAt: Date? = null,
  val updatedAt: Date? = null,
  val htmlUrl: String? = null,
  val reviewId: Long = 0,
  val assignee: GiteaUserDTO? = null,
  val assigneeTeam: GiteaTeamDTO? = null,
  val removedAssignee: Boolean = false,
  val label: GiteaLabelDTO? = null,
  val milestone: GiteaMilestoneDTO? = null,
  val oldMilestone: GiteaMilestoneDTO? = null,
  val oldTitle: String? = null,
  val newTitle: String? = null,
  val oldRef: String? = null,
  val newRef: String? = null,
  val refAction: String? = null,
  val refCommitSha: String? = null,
  val resolveDoer: GiteaUserDTO? = null,
)

/** `GET …/issues/comments/{id}/reactions` */
data class GiteaReactionDTO(
  /** `+1`, `-1`, `laugh`, `hooray`, `confused`, `heart`, `rocket`, `eyes` */
  val content: String,
  val user: GiteaUserDTO? = null,
  val createdAt: Date? = null,
)
