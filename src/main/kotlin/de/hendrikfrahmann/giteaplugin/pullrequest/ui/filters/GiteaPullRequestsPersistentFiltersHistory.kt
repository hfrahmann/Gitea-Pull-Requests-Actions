package de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters

import com.intellij.collaboration.ui.codereview.list.search.PersistingReviewListSearchHistoryModel
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestState
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue.UserFilterValue

/** Last filter and search history of the PR list, per project (workspace file). */
@Service(Service.Level.PROJECT)
@State(name = "GiteaPullRequestFiltersHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)], reportStatistic = false)
class GiteaPullRequestsPersistentFiltersHistory : PersistentStateComponent<GiteaPullRequestsPersistentFiltersHistory.HistoryState> {
  class HistoryState {
    @get:XCollection(style = XCollection.Style.v2)
    var history: MutableList<FilterBean> = mutableListOf()
    var lastFilter: FilterBean? = null
  }

  /** XML form of [GiteaPullRequestsFiltersValue]. */
  @Tag("filter")
  class FilterBean {
    var query: String? = null
    var state: String? = null
    var author: String? = null
    var authorName: String? = null
    var assignee: String? = null
    var assigneeName: String? = null
    var reviewer: String? = null
    var reviewerName: String? = null
    var label: String? = null
    var milestone: String? = null

    fun toValue(): GiteaPullRequestsFiltersValue = GiteaPullRequestsFiltersValue(
      searchQuery = query,
      state = state?.let { runCatching { GiteaPullRequestState.valueOf(it) }.getOrNull() },
      author = author?.let { UserFilterValue(it, authorName) },
      assignee = assignee?.let { UserFilterValue(it, assigneeName) },
      reviewer = reviewer?.let { UserFilterValue(it, reviewerName) },
      label = label,
      milestone = milestone,
    )

    companion object {
      fun of(value: GiteaPullRequestsFiltersValue): FilterBean = FilterBean().apply {
        query = value.searchQuery
        state = value.state?.name
        author = value.author?.login; authorName = value.author?.fullName
        assignee = value.assignee?.login; assigneeName = value.assignee?.fullName
        reviewer = value.reviewer?.login; reviewerName = value.reviewer?.fullName
        label = value.label
        milestone = value.milestone
      }
    }
  }

  private var state = HistoryState()

  override fun getState(): HistoryState = state

  override fun loadState(state: HistoryState) {
    this.state = state
  }

  var lastFilter: GiteaPullRequestsFiltersValue?
    get() = state.lastFilter?.toValue()
    set(value) {
      state.lastFilter = value?.let(FilterBean::of)
    }

  var history: List<GiteaPullRequestsFiltersValue>
    get() = state.history.map(FilterBean::toValue)
    set(value) {
      state.history = value.mapTo(mutableListOf(), FilterBean::of)
    }

  companion object {
    fun getInstance(project: Project): GiteaPullRequestsPersistentFiltersHistory = project.service()
  }
}

class GiteaPullRequestsFiltersHistoryModel(private val persistent: GiteaPullRequestsPersistentFiltersHistory)
  : PersistingReviewListSearchHistoryModel<GiteaPullRequestsFiltersValue>() {
  override var lastFilter: GiteaPullRequestsFiltersValue?
    get() = persistent.lastFilter
    set(value) {
      persistent.lastFilter = value
    }

  override var persistentHistory: List<GiteaPullRequestsFiltersValue>
    get() = persistent.history
    set(value) {
      persistent.history = value
    }
}
