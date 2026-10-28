package de.hendrikfrahmann.giteaplugin.api.request

import com.fasterxml.jackson.annotation.JsonProperty
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaPagination
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaResponse
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCombinedStatusDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCommitDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaIssueDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.api.getJson
import de.hendrikfrahmann.giteaplugin.api.getJsonList
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.loadJsonValue
import de.hendrikfrahmann.giteaplugin.api.loadText
import de.hendrikfrahmann.giteaplugin.api.nullOnHttpStatus
import de.hendrikfrahmann.giteaplugin.api.page
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.sendIgnoringBody
import de.hendrikfrahmann.giteaplugin.api.withQuery
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.net.http.HttpRequest

/**
 * `GET /repos/{owner}/{repo}/commits/{sha}/pull` – the merged PR whose `merge_commit_sha` is [sha]; `null` on 404.
 *
 * Verified on 28.1: only the merge commit matches (merge style "merge": the merge commit, "squash": the squashed
 * commit, "rebase": the last rebased commit). The PR's own commits, other rebased commits and open PRs give 404.
 */
suspend fun GiteaApi.findPullRequestByMergeCommit(repository: GiteaRepositoryPath, sha: String): GiteaPullRequestDTO? =
  nullOnHttpStatus { getJson(server.repoApiUri(repository, "commits", sha, "pull")) }

/** Query of `GET /repos/{o}/{r}/pulls` – supports only these filters. */
data class GiteaPullRequestListQuery(
  /** `open`, `closed`, `all` */
  val state: String = "open",
  val poster: String? = null,
  val labelIds: List<Long> = emptyList(),
  val milestoneId: Long? = null,
  /** Only PRs into this branch. */
  val baseBranch: String? = null,
)

/** `GET /repos/{o}/{r}/pulls` – newest first, with reviewers and mergeability. */
suspend fun GiteaApi.loadPullRequestsPage(repository: GiteaRepositoryPath, query: GiteaPullRequestListQuery, page: Int, limit: Int)
  : GiteaResponse<List<GiteaPullRequestDTO>> =
  getJsonList(server.repoApiUri(repository, "pulls").withQuery {
    "state" eq query.state
    "poster" eq query.poster
    "labels" eq query.labelIds
    "milestone" eq query.milestoneId
    "base_branch" eq query.baseBranch
    page(page, limit)
  })

/**
 * Query of the issue endpoints with `type=pulls`. Label and milestone filters take names; several labels are AND-combined
 * (verified on 28.1, the swagger text says "any of").
 */
data class GiteaPullRequestIssueQuery(
  /** `open`, `closed`, `all` */
  val state: String = "open",
  /** Full-text search (Gitea's issue indexer: whole words, title and body). */
  val text: String? = null,
  val labels: List<String> = emptyList(),
  val milestones: List<String> = emptyList(),
  val createdBy: String? = null,
  val assignedTo: String? = null,
)

/** `GET /repos/{o}/{r}/issues?type=pulls` – also has search, assignee and label names, but no reviewers. */
suspend fun GiteaApi.loadPullRequestIssuesPage(repository: GiteaRepositoryPath, query: GiteaPullRequestIssueQuery, page: Int, limit: Int)
  : GiteaResponse<List<GiteaIssueDTO>> =
  getJsonList(server.repoApiUri(repository, "issues").withQuery {
    "type" eq "pulls"
    "state" eq query.state
    "q" eq query.text
    "labels" eq query.labels.takeIf { it.isNotEmpty() }?.joinToString(",")
    "milestones" eq query.milestones.takeIf { it.isNotEmpty() }?.joinToString(",")
    "created_by" eq query.createdBy
    "assigned_by" eq query.assignedTo
    page(page, limit)
  })

/**
 * `GET /repos/issues/search?type=pulls&review_requested=true&owner=…` – PRs of all repositories of [owner] that wait for
 * the authenticated user's review (pending request, not yet reviewed). Filter by `repository.full_name` afterwards.
 * There is no per-repository variant and no way to ask for another user's review requests.
 */
