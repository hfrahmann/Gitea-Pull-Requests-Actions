package de.hendrikfrahmann.giteaplugin.pullrequest.data

import com.intellij.openapi.application.EDT
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import git4idea.branch.GitBrancher
import git4idea.remote.hosting.GitCodeReviewUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetch + checkout of a PR's head. Same repository: the head branch, tracking the remote one. Fork (or a deleted
 * head branch): Gitea's `refs/pull/N/head` fetched as `<remote>/pull/N` and checked out as local branch `pull/N` – no
 * extra remote for the fork needed.
 */
object GiteaPullRequestCheckout {
  suspend fun checkout(connection: GiteaProjectConnection, pr: GiteaPullRequestDTO) {
    val gitRepository = connection.repo.gitRepository
    val remote = connection.repo.gitRemote
    val head = pr.head ?: return
    val (localName, startPoint) = if (isFork(pr) || head.ref.startsWith("refs/pull/")) {
      val localName = "pull/${pr.number}"
      GitCodeReviewUtils.fetch(gitRepository, remote, "refs/pull/${pr.number}/head:refs/remotes/${remote.name}/$localName")
      localName to "${remote.name}/$localName"
    }
    else {
      GitCodeReviewUtils.fetch(gitRepository, remote, head.ref)
      head.ref to "${remote.name}/${head.ref}"
    }
    withContext(Dispatchers.EDT) {
      val brancher = GitBrancher.getInstance(gitRepository.project)
      if (gitRepository.branches.findLocalBranch(localName) != null) {
        brancher.checkout(localName, false, listOf(gitRepository), null)
      }
      else {
        brancher.checkoutNewBranchStartingFrom(localName, startPoint, listOf(gitRepository), null)
      }
    }
  }

  /** Head in another repository; `repo_id` 0 means Gitea doesn't know (deleted fork) – then the branch is gone anyway. */
  fun isFork(pr: GiteaPullRequestDTO): Boolean {
    val headRepo = pr.head?.repoId ?: 0L
    return headRepo != 0L && headRepo != pr.base?.repoId
  }
}
