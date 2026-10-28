package de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff

import com.intellij.collaboration.ui.codereview.diff.AsyncDiffRequestProcessorFactory
import com.intellij.collaboration.ui.codereview.diff.viewer.showCodeReview
import com.intellij.collaboration.ui.codereview.editor.CodeReviewComponentInlayRenderer
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewInlayModel
import com.intellij.collaboration.util.KeyValuePair
import com.intellij.collaboration.util.filePath
import com.intellij.collaboration.util.fileStatus
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.impl.DiffEditorViewer
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ui.PresentableChange
import com.intellij.util.cancelOnDispose
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Creates the diff viewer of a `giteapr://` file, bound to the PR's [GiteaPullRequestDiffViewModel]. */
@Service(Service.Level.PROJECT)
class GiteaPullRequestDiffService(private val project: Project, private val cs: CoroutineScope) {
  fun createProcessor(connectionId: String, number: Long): DiffEditorViewer {
    val vmFlow = GiteaToolWindowProjectViewModel.getInstance(project).connectedProjectVm.map { vm ->
      vm?.takeIf { it.connection.id == connectionId }?.diffViewModel(number)
    }
    return AsyncDiffRequestProcessorFactory.createIn(cs, project, vmFlow, ::createContext, ::presentation)
  }

  private fun createContext(vm: GiteaPullRequestDiffViewModel): List<KeyValuePair<*>> = listOf(
    KeyValuePair(GiteaPullRequestDiffViewModel.KEY, vm),
    KeyValuePair(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(SUBMIT_REVIEW_ACTION, PREVIOUS_COMMENT_ACTION, NEXT_COMMENT_ACTION, DISCUSSIONS_OPTIONS_GROUP)
      .mapNotNull { ActionManager.getInstance().getAction(it) }),
  )

  private fun presentation(changeVm: GiteaPullRequestDiffChangeViewModel): PresentableChange = object : PresentableChange {
    override fun getFilePath(): FilePath = changeVm.change.filePath
    override fun getFileStatus(): FileStatus = changeVm.change.fileStatus
  }

  companion object {
    const val SUBMIT_REVIEW_ACTION: String = "Gitea.PullRequest.Review.Submit"
    const val NEXT_COMMENT_ACTION: String = "Gitea.PullRequest.Diff.Next.Comment"
    const val PREVIOUS_COMMENT_ACTION: String = "Gitea.PullRequest.Diff.Previous.Comment"
    const val DISCUSSIONS_OPTIONS_GROUP: String = "Gitea.PullRequest.Diff.Discussions.View.Options"
  }
}

/** Adds the review (threads, gutter "+" for new comments) to diff viewers of `giteapr://` files. */
class GiteaPullRequestDiffExtension : DiffExtension() {
  override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
    val project = context.project ?: return
    if (viewer !is DiffViewerBase) {
      return
    }
    val diffVm = context.getUserData(GiteaPullRequestDiffViewModel.KEY) ?: return
    val change = request.getUserData(GiteaPullRequestDiffViewModel.CHANGE_KEY) ?: return
    project.service<InlaysController>().install(diffVm, change, viewer)
  }

  @Service(Service.Level.PROJECT)
  private class InlaysController(private val cs: CoroutineScope) {
    fun install(diffVm: GiteaPullRequestDiffViewModel, change: com.intellij.collaboration.util.RefComparisonChange, viewer: DiffViewerBase) {
      cs.launch(Dispatchers.EDT) {
        val reviewVm = diffVm.createChangeReviewViewModel(this, change) ?: return@launch
        viewer.showCodeReview({ editor, _, locationToLine, lineToLocation, _ ->
          GiteaDiffEditorModel(this, reviewVm, locationToLine, lineToLocation) { line ->
            editor.document.takeIf { line in 0 until it.lineCount }?.let { it.getText(TextRange(it.getLineStartOffset(line), it.getLineEndOffset(line))) }
          }
        }) { inlay ->
          when (inlay) {
            is GiteaDiffEditorModel.ThreadInlay ->
              CodeReviewComponentInlayRenderer(GiteaReviewThreadComponentFactory.createThread(this, reviewVm, inlay.mapped))
            is GiteaDiffEditorModel.NewCommentInlay ->
              CodeReviewComponentInlayRenderer(GiteaReviewThreadComponentFactory.createNewComment(this, reviewVm, inlay.vm))
          }
        }
      }.cancelOnDispose(viewer)
    }
  }
}