suspend fun GiteaApi.searchReviewRequestedPullRequestsPage(owner: String, query: GiteaPullRequestIssueQuery, page: Int, limit: Int)
  : GiteaResponse<List<GiteaIssueDTO>> =
  getJsonList(server.apiUri("repos", "issues", "search").withQuery {
    "type" eq "pulls"
    "review_requested" eq true
    "owner" eq owner
    "state" eq query.state
    "q" eq query.text
    "labels" eq query.labels.takeIf { it.isNotEmpty() }?.joinToString(",")
    "milestones" eq query.milestones.takeIf { it.isNotEmpty() }?.joinToString(",")
    "created_by" eq query.createdBy
    page(page, limit)
  })

/** `GET /repos/{o}/{r}/pulls/{index}` */
suspend fun GiteaApi.getPullRequest(repository: GiteaRepositoryPath, number: Long): GiteaPullRequestDTO =
  getJson(server.repoApiUri(repository, "pulls", number.toString()))

/** Body of `POST /repos/{o}/{r}/pulls`; a draft is a title with Gitea's `WIP:` prefix. */
data class GiteaCreatePullRequestRequest(
  val title: String,
  val body: String? = null,
  /** Branch name, `owner:branch` for a branch of another repository (fork). */
  val head: String,
  val base: String,
  val assignees: List<String> = emptyList(),
  val reviewers: List<String> = emptyList(),
  val labels: List<Long> = emptyList(),
  val milestone: Long? = null,
)

/**
 * `POST /repos/{o}/{r}/pulls` – verified on 28.1: reviewers, assignees, labels and milestone are set right away; `409`
 * if an open PR for the same head and base exists, `422` without changes, `404` for an unknown head.
 */
suspend fun GiteaApi.createPullRequest(repository: GiteaRepositoryPath, request: GiteaCreatePullRequestRequest): GiteaPullRequestDTO =
  loadJsonValue<GiteaPullRequestDTO>(request(server.repoApiUri(repository, "pulls")).jsonBody("POST", request).build()).body

/**
 * The open PR from [headBranch] of [headRepository] into [baseBranch] of [repository], or `null`.
 *
 * `GET /pulls/{base}/{owner:head}` alone isn't enough: verified on 28.1 it answers with *a* PR for the branches, also
 * a closed one while another is open. So when it doesn't find an open one, the open PRs into the base are searched.
 */
suspend fun GiteaApi.findOpenPullRequest(repository: GiteaRepositoryPath, baseBranch: String, headRepository: GiteaRepositoryPath,
                                         headBranch: String): GiteaPullRequestDTO? {
  fun GiteaPullRequestDTO.matches() = state == "open" && hasHead(headRepository, headBranch)

  val direct = nullOnHttpStatus {
    getJson<GiteaPullRequestDTO>(server.repoApiUri(repository, "pulls", baseBranch, "${headRepository.owner}:$headBranch"))
  } ?: return null
  if (direct.matches()) {
    return direct
  }
  return GiteaPagination.pagesFlow { page, limit ->
    loadPullRequestsPage(repository, GiteaPullRequestListQuery(baseBranch = baseBranch), page, limit)
  }.map { prs -> prs.firstOrNull { it.matches() } }.firstOrNull { it != null }
}

/**
 * The open PR into any branch of [repository] whose head is [headBranch] of [headRepository] or, failing that, whose head
 * commit is [headSha] (a detached checkout). Scans all open PRs – there is no head filter.
 */
suspend fun GiteaApi.findOpenPullRequestByHead(repository: GiteaRepositoryPath, headRepository: GiteaRepositoryPath, headBranch: String?,
                                               headSha: String?): GiteaPullRequestDTO? {
  if (headBranch == null && headSha == null) {
    return null
  }
  // one pass over the pages: a branch match wins, the first commit match is remembered on the way
  var bySha: GiteaPullRequestDTO? = null
  val byBranch = GiteaPagination.pagesFlow { page, limit ->
    loadPullRequestsPage(repository, GiteaPullRequestListQuery(), page, limit)
  }.map { prs ->
    if (bySha == null && headSha != null) {
      bySha = prs.firstOrNull { it.head?.sha == headSha }
    }
    prs.firstOrNull { it.hasHead(headRepository, headBranch) }
  }.firstOrNull { it != null }
  return byBranch ?: bySha
}

