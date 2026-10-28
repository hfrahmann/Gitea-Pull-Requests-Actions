package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model

import com.intellij.collaboration.ui.codereview.details.model.CodeReviewBranches
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewBranchesViewModel
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestCheckout
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.util.childScope
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** `head → base` with checkout ([GiteaPullRequestCheckout]). */
class GiteaPullRequestBranchesViewModel(
  parentCs: CoroutineScope,
  private val connection: GiteaProjectConnection,
  private val pullRequest: StateFlow<GiteaPullRequestDTO>,
) : CodeReviewBranchesViewModel {
  private val cs = parentCs.childScope()
  private val gitRepository = connection.repo.gitRepository

  override val sourceBranch: StateFlow<String> = pullRequest.map(::sourceBranchName).stateIn(cs, SharingStarted.Eagerly, sourceBranchName(pullRequest.value))

  override val isCheckedOut: SharedFlow<Boolean> = combine(repositoryChanges(gitRepository).onStart { emit(Unit) }, pullRequest) { _, pr ->
    pr.head?.sha != null && gitRepository.currentRevision == pr.head.sha
  }.stateIn(cs, SharingStarted.Eagerly, false)

  private val _showBranchesRequests = MutableSharedFlow<CodeReviewBranches>()
  override val showBranchesRequests: SharedFlow<CodeReviewBranches> = _showBranchesRequests.asSharedFlow()

  override fun showBranches() {
    cs.launch {
      val pr = pullRequest.value
      _showBranchesRequests.emit(CodeReviewBranches(sourceBranchName(pr), pr.base?.ref.orEmpty(), !isFork(pr)))
    }
  }

  override fun fetchAndCheckoutRemoteBranch() {
    cs.launch { GiteaPullRequestCheckout.checkout(connection, pullRequest.value) }
  }

  companion object {
    private fun isFork(pr: GiteaPullRequestDTO): Boolean = GiteaPullRequestCheckout.isFork(pr)

    /** `branch`, `owner:branch` for a fork, the short head sha when the head branch is gone. */
    fun sourceBranchName(pr: GiteaPullRequestDTO): String {
      val head = pr.head ?: return ""
      if (head.ref.startsWith("refs/pull/")) {
        return head.sha.take(8)
      }
      val owner = head.repo?.owner?.login
      return when {
        isFork(pr) && owner != null -> "$owner:${head.ref}"
        else -> head.ref
      }
    }

    private fun repositoryChanges(repository: GitRepository): Flow<Unit> = callbackFlow {
      val connection = repository.project.messageBus.connect()
      connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
        if (it == repository) {
          trySend(Unit)
        }
      })
      awaitClose { connection.disconnect() }
    }
  }
}
