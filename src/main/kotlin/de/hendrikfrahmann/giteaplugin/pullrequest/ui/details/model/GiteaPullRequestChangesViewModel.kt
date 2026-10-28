package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model

import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeDetails
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeList
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModel
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangesContainer
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangesViewModel
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangesViewModelDelegate
import com.intellij.collaboration.util.ChangesSelection
import com.intellij.collaboration.util.ComputedResult
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.collaboration.util.filePath
import com.intellij.openapi.ListSelection
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.vcsUtil.VcsFileUtil
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCommitDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestChanges
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestDataProvider
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestViewedState
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.util.childScope
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.util.Date

class GiteaCommitViewModel(commit: GiteaCommitDTO) {
  val sha: String = commit.sha
  val shortId: String = commit.sha.take(8)
  private val message = commit.commit?.message.orEmpty().trim()
  val titleHtml: String = StringUtil.escapeXmlEntities(message.lineSequence().firstOrNull().orEmpty())
  val descriptionHtml: String = StringUtil.escapeXmlEntities(message.substringAfter('\n', "").trim()).replace("\n", "<br/>")
  val author: String = commit.commit?.author?.name ?: commit.author?.login.orEmpty()
  val date: Date = commit.commit?.author?.date?.let { runCatching { Date.from(OffsetDateTime.parse(it).toInstant()) }.getOrNull() }
                   ?: commit.created ?: Date(0)
}

