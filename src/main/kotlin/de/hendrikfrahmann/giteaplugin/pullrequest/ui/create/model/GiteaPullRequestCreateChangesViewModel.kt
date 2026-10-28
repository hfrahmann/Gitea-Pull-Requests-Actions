package de.hendrikfrahmann.giteaplugin.pullrequest.ui.create.model

import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModel
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangesViewModel
import com.intellij.collaboration.util.ChangesSelection
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.VcsCommitMetadata
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaPullRequestDiffUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * "Changes from N commits ▾" of the New Pull Request tab, like GitHub's create tab: all changes between base and head,
 * or those of one chosen commit (computed by git when chosen). GitHub's `CodeReviewCommitsChangesStateHandler` is
 * internal API, so the selection is kept here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GiteaPullRequestCreateChangesViewModel(
  private val cs: CoroutineScope,
  private val project: Project,
  private val comparison: GiteaPullRequestCreateComparison,
  private val loadCommitChanges: suspend (VcsCommitMetadata) -> List<RefComparisonChange>,
) : CodeReviewChangesViewModel<VcsCommitMetadata> {

  override val reviewCommits: StateFlow<List<VcsCommitMetadata>> = MutableStateFlow(comparison.commits)

  private val _selectedCommitIndex = MutableStateFlow(-1)
  override val selectedCommitIndex: StateFlow<Int> = _selectedCommitIndex.asStateFlow()

  override val selectedCommit: StateFlow<VcsCommitMetadata?> =
    _selectedCommitIndex.map { comparison.commits.getOrNull(it) }.stateIn(cs, SharingStarted.Eagerly, null)

  /** `null` while git computes the changes of the chosen commit. */
  val changeListVm: StateFlow<Result<ChangeListViewModel>?> = selectedCommit.transformLatest { commit ->
    if (commit == null) {
      emit(Result.success(ChangeListViewModel(comparison.changes)))
      return@transformLatest
    }
    emit(null)
    val changes = try {
      Result.success(loadCommitChanges(commit))
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      Result.failure(e)
    }
    emit(changes.map(::ChangeListViewModel))
  }.stateIn(cs, SharingStarted.Eagerly, null)

  override fun selectCommit(index: Int) {
    _selectedCommitIndex.value = index.takeIf { it in comparison.commits.indices } ?: -1
  }

  override fun selectNextCommit() {
    _selectedCommitIndex.value = (_selectedCommitIndex.value + 1).coerceAtMost(comparison.commits.lastIndex)
  }

  override fun selectPreviousCommit() {
    _selectedCommitIndex.value = (_selectedCommitIndex.value - 1).coerceAtLeast(-1)
  }

  override fun commitHash(commit: VcsCommitMetadata): String = commit.id.toShortString()

  /** Changes shown in the tree; double click or Enter opens the platform diff with the local revisions. */
  inner class ChangeListViewModel(override val changes: List<RefComparisonChange>) : CodeReviewChangeListViewModel {
    override val project: Project get() = this@GiteaPullRequestCreateChangesViewModel.project

    private val _selectionRequests = MutableSharedFlow<CodeReviewChangeListViewModel.SelectionRequest>(replay = 1)
    override val selectionRequests: SharedFlow<CodeReviewChangeListViewModel.SelectionRequest> = _selectionRequests.asSharedFlow()

    private val _changesSelection = MutableStateFlow<ChangesSelection?>(null)
    override val changesSelection: StateFlow<ChangesSelection?> = _changesSelection.asStateFlow()

    override fun updateSelectedChanges(selection: ChangesSelection?) {
      _changesSelection.value = selection
    }

    override fun showDiffPreview() {
      val selection = changesSelection.value ?: return
      cs.launch(Dispatchers.EDT) { GiteaPullRequestDiffUtil.showDiff(project, selection.changes, selection.selectedIdx) }
    }

    override fun showDiff() = showDiffPreview()
  }
}
