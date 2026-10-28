package de.hendrikfrahmann.giteaplugin.pullrequest.data

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaPagination
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaResponse
import de.hendrikfrahmann.giteaplugin.api.request.GiteaPullRequestIssueQuery
import de.hendrikfrahmann.giteaplugin.api.request.GiteaPullRequestListQuery
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestIssuesPage
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestsPage
import de.hendrikfrahmann.giteaplugin.api.request.searchReviewRequestedPullRequestsPage
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue

/**
 * Pages through the PRs of [repository] that match a filter. Gitea has no endpoint that supports every filter, so
 * [Source.choose] picks one and the rest is checked here ([matches]); pages without a match are skipped.
 *
 * | Filter                | `/pulls` | `/issues?type=pulls` | `/repos/issues/search` |
 * |-----------------------|----------|----------------------|------------------------|
 * | text                  | –        | ✔ (title + body)     | ✔                      |
 * | author                | ✔        | ✔                    | ✔                      |
 * | assignee              | –        | ✔                    | –                      |
 * | label / milestone     | ids      | names                | names                  |
 * | review requested from | –        | –                    | only the current user  |
 * | merged vs. closed     | –        | –                    | –                      |
 */
class GiteaPullRequestsLoader(
  private val api: GiteaApi,
  private val repository: GiteaRepositoryPath,
  /** `null` without a login (anonymous connection). */
  private val currentUserLogin: String?,
  private val pageSize: Int = GiteaPagination.DEFAULT_PAGE_SIZE,
) {
  enum class Source { PULLS, ISSUES, REVIEW_REQUESTED_SEARCH;

    companion object {
      fun choose(filter: GiteaPullRequestsFiltersValue, currentUserLogin: String?): Source = when {
        filter.reviewer != null && filter.reviewer.login.equals(currentUserLogin, ignoreCase = true) -> REVIEW_REQUESTED_SEARCH
        // reviewers are only in /pulls items, everything else is checked here
        filter.reviewer != null -> PULLS
        filter.searchQuery != null || filter.assignee != null || filter.label != null || filter.milestone != null -> ISSUES
        else -> PULLS
      }
    }
  }

  fun createPager(filter: GiteaPullRequestsFiltersValue): Pager = Pager(filter, Source.choose(filter, currentUserLogin))

  /** Not thread-safe: call [loadMore] sequentially. */
  inner class Pager internal constructor(private val filter: GiteaPullRequestsFiltersValue, val source: Source) {
    private var nextPage = 1
    private var loadedRaw = 0

    var hasMore: Boolean = true
      private set

    /** The next matching PRs; loads further pages until something matches or nothing is left. Empty only at the end. */
    suspend fun loadMore(): List<GiteaPullRequestShort> {
      while (hasMore) {
        val response = loadPage(nextPage)
        val raw = response.body
        loadedRaw += raw.size
        hasMore = raw.isNotEmpty() && GiteaPagination.hasNextPage(response, loadedRaw, pageSize)
        nextPage++
        val matching = raw.filter { matches(it, filter, source, repository) }
        if (matching.isNotEmpty()) {
          return matching
        }
      }
      return emptyList()
    }

    private suspend fun loadPage(page: Int): GiteaResponse<List<GiteaPullRequestShort>> {
      val state = serverState(filter.state)
      return when (source) {
        Source.PULLS ->
          api.loadPullRequestsPage(repository, GiteaPullRequestListQuery(state = state, poster = filter.author?.login), page, pageSize)
            .map { list -> list.map(GiteaPullRequestShort::from) }
        Source.ISSUES ->
          api.loadPullRequestIssuesPage(repository, issueQuery(state, assignee = true), page, pageSize)
            .map { list -> list.map(GiteaPullRequestShort::from) }
        Source.REVIEW_REQUESTED_SEARCH ->
          api.searchReviewRequestedPullRequestsPage(repository.owner, issueQuery(state, assignee = false), page, pageSize)
            .map { list -> list.map(GiteaPullRequestShort::from) }
      }
    }

    private fun issueQuery(state: String, assignee: Boolean) = GiteaPullRequestIssueQuery(
      state = state,
      text = filter.searchQuery,
      labels = listOfNotNull(filter.label),
      milestones = listOfNotNull(filter.milestone),
      createdBy = filter.author?.login,
      assignedTo = filter.assignee?.login.takeIf { assignee },
    )
  }

  companion object {
    internal fun serverState(state: GiteaPullRequestState?): String = when (state) {
      GiteaPullRequestState.OPEN -> "open"
      GiteaPullRequestState.CLOSED, GiteaPullRequestState.MERGED -> "closed"
      null -> "all"
    }

    /** Everything the chosen [source] couldn't filter by itself. Text is only checked for [Source.PULLS] (title only). */
    internal fun matches(pr: GiteaPullRequestShort, filter: GiteaPullRequestsFiltersValue, source: Source, repository: GiteaRepositoryPath): Boolean {
      if (pr.repositoryFullName != null && !pr.repositoryFullName.equals(repository.toString(), ignoreCase = true)) {
        return false
      }
      if (filter.state != null && pr.state != filter.state) {
        return false
      }
      if (filter.author != null && !pr.author?.login.equals(filter.author.login, ignoreCase = true)) {
        return false
      }
      if (filter.assignee != null && pr.assignees.none { it.login.equals(filter.assignee.login, ignoreCase = true) }) {
        return false
      }
      if (filter.label != null && pr.labels.none { it.name == filter.label }) {
        return false
      }
      if (filter.milestone != null && pr.milestone != filter.milestone) {
        return false
      }
      if (filter.reviewer != null && source == Source.PULLS &&
          pr.reviewers.orEmpty().none { it.login.equals(filter.reviewer.login, ignoreCase = true) }) {
        return false
      }
      return filter.searchQuery == null || source != Source.PULLS || pr.title.contains(filter.searchQuery, ignoreCase = true)
    }
  }
}