private fun GiteaPullRequestDTO.hasHead(headRepository: GiteaRepositoryPath, headBranch: String?): Boolean =
  head?.ref == headBranch && head?.repo?.fullName.equals(headRepository.toString(), ignoreCase = true)

/** `GET /repos/{o}/{r}/pulls/{index}/commits` – all pages, newest first (as Gitea sends them), without files. */
suspend fun GiteaApi.loadPullRequestCommits(repository: GiteaRepositoryPath, number: Long): List<GiteaCommitDTO> =
  GiteaPagination.loadAllPages { page, limit ->
    getJsonList(server.repoApiUri(repository, "pulls", number.toString(), "commits").withQuery {
      "verification" eq false
      "files" eq false
      page(page, limit)
    })
  }

/** `GET /repos/{o}/{r}/pulls/{index}.diff` – unified diff merge base → head (empty for an empty PR). */
suspend fun GiteaApi.loadPullRequestDiff(repository: GiteaRepositoryPath, number: Long): String =
  loadText(server.repoApiUri(repository, "pulls", "$number.diff"))

/** `GET /repos/{o}/{r}/git/commits/{sha}.diff` – unified diff against the first parent (empty for an empty commit). */
suspend fun GiteaApi.loadCommitDiff(repository: GiteaRepositoryPath, sha: String): String =
  loadText(server.repoApiUri(repository, "git", "commits", "$sha.diff"))

/** `GET /repos/{o}/{r}/pulls/{index}/reviews` – all pages, oldest first; includes `REQUEST_REVIEW` placeholders. */
suspend fun GiteaApi.loadPullRequestReviews(repository: GiteaRepositoryPath, number: Long): List<GiteaReviewDTO> =
  GiteaPagination.loadAllPages { page, limit ->
    getJsonList(server.repoApiUri(repository, "pulls", number.toString(), "reviews").withQuery { page(page, limit) })
  }

/** `GET /repos/{o}/{r}/commits/{ref}/status` – combined CI status; `target_url`s may be relative to the server. */
suspend fun GiteaApi.loadCombinedStatus(repository: GiteaRepositoryPath, ref: String): GiteaCombinedStatusDTO =
  getJson(server.repoApiUri(repository, "commits", ref, "status").withQuery { "limit" eq GiteaPagination.DEFAULT_PAGE_SIZE })

/**
 * `PATCH /repos/{o}/{r}/pulls/{index}` – only the given fields change (`null` = unchanged; empty lists clear).
 * `milestone = 0` does **not** remove the milestone here (verified on 28.1), use [setIssueMilestone] for that.
 */
data class GiteaEditPullRequestRequest(
  val assignees: List<String>? = null,
  val labels: List<Long>? = null,
  val milestone: Long? = null,
  val title: String? = null,
  /** `open` / `closed` */
  val state: String? = null,
)

private data class GiteaIssueMilestoneRequest(val milestone: Long)

/** `PATCH /repos/{o}/{r}/issues/{index}` with only `milestone`; `null` removes it (sent as `0`). Works for PRs too. */
suspend fun GiteaApi.setIssueMilestone(repository: GiteaRepositoryPath, number: Long, milestoneId: Long?) {
  sendIgnoringBody(request(server.repoApiUri(repository, "issues", number.toString()))
                     .jsonBody("PATCH", GiteaIssueMilestoneRequest(milestoneId ?: 0)).build())
}

suspend fun GiteaApi.editPullRequest(repository: GiteaRepositoryPath, number: Long, request: GiteaEditPullRequestRequest): GiteaPullRequestDTO =
  loadJsonValue<GiteaPullRequestDTO>(request(server.repoApiUri(repository, "pulls", number.toString())).jsonBody("PATCH", request).build()).body