/** Commit selector + the change list of the selected commit (or of the whole PR). */
@OptIn(ExperimentalCoroutinesApi::class)
class GiteaPullRequestChangesViewModel(
  parentCs: CoroutineScope,
  private val project: Project,
  private val connection: GiteaProjectConnection,
  private val dataProvider: GiteaPullRequestDataProvider,
  /** Opens the review diff; the details pass the selected files. */
  private val showDiff: (ListSelection<RefComparisonChange>) -> Unit,
) : CodeReviewChangesViewModel<GiteaCommitViewModel> {
  private val cs = parentCs.childScope()

  private val changes: StateFlow<Result<GiteaPullRequestChanges>?> = dataProvider.changes.filterNotNull().mapLatest { deferred ->
    try {
      Result.success(deferred.await())
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      Result.failure(e)
    }
  }.stateIn(cs, SharingStarted.Eagerly, null)

  private val delegate = CodeReviewChangesViewModelDelegate.create(cs, changes.filterNotNull().map { result ->
    result.map { ChangesContainer(it) }
  }) { container, changeList ->
    val parsed = (container as ChangesContainer).changes
    // a PR with one commit: that commit's list is the whole PR, so it keeps the viewed state
    when {
      changeList.commitSha == null || parsed.commits.size == 1 -> CumulativeChangeListViewModel(this, parsed, changeList)
      else -> CommitChangeListViewModel(this, changeList)
    }
  }

  val changeListVm: StateFlow<ComputedResult<GiteaPullRequestChangeListViewModel>> = delegate.changeListVm

  override val reviewCommits: SharedFlow<List<GiteaCommitViewModel>> = changes.mapNotNull { it?.getOrNull() }
    .map { it.commits.map(::GiteaCommitViewModel) }
    .shareIn(cs, SharingStarted.Eagerly, 1)

  /** `-1` while the whole PR is shown. */
  override val selectedCommitIndex: SharedFlow<Int> = combine(reviewCommits, delegate.selectedCommit) { commits, sha ->
    sha?.let { commits.indexOfFirst { commit -> commit.sha == it } } ?: -1
  }.shareIn(cs, SharingStarted.Eagerly, 1)

  override val selectedCommit: SharedFlow<GiteaCommitViewModel?> = combine(reviewCommits, selectedCommitIndex) { commits, index ->
    commits.getOrNull(index)
  }.shareIn(cs, SharingStarted.Eagerly, 1)

  init {
    // a single commit is shown selected (title + "Show details" row), like the GitHub plugin
    cs.launch {
      combine(reviewCommits, delegate.changeListVm, delegate.selectedCommit, ::Triple).collect { (commits, changeList, selected) ->
        if (commits.size == 1 && selected == null && changeList.result?.isSuccess == true) {
          delegate.selectCommit(0)
        }
      }
    }
  }

  override fun selectCommit(index: Int) {
    delegate.selectCommit(index)?.selectChange(null)
  }

  override fun selectNextCommit() {
    delegate.selectNextCommit()?.selectChange(null)
  }

  override fun selectPreviousCommit() {
    delegate.selectPreviousCommit()?.selectChange(null)
  }

  override fun commitHash(commit: GiteaCommitViewModel): String = commit.shortId

  private class ChangesContainer(val changes: GiteaPullRequestChanges)
    : CodeReviewChangesContainer(changes.comparison.changes, changes.comparison.commits.map { it.sha }, changes.comparison.changesByCommits)

  /**
   * Shared by both change lists: diffs open with the platform diff viewer. Selection handling mirrors collaboration-tools'
   * `CodeReviewChangeListViewModelBase`, which is internal API.
   */
  abstract inner class GiteaPullRequestChangeListViewModel(scope: CoroutineScope, private val changeList: CodeReviewChangeList)
    : CodeReviewChangeListViewModel.WithDetails {
    protected val cs: CoroutineScope = scope.childScope()
    override val project: Project get() = this@GiteaPullRequestChangesViewModel.project
    override val changes: List<RefComparisonChange> = changeList.changes

    private val _selectionRequests = MutableSharedFlow<CodeReviewChangeListViewModel.SelectionRequest>(replay = 1)
    override val selectionRequests: SharedFlow<CodeReviewChangeListViewModel.SelectionRequest> = _selectionRequests.asSharedFlow()

    private val _changesSelection = MutableStateFlow<ChangesSelection?>(null)
    override val changesSelection: StateFlow<ChangesSelection?> = _changesSelection.asStateFlow()

    /** `null` selects all changes (fuzzy), like after choosing a commit. */
    fun selectChange(change: RefComparisonChange?) {
      when (change) {
        null -> {
          _changesSelection.value = ChangesSelection.Fuzzy(changes)
          _selectionRequests.tryEmit(CodeReviewChangeListViewModel.SelectionRequest.All)
        }
        in changes -> {
          _changesSelection.value = ChangesSelection.Precise(changes, change)
          _selectionRequests.tryEmit(CodeReviewChangeListViewModel.SelectionRequest.OneChange(change))
        }
      }
    }

    override fun updateSelectedChanges(selection: ChangesSelection?) {
      _changesSelection.value = selection
    }

    override fun showDiffPreview() {
      val selection = changesSelection.value ?: return
      cs.launch(Dispatchers.EDT) { showDiff(ListSelection.createAt(selection.changes, selection.selectedIdx)) }
    }

    override fun showDiff() = showDiffPreview()
  }

  /** Changes of a single commit: no viewed state (it belongs to the PR's cumulative diff). */
  private inner class CommitChangeListViewModel(scope: CoroutineScope, changeList: CodeReviewChangeList)
    : GiteaPullRequestChangeListViewModel(scope, changeList) {
    override val detailsByChange: StateFlow<Map<RefComparisonChange, CodeReviewChangeDetails>> =
      MutableStateFlow(changes.associateWith { CodeReviewChangeDetails(true, 0) })
  }

  /** The whole PR: files can be marked viewed; a new commit to a file makes it unviewed again. */
  private inner class CumulativeChangeListViewModel(
    scope: CoroutineScope,
    private val parsed: GiteaPullRequestChanges,
    changeList: CodeReviewChangeList,
  ) : GiteaPullRequestChangeListViewModel(scope, changeList), CodeReviewChangeListViewModel.WithViewedState {
    private val viewedState = GiteaPullRequestViewedState.getInstance(project)
    private val key = GiteaPullRequestViewedState.key(connection.repo.repository.serverPath.toString(),
                                                      connection.repo.repository.path.toString(), dataProvider.number)

    override val detailsByChange: StateFlow<Map<RefComparisonChange, CodeReviewChangeDetails>> =
      viewedState.updates.onStart { emit(Unit) }.map {
        changes.associateWith { change -> CodeReviewChangeDetails(isViewed(change), 0) }
      }.stateIn(cs, SharingStarted.Eagerly, emptyMap())

    override fun setViewedState(changes: Iterable<RefComparisonChange>, viewed: Boolean) {
      viewedState.setViewed(key, changes.mapNotNull { change -> latestSha(change)?.let { relativePath(change) to it } }, viewed)
    }

    private fun isViewed(change: RefComparisonChange): Boolean =
      latestSha(change)?.let { viewedState.isViewed(key, relativePath(change), it) } == true

    private fun relativePath(change: RefComparisonChange): String =
      VcsFileUtil.relativePath(connection.repo.gitRepository.root, change.filePath)

    private fun latestSha(change: RefComparisonChange): String? {
      val path = relativePath(change)
      return parsed.comparison.commits.lastOrNull { commit -> commit.patches.any { it.afterName == path || it.beforeName == path } }?.sha
    }
  }
}
