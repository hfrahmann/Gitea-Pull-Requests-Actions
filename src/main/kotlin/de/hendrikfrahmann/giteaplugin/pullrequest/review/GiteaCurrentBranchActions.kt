package de.hendrikfrahmann.giteaplugin.pullrequest.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import git4idea.branch.GitBranchSyncStatus
import git4idea.repo.GitRepository
import git4idea.ui.branch.GitCurrentBranchPresenter
import javax.swing.Icon

// service(), not serviceIfCreated(): the widget asks before anything else has created the service (like GitLab)
private fun currentPullRequest(project: Project?): GiteaCurrentBranchPullRequestService.Current? =
  project?.takeIf { !it.isDisposed }?.let { GiteaCurrentBranchPullRequestService.getInstance(it) }?.current?.value

private fun isBehind(project: Project?): Boolean =
  project?.takeIf { !it.isDisposed }?.let { GiteaCurrentBranchPullRequestService.getInstance(it) }?.syncStatus?.value?.incoming == true

/**
 * Branch widget: "#3 on feature" with the Gitea icon while the current branch has an open PR, a warning icon when
 * the PR head has commits the local branch lacks. `PresentationData` is internal API, hence the own [Presentation].
 */
class GiteaCurrentBranchPresenter : GitCurrentBranchPresenter {
  override fun getPresentation(repository: GitRepository): GitCurrentBranchPresenter.Presentation? {
    val current = currentPullRequest(repository.project)?.takeIf { it.gitRepository == repository } ?: return null
    val branch = repository.currentBranchName ?: repository.currentRevision?.take(SHORT_SHA_LENGTH) ?: return null
    val shortBranch = StringUtil.shortenTextWithEllipsis(branch, MAX_BRANCH_LENGTH, BRANCH_SUFFIX_LENGTH)
    val text = GiteaBundle.message("pull.request.on.branch", current.number, StringUtil.escapeMnemonics(shortBranch))
    val syncStatus = GitBranchSyncStatus.calcForCurrentBranch(repository)
    if (isBehind(repository.project)) {
      return Presentation(AllIcons.General.Warning, text, GiteaBundle.message("pull.request.on.branch.behind", current.number, branch), syncStatus)
    }
    val description = GiteaBundle.message("pull.request.on.branch.description", current.number, current.pullRequest.title, branch)
    return Presentation(GiteaIcons.Gitea, text, description, syncStatus)
  }

  private class Presentation(override val icon: Icon?, override val text: String, override val description: String?,
                             override val syncStatus: GitBranchSyncStatus) : GitCurrentBranchPresenter.Presentation

  private companion object {
    const val SHORT_SHA_LENGTH = 8
    const val MAX_BRANCH_LENGTH = 40
    const val BRANCH_SUFFIX_LENGTH = 10
  }
}

/** Group `Gitea.PullRequest.Branch.Popup.Actions` in the branch popup – only while the current branch has a Gitea PR. */
class GiteaCurrentBranchActionGroup : DefaultActionGroup() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isVisible = currentPullRequest(e.project) != null
  }
}

/** `Gitea.PullRequest.Branch.Show` – the current branch's PR in the tool window. */
class GiteaShowCurrentBranchPullRequestAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = currentPullRequest(e.project) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    GiteaCurrentBranchPullRequestService.getInstance(e.project ?: return).showPullRequest()
  }
}

/** `Gitea.PullRequest.Branch.Update` – fetch the PR head and merge it into the current branch; only when behind. */
class GiteaUpdateCurrentBranchAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = currentPullRequest(e.project) != null && isBehind(e.project)
  }

  override fun actionPerformed(e: AnActionEvent) {
    GiteaCurrentBranchPullRequestService.getInstance(e.project ?: return).updateBranch()
  }
}
