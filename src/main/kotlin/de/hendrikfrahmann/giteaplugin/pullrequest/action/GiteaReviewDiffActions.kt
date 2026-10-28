package de.hendrikfrahmann.giteaplugin.pullrequest.action

import com.intellij.collaboration.ui.codereview.diff.action.CodeReviewDiscussionsToggleAction
import com.intellij.collaboration.ui.codereview.diff.model.CodeReviewDiscussionsViewModel
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbAwareAction
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff.GiteaPullRequestDiffViewModel

/** `Gitea.PullRequest.Diff.Discussions.View.Options` in the diff settings: all / unresolved only / hidden. */
class GiteaDiffDiscussionsToggleAction : CodeReviewDiscussionsToggleAction() {
  override fun findViewModel(ctx: DataContext): CodeReviewDiscussionsViewModel? =
    ctx.getData(DiffDataKeys.DIFF_CONTEXT)?.getUserData(GiteaPullRequestDiffViewModel.KEY)
}

/**
 * `Gitea.PullRequest.Diff.Next.Comment` / `.Previous.Comment`: jumps through the visible threads of all files in the
 * review diff (own implementation – the platform's comment navigation needs an internal view model).
 */
abstract class GiteaDiffCommentNavigationAction(private val next: Boolean) : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.getData(DiffDataKeys.DIFF_CONTEXT)?.getUserData(GiteaPullRequestDiffViewModel.KEY) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    e.getData(DiffDataKeys.DIFF_CONTEXT)?.getUserData(GiteaPullRequestDiffViewModel.KEY)?.navigateComment(next)
  }
}

class GiteaDiffNextCommentAction : GiteaDiffCommentNavigationAction(true)
class GiteaDiffPreviousCommentAction : GiteaDiffCommentNavigationAction(false)
