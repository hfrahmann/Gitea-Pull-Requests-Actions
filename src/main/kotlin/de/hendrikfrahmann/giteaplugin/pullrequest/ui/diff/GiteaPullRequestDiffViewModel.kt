package de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff

import com.intellij.collaboration.ui.codereview.diff.DiscussionsViewOption
import com.intellij.collaboration.ui.codereview.diff.model.AsyncDiffViewModel
import com.intellij.collaboration.ui.codereview.diff.model.CodeReviewDiffProcessorViewModel
import com.intellij.collaboration.ui.codereview.diff.model.CodeReviewDiscussionsViewModel
import com.intellij.collaboration.ui.codereview.diff.model.DiffViewerScrollRequest
import com.intellij.collaboration.ui.codereview.diff.model.PreLoadingCodeReviewAsyncDiffViewModelDelegate
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.collaboration.util.ComputedResult
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.util.Side
import com.intellij.openapi.ListSelection
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestChanges
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestDataProvider
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaReviewThread
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaCommentContext
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaCommentEditorSupport
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.GiteaPullRequestDiffUtil
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import git4idea.changes.GitTextFilePatchWithHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The review diff of one PR (editor tab `giteapr://`): which files are shown, one [GiteaPullRequestDiffChangeViewModel]
 * per file, and per file a [GiteaChangeReviewViewModel] with the inline threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GiteaPullRequestDiffViewModel(
  parentCs: CoroutineScope,
  val project: Project,
  val connection: GiteaProjectConnection,
  val dataProvider: GiteaPullRequestDataProvider,
  val avatarIconsProvider: IconsProvider<GiteaUserDTO>,
) : CodeReviewDiffProcessorViewModel<GiteaPullRequestDiffChangeViewModel>, CodeReviewDiscussionsViewModel {
  private val cs = parentCs.childScope()

  private val _discussionsViewOption = MutableStateFlow(GiteaProjectSettings.getInstance(project).diffDiscussionsViewOption)
  override val discussionsViewOption: StateFlow<DiscussionsViewOption> = _discussionsViewOption.asStateFlow()

  val number: Long get() = dataProvider.number
  val reviewData get() = dataProvider.reviewData

  /** Completion and image upload for the comment fields of this PR. */
  val commentEditorSupport: GiteaCommentEditorSupport by lazy {
    GiteaCommentEditorSupport(project, connection.api, connection.repo.repository.path,
                              dataProvider.snapshot.value?.getOrNull()?.pullRequest?.user, avatarIconsProvider)
  }

  /** Shared by the comment components of the diff and the timeline. */
  val commentContext: GiteaCommentContext by lazy { GiteaCommentContext(project, connection, avatarIconsProvider, commentEditorSupport) }

  private val loadedChanges: StateFlow<ComputedResult<GiteaPullRequestChanges>?> = dataProvider.changes.flatMapLatest { deferred ->
    deferred?.let(::awaitChanges) ?: flowOf(null)
  }.stateIn(cs, SharingStarted.Eagerly, null)

  /** Index of the thread the comment navigation is at, in [navigableThreads]. */
  private var navigationIndex = -1

  private val delegate = PreLoadingCodeReviewAsyncDiffViewModelDelegate.create(loadedChanges) { changes, change: RefComparisonChange ->
    GiteaPullRequestDiffChangeViewModel(this, project, changes, change)
  }

  override val changes: StateFlow<ComputedResult<CodeReviewDiffProcessorViewModel.State<GiteaPullRequestDiffChangeViewModel>>?> =
    delegate.changes.stateIn(cs, SharingStarted.Eagerly, null)

  override fun setDiscussionsViewOption(viewOption: DiscussionsViewOption) {
    _discussionsViewOption.value = viewOption
    GiteaProjectSettings.getInstance(project).diffDiscussionsViewOption = viewOption
  }

  override fun showChange(change: GiteaPullRequestDiffChangeViewModel, scrollRequest: DiffViewerScrollRequest?) =
    delegate.showChange(change.change, scrollRequest)

  override fun showChange(changeIdx: Int, scrollRequest: DiffViewerScrollRequest?) {
    val change = changes.value?.result?.getOrNull()?.selectedChanges?.list?.getOrNull(changeIdx) ?: return
    showChange(change, scrollRequest)
  }

  /** Called before opening the diff file: which files to show and which one first. */
  fun showChanges(changes: ListSelection<RefComparisonChange>) = delegate.showChanges(changes)

  /** Inline review state of [change] for one diff editor; cancelled with [scope]. */
  fun createChangeReviewViewModel(scope: CoroutineScope, change: RefComparisonChange): GiteaChangeReviewViewModel? {
    val changes = loadedChanges.value?.result?.getOrNull() ?: return null
    val patch = changes.comparison.patchesByChange[change] ?: return null
    return GiteaChangeReviewViewModel(scope, this, change, patch, changes.comparison.mergeBaseSha, changes.comparison.headSha)
  }

  /**
   * Review model for the file at repository-relative [path] (editor review mode), once the changes are loaded;
   * `null` if the PR doesn't change the file or the changes can't be loaded.
   */
  suspend fun createChangeReviewViewModelFor(scope: CoroutineScope, path: String): GiteaChangeReviewViewModel? {
    val loaded = loadedChanges.first { it != null && !it.isInProgress }?.result?.getOrNull() ?: return null
    val change = loaded.comparison.changes.firstOrNull { change ->
      loaded.comparison.patchesByChange[change]?.patch?.afterName == path
    } ?: return null
    return createChangeReviewViewModel(scope, change)
  }

  /**
   * Shows [thread] in the diff: selects its file among all changes and scrolls to its line (just the file if the thread is
   * outdated). Waits for the changes; `false` if the file is not part of the PR (anymore).
   */
  suspend fun showThread(thread: GiteaReviewThread): Boolean {
    val loaded = loadedChanges.first { it != null && !it.isInProgress }?.result?.getOrNull() ?: return false
    val all = loaded.comparison.changes
    val index = all.indexOfFirst { change ->
      val patch = loaded.comparison.patchesByChange[change]?.patch
      thread.path == patch?.afterName || thread.path == patch?.beforeName
    }
    if (index < 0) {
      return false
    }
    val change = all[index]
    val location = loaded.comparison.patchesByChange[change]?.let { thread.mapToDiff(it, loaded.comparison.mergeBaseSha, loaded.comparison.headSha) }
    delegate.showChanges(ListSelection.createAt(all, index))
    delegate.showChange(change, location?.let { DiffViewerScrollRequest.toLine(it) })
    return true
  }

  /** Visible threads of all files shown in the diff, in file order then line order (for next/previous comment). */
  private fun navigableThreads(): List<Pair<RefComparisonChange, Pair<Side, Int>>> {
    val loaded = loadedChanges.value?.result?.getOrNull() ?: return emptyList()
    val files = changes.value?.result?.getOrNull()?.selectedChanges?.list?.map { it.change } ?: return emptyList()
    val threads = reviewData.state.value?.getOrNull()?.threads.orEmpty().filter { isVisible(it) }
    return files.flatMap { change ->
      val patch = loaded.comparison.patchesByChange[change] ?: return@flatMap emptyList()
      val paths = setOfNotNull(patch.patch.beforeName, patch.patch.afterName)
      threads.filter { it.path in paths }.mapNotNull { thread -> thread.mapToDiff(patch, loaded.comparison.mergeBaseSha, loaded.comparison.headSha) }
        .sortedWith(compareBy({ it.second }, { it.first.ordinal })).map { change to it }
    }
  }

  /** Moves to the next/previous thread, across files; `false` if there is none. */
  fun navigateComment(next: Boolean): Boolean {
    val all = navigableThreads()
    if (all.isEmpty()) {
      return false
    }
    val selected = changes.value?.result?.getOrNull()?.selectedChanges?.let { it.list.getOrNull(it.selectedIndex) }?.change
    if (all.getOrNull(navigationIndex)?.first != selected) {
      navigationIndex = startIndexInFile(all, selected, next)
    }
    val step = when {
      next -> 1
      else -> -1
    }
    val target = (navigationIndex + step).takeIf { it in all.indices } ?: return false
    navigationIndex = target
    val (change, location) = all[target]
    delegate.showChange(change, DiffViewerScrollRequest.toLine(location))
    return true
  }

  fun isVisible(thread: GiteaReviewThread): Boolean = when (discussionsViewOption.value) {
    DiscussionsViewOption.ALL -> true
    DiscussionsViewOption.UNRESOLVED_ONLY -> !thread.isResolved
    DiscussionsViewOption.DONT_SHOW -> false
  }

  private fun awaitChanges(deferred: Deferred<GiteaPullRequestChanges>): Flow<ComputedResult<GiteaPullRequestChanges>?> = flow {
    emit(ComputedResult.loading())
    val result = try {
      ComputedResult.success(deferred.await())
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      ComputedResult.failure(e)
    }
    emit(result)
  }

  /** Navigation starting in a file the user switched to begins just before (or after) that file's threads. */
  private fun startIndexInFile(all: List<Pair<RefComparisonChange, Pair<Side, Int>>>, selected: RefComparisonChange?, next: Boolean): Int {
    val first = all.indexOfFirst { it.first == selected }
    return when {
      first < 0 && next -> -1
      first < 0 -> all.size
      next -> first - 1
      else -> all.indexOfLast { it.first == selected } + 1
    }
  }

  companion object {
    val KEY: Key<GiteaPullRequestDiffViewModel> = Key.create("Gitea.PullRequest.Diff.ViewModel")
    val CHANGE_KEY: Key<RefComparisonChange> = Key.create("Gitea.PullRequest.Diff.Change")
  }
}