/** Editor side of the review for one diff editor: inlays at editor lines and the gutter controls. */
class GiteaDiffEditorModel(
  cs: CoroutineScope,
  private val reviewVm: GiteaChangeReviewViewModel,
  private val locationToLine: (Pair<Side, Int>) -> Int?,
  private val lineToLocation: (Int) -> Pair<Side, Int>?,
  /** Text of an editor line, for suggestions. */
  private val lineText: (Int) -> String? = { null },
) : CodeReviewEditorModel<GiteaDiffEditorModel.Inlay> {

  sealed interface Inlay : CodeReviewInlayModel

  inner class ThreadInlay(val mapped: GiteaMappedThread) : Inlay {
    // content in the key: an edited, answered or resolved thread gets a new component instead of the old one
    override val key: Any = listOf("thread", mapped.thread.id, mapped.thread.resolver?.login,
                                   mapped.thread.comments.map { it.id to it.body })
    override val line: StateFlow<Int?> = MutableStateFlow(locationToLine(mapped.side to mapped.line))
    override val isVisible: StateFlow<Boolean> = MutableStateFlow(mapped.thread.id !in reviewVm.hiddenThreads.value)
  }

  inner class NewCommentInlay(val vm: GiteaNewCommentViewModel) : Inlay {
    override val key: Any = "new:${vm.side}:${vm.line}"
    override val line: StateFlow<Int?> = MutableStateFlow(locationToLine(vm.side to vm.line))
    override val isVisible: StateFlow<Boolean> = MutableStateFlow(true)
  }

  override val inlays: StateFlow<Collection<Inlay>> =
    combine(reviewVm.threads, reviewVm.newComments, reviewVm.hiddenThreads) { threads, newComments, hidden ->
      threads.filter { it.thread.id !in hidden }.map { ThreadInlay(it) } + newComments.values.map { NewCommentInlay(it) }
    }.stateIn(cs, SharingStarted.Eagerly, emptyList())

  override val gutterControlsState: StateFlow<CodeReviewEditorGutterControlsModel.ControlsState?> =
    combine(reviewVm.threads, reviewVm.newComments) { threads, newComments ->
      GutterState(threads.mapNotNullTo(mutableSetOf()) { locationToLine(it.side to it.line) },
                  newComments.keys.mapNotNullTo(mutableSetOf()) { locationToLine(it) })
    }.stateIn(cs, SharingStarted.Eagerly, null)

  private inner class GutterState(override val linesWithComments: Set<Int>, override val linesWithNewComments: Set<Int>)
    : CodeReviewEditorGutterControlsModel.ControlsState {
    override fun isLineCommentable(lineIdx: Int): Boolean = reviewVm.canComment && lineToLocation(lineIdx) != null
  }

  override fun canCreateComment(lineIdx: Int): Boolean = reviewVm.canComment && lineToLocation(lineIdx) != null

  override fun requestNewComment(lineIdx: Int) {
    val (side, line) = lineToLocation(lineIdx) ?: return
    reviewVm.requestNewComment(side, line, lineText(lineIdx))
  }

  override fun cancelNewComment(lineIdx: Int) {
    val (side, line) = lineToLocation(lineIdx) ?: return
    reviewVm.cancelNewComment(side, line)
  }

  override fun toggleComments(lineIdx: Int) {
    reviewVm.toggleThreads(reviewVm.threads.value.filter { locationToLine(it.side to it.line) == lineIdx }.map { it.thread.id })
  }
}
