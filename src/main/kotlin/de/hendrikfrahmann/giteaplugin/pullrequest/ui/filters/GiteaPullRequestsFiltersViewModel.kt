package de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters

import com.intellij.collaboration.ui.codereview.list.search.ReviewListQuickFilter
import com.intellij.collaboration.ui.codereview.list.search.ReviewListSearchPanelViewModelBase
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.collaboration.util.IncrementallyComputedValue
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaMilestoneDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestState
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue.UserFilterValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

sealed class GiteaPullRequestsQuickFilter : ReviewListQuickFilter<GiteaPullRequestsFiltersValue> {
  class Open : GiteaPullRequestsQuickFilter() {
    override val filter = GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.OPEN)
  }

  class CreatedByMe(user: GiteaUserDTO) : GiteaPullRequestsQuickFilter() {
    override val filter = GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.OPEN, author = UserFilterValue.of(user))
  }

  class AssignedToMe(user: GiteaUserDTO) : GiteaPullRequestsQuickFilter() {
    override val filter = GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.OPEN, assignee = UserFilterValue.of(user))
  }

  class ReviewRequested(user: GiteaUserDTO) : GiteaPullRequestsQuickFilter() {
    override val filter = GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.OPEN, reviewer = UserFilterValue.of(user))
  }

  class Closed : GiteaPullRequestsQuickFilter() {
    override val filter = GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.CLOSED)
  }
}

/** Filters of the PR list; choosers load users, labels and milestones of the repository on first use. */
@OptIn(ExperimentalCoroutinesApi::class)
class GiteaPullRequestsFiltersViewModel(
  scope: CoroutineScope,
  historyModel: GiteaPullRequestsFiltersHistoryModel,
  /** `null` without a login (anonymous connection): no "… me" quick filters. */
  val currentUser: GiteaUserDTO?,
  val avatarIconsProvider: IconsProvider<GiteaUserDTO>,
  loadUsers: suspend () -> List<GiteaUserDTO>,
  labelsBatches: () -> Flow<List<GiteaLabelDTO>>,
  milestonesBatches: () -> Flow<List<GiteaMilestoneDTO>>,
) : ReviewListSearchPanelViewModelBase<GiteaPullRequestsFiltersValue, GiteaPullRequestsQuickFilter>(
  scope, historyModel,
  emptySearch = GiteaPullRequestsFiltersValue.EMPTY,
  defaultFilter = GiteaPullRequestsQuickFilter.Open().filter,
) {
  override fun GiteaPullRequestsFiltersValue.withQuery(query: String?): GiteaPullRequestsFiltersValue = copy(searchQuery = query)

  override val quickFilters: List<GiteaPullRequestsQuickFilter> = buildList {
    add(GiteaPullRequestsQuickFilter.Open())
    if (currentUser != null) {
      add(GiteaPullRequestsQuickFilter.CreatedByMe(currentUser))
      add(GiteaPullRequestsQuickFilter.ReviewRequested(currentUser))
      add(GiteaPullRequestsQuickFilter.AssignedToMe(currentUser))
    }
    add(GiteaPullRequestsQuickFilter.Closed())
  }

  val stateFilterState: MutableStateFlow<GiteaPullRequestState?> = searchState.partialState(GiteaPullRequestsFiltersValue::state) { copy(state = it) }
  val authorFilterState: MutableStateFlow<UserFilterValue?> = searchState.partialState(GiteaPullRequestsFiltersValue::author) { copy(author = it) }
  val assigneeFilterState: MutableStateFlow<UserFilterValue?> = searchState.partialState(GiteaPullRequestsFiltersValue::assignee) { copy(assignee = it) }
  val reviewerFilterState: MutableStateFlow<UserFilterValue?> = searchState.partialState(GiteaPullRequestsFiltersValue::reviewer) { copy(reviewer = it) }
  val labelFilterState: MutableStateFlow<String?> = searchState.partialState(GiteaPullRequestsFiltersValue::label) { copy(label = it) }
  val milestoneFilterState: MutableStateFlow<String?> = searchState.partialState(GiteaPullRequestsFiltersValue::milestone) { copy(milestone = it) }

  private val reloadSignal = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

  val users: StateFlow<IncrementallyComputedValue<List<GiteaUserDTO>>> =
    reloadSignal.transformLatest {
      emit(IncrementallyComputedValue.loading())
      emit(try {
        IncrementallyComputedValue.success(loadUsers().sortedBy { it.login.lowercase() })
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        IncrementallyComputedValue.failure(e)
      })
    }.stateIn(scope, SharingStarted.Lazily, IncrementallyComputedValue.loading())

  val labels: StateFlow<IncrementallyComputedValue<List<GiteaLabelDTO>>> = incremental(scope, labelsBatches)
  val milestones: StateFlow<IncrementallyComputedValue<List<GiteaMilestoneDTO>>> = incremental(scope, milestonesBatches)

  fun reloadData() {
    reloadSignal.tryEmit(Unit)
  }

  private fun <T> incremental(scope: CoroutineScope, batches: () -> Flow<List<T>>): StateFlow<IncrementallyComputedValue<List<T>>> =
    reloadSignal.transformLatest {
      val loaded = mutableListOf<T>()
      emit(IncrementallyComputedValue.loading())
      try {
        batches().collect {
          loaded += it
          emit(IncrementallyComputedValue.partialSuccess(loaded.toList()))
        }
        emit(IncrementallyComputedValue.success(loaded.toList()))
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        emit(when {
          loaded.isEmpty() -> IncrementallyComputedValue.failure(e)
          else -> IncrementallyComputedValue.partialFailure(loaded.toList(), e)
        })
      }
    }.stateIn(scope, SharingStarted.Lazily, IncrementallyComputedValue.loading())
}