private data class GiteaReviewersRequest(val reviewers: List<String>)

/** `POST /repos/{o}/{r}/pulls/{index}/requested_reviewers` */
suspend fun GiteaApi.requestReviewers(repository: GiteaRepositoryPath, number: Long, logins: List<String>) {
  if (logins.isEmpty()) {
    return
  }
  sendIgnoringBody(request(server.repoApiUri(repository, "pulls", number.toString(), "requested_reviewers"))
                     .jsonBody("POST", GiteaReviewersRequest(logins)).build())
}

/** `DELETE /repos/{o}/{r}/pulls/{index}/requested_reviewers` */
suspend fun GiteaApi.removeReviewRequests(repository: GiteaRepositoryPath, number: Long, logins: List<String>) {
  if (logins.isEmpty()) {
    return
  }
  sendIgnoringBody(request(server.repoApiUri(repository, "pulls", number.toString(), "requested_reviewers"))
                     .jsonBody("DELETE", GiteaReviewersRequest(logins)).build())
}

/** Gitea's merge styles (`Do`); which ones a repository allows is in [de.hendrikfrahmann.giteaplugin.api.dto.GiteaRepositoryDTO]. */
enum class GiteaMergeStyle(val value: String) {
  MERGE("merge"), REBASE("rebase"), REBASE_MERGE("rebase-merge"), SQUASH("squash"), FAST_FORWARD_ONLY("fast-forward-only");

  companion object {
    fun of(value: String?): GiteaMergeStyle? = entries.firstOrNull { it.value == value }
  }
}

data class GiteaMergePullRequestRequest(
  @get:JsonProperty("Do") val style: String,
  val mergeTitleField: String? = null,
  val mergeMessageField: String? = null,
  val deleteBranchAfterMerge: Boolean? = null,
  /** Refused with `409 head out of date` if the PR moved on – nothing unseen gets merged. */
  val headCommitId: String? = null,
  val mergeWhenChecksSucceed: Boolean? = null,
)

/**
 * `POST /repos/{o}/{r}/pulls/{index}/merge` – `true` when merged now (`200`), `false` when scheduled to merge once the
 * required checks succeed (`201`). Verified on 28.1: refusals are `405` with a message ("Does not have enough approvals",
 * "Not all required status checks successful", "Work in progress PRs cannot be merged", "Please try again later" while
 * Gitea is still checking), `409` for a wrong `head_commit_id` or an already scheduled auto merge.
 */
suspend fun GiteaApi.mergePullRequest(repository: GiteaRepositoryPath, number: Long, request: GiteaMergePullRequestRequest): Boolean =
  sendIgnoringBody(request(server.repoApiUri(repository, "pulls", number.toString(), "merge")).jsonBody("POST", request).build())
    .statusCode != 201

/** `DELETE /repos/{o}/{r}/pulls/{index}/merge` – cancels a scheduled auto merge (`404` if none is scheduled). */
suspend fun GiteaApi.cancelAutoMerge(repository: GiteaRepositoryPath, number: Long) {
  sendIgnoringBody(request(server.repoApiUri(repository, "pulls", number.toString(), "merge")).DELETE().build())
}

/**
 * `POST /repos/{o}/{r}/pulls/{index}/update?style=merge|rebase` – brings the head branch up to date with the base.
 * Verified on 28.1: a branch that isn't behind gives `500` with an empty message, so only offer it when behind.
 */
suspend fun GiteaApi.updatePullRequestBranch(repository: GiteaRepositoryPath, number: Long, rebase: Boolean) {
  val style = when {
    rebase -> "rebase"
    else -> "merge"
  }
  val uri = server.repoApiUri(repository, "pulls", number.toString(), "update").withQuery { "style" eq style }
  sendIgnoringBody(request(uri).POST(HttpRequest.BodyPublishers.noBody()).build())
}
