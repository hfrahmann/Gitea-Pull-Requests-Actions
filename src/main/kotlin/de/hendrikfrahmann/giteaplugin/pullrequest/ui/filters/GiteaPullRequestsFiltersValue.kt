package de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters

import com.intellij.collaboration.ui.codereview.list.search.ReviewListSearchValue
import com.intellij.openapi.util.NlsSafe
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestState

/** Search field text plus filters of the PR list; `null` = not filtered. */
data class GiteaPullRequestsFiltersValue(
  override val searchQuery: String? = null,
  val state: GiteaPullRequestState? = null,
  val author: UserFilterValue? = null,
  val assignee: UserFilterValue? = null,
  /** Review requested from this user (pending, not yet reviewed). */
  val reviewer: UserFilterValue? = null,
  val label: String? = null,
  val milestone: String? = null,
  /** Draft (WIP) PRs: hide them or show only them. */
  val draft: DraftFilter? = null,
) : ReviewListSearchValue {

  override val filterCount: Int
    get() = listOf(state, author, assignee, reviewer, label, milestone, draft).count { it != null }

  enum class DraftFilter { HIDE, ONLY }

  data class UserFilterValue(val login: @NlsSafe String, val fullName: @NlsSafe String?) {
    @get:NlsSafe
    val presentableName: String get() = fullName?.takeIf { it.isNotBlank() } ?: login

    companion object {
      fun of(user: GiteaUserDTO): UserFilterValue = UserFilterValue(user.login, user.fullName)
    }
  }

  companion object {
    val EMPTY: GiteaPullRequestsFiltersValue = GiteaPullRequestsFiltersValue()
  }
}
