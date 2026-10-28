package de.hendrikfrahmann.giteaplugin.pullrequest.review

import com.intellij.collaboration.ui.codereview.diff.viewer.showCodeReview
import com.intellij.collaboration.ui.codereview.editor.CodeReviewComponentInlayRenderer
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff.GiteaDiffEditorModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff.GiteaReviewThreadComponentFactory
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Review mode in regular editors: when the current branch is a PR head and the mode is on, files changed in the PR show
 * their threads and the gutter "+" like the review diff (lines are those of the working copy, assumed to equal the
 * PR head).
 */
class GiteaEditorReviewController : EditorFactoryListener {
  override fun editorCreated(event: EditorFactoryEvent) {
    val editor = event.editor as? EditorEx ?: return
    // diff editors (also the review diff of a checked-out PR) get their review from GiteaPullRequestDiffExtension
    if (editor.editorKind != EditorKind.MAIN_EDITOR) {
      return
    }
    val project = editor.project ?: return
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
    if (!file.isInLocalFileSystem) {
      return
    }
    editor.putUserData(JOB_KEY, project.service<Installer>().install(editor, file))
  }

  override fun editorReleased(event: EditorFactoryEvent) {
    event.editor.getUserData(JOB_KEY)?.cancel()
  }

  @Service(Service.Level.PROJECT)
  private class Installer(private val project: Project, private val cs: CoroutineScope) {
    /** Shows the review in [editor] while the mode is on and the current branch has a PR; until the editor is released. */
    fun install(editor: EditorEx, file: VirtualFile): Job =
      cs.launch {
        combine(GiteaSettings.getInstance().reviewModeInEditorsState, GiteaCurrentBranchPullRequestService.getInstance(project).current, ::Pair)
          .collectLatest { (enabled, current) ->
            if (enabled && current != null) {
              showReview(editor, file, current)
            }
          }
      }

    /** Runs until cancelled (mode switched off, other PR, editor released). */
    private suspend fun showReview(editor: EditorEx, file: VirtualFile, current: GiteaCurrentBranchPullRequestService.Current) {
      val path = VfsUtilCore.getRelativePath(file, current.gitRepository.root) ?: return
      val diffVm = current.projectVm.diffViewModel(current.number)
      withContext(Dispatchers.EDT) {
        val reviewVm = diffVm.createChangeReviewViewModelFor(this, path) ?: return@withContext
        // working copy lines are taken as lines of the PR head (new side)
        val model = GiteaDiffEditorModel(this, reviewVm,
                                         locationToLine = { (side, line) -> line.takeIf { side == Side.RIGHT } },
                                         lineToLocation = { line -> Side.RIGHT to line },
                                         lineText = { line -> lineText(editor, line) })
        editor.showCodeReview(model) { inlay ->
          val component = when (inlay) {
            is GiteaDiffEditorModel.ThreadInlay -> GiteaReviewThreadComponentFactory.createThread(this, reviewVm, inlay.mapped)
            is GiteaDiffEditorModel.NewCommentInlay -> GiteaReviewThreadComponentFactory.createNewComment(this, reviewVm, inlay.vm)
          }
          CodeReviewComponentInlayRenderer(component)
        }
      }
    }

    private fun lineText(editor: EditorEx, line: Int): String? {
      val document = editor.document.takeIf { line in 0 until it.lineCount } ?: return null
      return document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
    }
  }

  private companion object {
    val JOB_KEY: Key<Job> = Key.create("Gitea.Editor.Review.Job")
  }
}

/** `Gitea.PullRequest.Review.Mode.Toggle` – review threads in regular editors on/off. */
class GiteaToggleEditorReviewModeAction : DumbAwareToggleAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  override fun isSelected(e: AnActionEvent): Boolean = GiteaSettings.getInstance().reviewModeInEditors
  override fun setSelected(e: AnActionEvent, state: Boolean) {
    GiteaSettings.getInstance().reviewModeInEditors = state
  }
}