/** One file of the review diff: the platform diff request, computed from local revisions. */
class GiteaPullRequestDiffChangeViewModel(
  parentCs: CoroutineScope,
  private val project: Project,
  private val changes: GiteaPullRequestChanges,
  val change: RefComparisonChange,
) : AsyncDiffViewModel {
  private val cs = parentCs.childScope()
  private val reloads = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply { tryEmit(Unit) }

  @OptIn(ExperimentalCoroutinesApi::class)
  override val request: StateFlow<ComputedResult<DiffRequest>?> = reloads.transformLatest {
    emit(ComputedResult.loading())
    val result = try {
      ComputedResult.success(loadRequest())
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      ComputedResult.failure(e)
    }
    emit(result)
  }.stateIn(cs, SharingStarted.Lazily, null)

  override fun reloadRequest() {
    reloads.tryEmit(Unit)
  }

  /** The platform diff of the local revisions (fetched by the changes loader). */
  private suspend fun loadRequest(): DiffRequest {
    val producer = ChangeDiffRequestProducer.create(project, GiteaPullRequestDiffUtil.toVcsChange(project, change)) ?: error("No diff for $change")
    val request = withContext(Dispatchers.IO) { coroutineToIndicator { producer.process(UserDataHolderBase(), it) } }
    request.putUserData(GiteaPullRequestDiffViewModel.CHANGE_KEY, change)
    return request
  }

  override fun equals(other: Any?): Boolean =
    other is GiteaPullRequestDiffChangeViewModel && other.change == change && other.changes === changes

  override fun hashCode(): Int = change.hashCode()
}

