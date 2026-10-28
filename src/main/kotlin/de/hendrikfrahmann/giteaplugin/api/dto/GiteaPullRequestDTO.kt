package de.hendrikfrahmann.giteaplugin.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Date

data class GiteaPullRequestDTO(
  val id: Long,
  /** The PR index within the repository (`#1`). */
  val number: Long,
  val title: String,
  val body: String? = null,
  /** `open` / `closed` */
  val state: String,
  val draft: Boolean = false,
  @param:JsonProperty("is_locked") val isLocked: Boolean = false,
  val user: GiteaUserDTO? = null,
  val assignee: GiteaUserDTO? = null,
  val assignees: List<GiteaUserDTO> = emptyList(),
  val requestedReviewers: List<GiteaUserDTO> = emptyList(),
  val requestedReviewersTeams: List<GiteaTeamDTO> = emptyList(),
  val labels: List<GiteaLabelDTO> = emptyList(),
  val milestone: GiteaMilestoneDTO? = null,
  val base: GiteaPRBranchInfoDTO? = null,
  val head: GiteaPRBranchInfoDTO? = null,
  val mergeBase: String? = null,
  /**
   * `null` while Gitea is still checking. Verified on 28.1: also `false` for every draft (WIP) and closed PR, whatever
   * the conflicts – use [hasConflicts].
   */
  val mergeable: Boolean? = null,
  val merged: Boolean = false,
  val mergedAt: Date? = null,
  val mergedBy: GiteaUserDTO? = null,
  val mergeCommitSha: String? = null,
  val allowMaintainerEdit: Boolean = false,
  val comments: Long = 0,
  val reviewComments: Long = 0,
  val additions: Long = 0,
  val deletions: Long = 0,
  val changedFiles: Long = 0,
  /** ⚠ `ROOT_URL`-based, rebase before use. */
  val htmlUrl: String? = null,
  val diffUrl: String? = null,
  val patchUrl: String? = null,
  val createdAt: Date? = null,
  val updatedAt: Date? = null,
  val closedAt: Date? = null,
  val dueDate: Date? = null,
)

/**
 * Conflicts with the base. Gitea's `mergeable` folds the draft state in (`false` for every WIP PR), so for drafts the
 * conflicts are unknown and reported as none.
 */
val GiteaPullRequestDTO.hasConflicts: Boolean get() = state == "open" && !draft && mergeable == false

data class GiteaPRBranchInfoDTO(
  /** Branch name. */
  val label: String? = null,
  /** Branch name, or `refs/pull/N/head` when the head repo is gone. */
  val ref: String,
  val sha: String,
  val repoId: Long = 0,
  val repo: GiteaRepositoryDTO? = null,
)

/** `GET …/pulls/{index}/files` */
data class GiteaChangedFileDTO(
  val filename: String,
  val previousFilename: String? = null,
  /** `added`, `deleted`, `modified`, `renamed`, `copied`, `changed`, `unchanged` */
  val status: String,
  val additions: Long = 0,
  val deletions: Long = 0,
  val changes: Long = 0,
  val htmlUrl: String? = null,
  val contentsUrl: String? = null,
  val rawUrl: String? = null,
)
