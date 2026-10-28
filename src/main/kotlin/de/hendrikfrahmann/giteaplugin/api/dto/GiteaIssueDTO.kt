package de.hendrikfrahmann.giteaplugin.api.dto

import java.util.Date

/**
 * An issue or – with [pullRequest] set – a pull request as returned by the issue endpoints
 * (`/repos/{o}/{r}/issues?type=pulls`, `/repos/issues/search`). Has no branches, reviewers or mergeability.
 */
data class GiteaIssueDTO(
  val id: Long,
  val number: Long,
  val title: String,
  val body: String? = null,
  val user: GiteaUserDTO? = null,
  val labels: List<GiteaLabelDTO> = emptyList(),
  val milestone: GiteaMilestoneDTO? = null,
  val assignee: GiteaUserDTO? = null,
  val assignees: List<GiteaUserDTO> = emptyList(),
  /** `open` / `closed` */
  val state: String,
  val comments: Long = 0,
  val createdAt: Date? = null,
  val updatedAt: Date? = null,
  val closedAt: Date? = null,
  val pullRequest: GiteaPullRequestMetaDTO? = null,
  /** Set by `/repos/issues/search`, which spans repositories. */
  val repository: GiteaIssueRepositoryDTO? = null,
)

data class GiteaPullRequestMetaDTO(
  val merged: Boolean = false,
  val mergedAt: Date? = null,
  val draft: Boolean = false,
)

data class GiteaIssueRepositoryDTO(
  val id: Long = 0,
  val name: String? = null,
  val owner: String? = null,
  /** `owner/name` */
  val fullName: String? = null,
)