/** A thread placed in the current diff ([line] is 0-based on [side]). */
data class GiteaMappedThread(val thread: GiteaReviewThread, val side: Side, val line: Int)

/** A comment being written at a diff location; [lineText] is the commented line (for "Insert Suggestion"). */
class GiteaNewCommentViewModel(val side: Side, val line: Int, val lineText: String? = null) {
  val text: MutableStateFlow<String> = MutableStateFlow("")
}

/**
 * Inline review of one file in the diff: threads of the file mapped to the current diff (outdated ones are dropped),
 * comments being written, and the actions. Comments can only be added in the PR's cumulative diff (Gitea positions are
 * relative to merge base → commit).
 */
class GiteaChangeReviewViewModel(
  private val cs: CoroutineScope,
  val diffVm: GiteaPullRequestDiffViewModel,
  val change: RefComparisonChange,
  private val patch: GitTextFilePatchWithHistory,
  private val mergeBaseSha: String,
  private val headSha: String,
) {
  private val paths = setOfNotNull(patch.patch.beforeName, patch.patch.afterName)
  val canComment: Boolean get() = patch.isCumulative && !diffVm.connection.isAnonymous
  val currentUser: GiteaUserDTO? get() = diffVm.connection.currentUser
  val avatarIconsProvider: IconsProvider<GiteaUserDTO> get() = diffVm.avatarIconsProvider

  val threads: StateFlow<List<GiteaMappedThread>> = combine(diffVm.reviewData.state, diffVm.discussionsViewOption) { result, _ ->
    result?.getOrNull()?.threads.orEmpty().filter { it.path in paths && diffVm.isVisible(it) }.mapNotNull { thread ->
      thread.mapToDiff(patch, mergeBaseSha, headSha)?.let { (side, line) -> GiteaMappedThread(thread, side, line) }
    }
  }.onStart { emit(emptyList()) }.stateIn(cs, SharingStarted.Eagerly, emptyList())

  private val _newComments = MutableStateFlow<Map<Pair<Side, Int>, GiteaNewCommentViewModel>>(emptyMap())
  val newComments: StateFlow<Map<Pair<Side, Int>, GiteaNewCommentViewModel>> = _newComments.asStateFlow()

  /** Threads hidden with the gutter toggle. */
  private val _hiddenThreads = MutableStateFlow<Set<Long>>(emptySet())
  val hiddenThreads: StateFlow<Set<Long>> = _hiddenThreads.asStateFlow()

  val hasPendingReview: Flow<Boolean> = diffVm.reviewData.state.map { it?.getOrNull()?.hasPendingReview == true }

  fun requestNewComment(side: Side, line: Int, lineText: String? = null) {
    if (!canComment) {
      return
    }
    _newComments.update { comments ->
      when {
        side to line in comments -> comments
        else -> comments + ((side to line) to GiteaNewCommentViewModel(side, line, lineText))
      }
    }
  }

  /**
   * Applies a ```suggestion block of [thread] to the local file: replaces the thread's line (new side) in the working
   * copy. Meant for a checked-out PR branch; returns `false` if the file or line isn't there.
   */
  fun applySuggestion(thread: GiteaMappedThread, suggestion: String): Boolean {
    if (thread.side != Side.RIGHT) {
      return false
    }
    val root = diffVm.connection.repo.gitRepository.root
    val file = root.findFileByRelativePath(thread.thread.path) ?: return false
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
    if (thread.line >= document.lineCount) {
      return false
    }
    WriteCommandAction.runWriteCommandAction(diffVm.project, GiteaBundle.message("review.suggestion.apply"), null, {
      document.replaceString(document.getLineStartOffset(thread.line), document.getLineEndOffset(thread.line), suggestion.trimEnd('\n'))
    })
    return true
  }

  fun cancelNewComment(side: Side, line: Int) = _newComments.update { it - (side to line) }

  /** Hides the threads, or shows them again when all of them are hidden. */
  fun toggleThreads(threadIds: Collection<Long>) = _hiddenThreads.update { hidden ->
    when {
      threadIds.all { it in hidden } -> hidden - threadIds.toSet()
      else -> hidden + threadIds
    }
  }

  /** [pending]: add to the pending review (Start/Add review comment), otherwise post a single comment. */
  suspend fun submitNewComment(comment: GiteaNewCommentViewModel, pending: Boolean) {
    val path = when (comment.side) {
      Side.LEFT -> patch.patch.beforeName
      Side.RIGHT -> patch.patch.afterName
    } ?: return
    diffVm.reviewData.addComment(path, comment.side, comment.line + 1, headSha, comment.text.value, pending)
    cancelNewComment(comment.side, comment.line)
  }
}
