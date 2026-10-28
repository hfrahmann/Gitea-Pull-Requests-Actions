package de.hendrikfrahmann.giteaplugin.pullrequest.data

import de.hendrikfrahmann.giteaplugin.api.dto.GiteaIssueDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import java.util.Date

enum class GiteaPullRequestState { OPEN, CLOSED, MERGED }

/**
 * A pull request as shown in the list. Built from `/pulls` (all fields) or from the issue endpoints, which have no
 * reviewers and no mergeability – then [reviewers] and [mergeable] are `null`.
 */
data class GiteaPullRequestShort(
  val number: Long,
  val title: String,
  val author: GiteaUserDTO?,
  val createdAt: Date?,
  val updatedAt: Date?,
  val state: GiteaPullRequestState,
  val isDraft: Boolean,
  val labels: List<GiteaLabelDTO>,
  val milestone: String?,
  val assignees: List<GiteaUserDTO>,
  val reviewers: List<GiteaUserDTO>?,
  val mergeable: Boolean?,
  val comments: Long,
  /** `owner/name`, only known for search results. */
  val repositoryFullName: String? = null,
) {
  companion object {
    fun from(pr: GiteaPullRequestDTO): GiteaPullRequestShort = GiteaPullRequestShort(
      number = pr.number,
      title = pr.title,
      author = pr.user,
      createdAt = pr.createdAt,
      updatedAt = pr.updatedAt,
      state = state(pr.state, pr.merged),
      isDraft = pr.draft,
      labels = pr.labels,
      milestone = pr.milestone?.title,
      assignees = pr.assignees.ifEmpty { listOfNotNull(pr.assignee) },
      reviewers = pr.requestedReviewers,
      // Gitea reports closed/merged PRs and every draft as not mergeable
      mergeable = pr.mergeable.takeIf { pr.state == "open" && !pr.draft },
      comments = pr.comments,
    )

    fun from(issue: GiteaIssueDTO): GiteaPullRequestShort = GiteaPullRequestShort(
      number = issue.number,
      title = issue.title,
      author = issue.user,
      createdAt = issue.createdAt,
      updatedAt = issue.updatedAt,
      state = state(issue.state, issue.pullRequest?.merged == true),
      isDraft = issue.pullRequest?.draft == true,
      labels = issue.labels,
      milestone = issue.milestone?.title,
      assignees = issue.assignees.ifEmpty { listOfNotNull(issue.assignee) },
      reviewers = null,
      mergeable = null,
      comments = issue.comments,
      repositoryFullName = issue.repository?.fullName,
    )

    private fun state(state: String, merged: Boolean): GiteaPullRequestState = when {
      merged -> GiteaPullRequestState.MERGED
      state == "open" -> GiteaPullRequestState.OPEN
      else -> GiteaPullRequestState.CLOSED
    }
  }
}
