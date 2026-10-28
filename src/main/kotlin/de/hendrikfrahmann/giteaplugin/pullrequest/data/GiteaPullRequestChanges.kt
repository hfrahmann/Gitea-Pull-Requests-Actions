package de.hendrikfrahmann.giteaplugin.pullrequest.data

import com.intellij.openapi.diff.impl.patch.FilePatch
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCommitDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.request.loadCommitDiff
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestCommits
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestDiff
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import git4idea.changes.GitBranchComparisonResult
import git4idea.changes.GitCommitShaWithPatches
import git4idea.remote.hosting.GitCodeReviewUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Commits (oldest first) and the parsed changes of a PR at one head/merge base. */
class GiteaPullRequestChanges(
  val commits: List<GiteaCommitDTO>,
  val comparison: GitBranchComparisonResult,
)

/**
 * Builds [GiteaPullRequestChanges] like the GitLab plugin: patches from the server (one `.diff` per commit plus the PR's
 * cumulative `.diff`), compared by git4idea. The diff viewer reads file contents from the local repository, so the
 * revisions are fetched first (`refs/pull/N/head` and the base branch) when missing.
 */
class GiteaPullRequestChangesLoader(
  private val project: Project,
  private val connection: GiteaProjectConnection,
  private val pullRequest: GiteaPullRequestDTO,
) {
  private val repository = connection.repo.repository.path
  private val gitRepository = connection.repo.gitRepository

  suspend fun load(): GiteaPullRequestChanges = coroutineScope {
    val api = connection.api
    val number = pullRequest.number
    val mergeBase = pullRequest.mergeBase ?: error("Gitea sent no merge base for #$number")
    val baseSha = pullRequest.base?.sha ?: mergeBase

    val commits = api.loadPullRequestCommits(repository, number).asReversed()
    val fetch = async { ensureRevisionsFetched(commits.map { it.sha } + mergeBase) }

    val commitsWithPatches = loadCommitPatches(commits)
    val headPatches = parsePatches(api.loadPullRequestDiff(repository, number))

    fetch.await()
    val comparison = withContext(Dispatchers.Default) {
      GitBranchComparisonResult.create(project, gitRepository.root, baseSha, mergeBase, commitsWithPatches, headPatches)
    }
    GiteaPullRequestChanges(commits, comparison)
  }

  /** One `.diff` per commit, a few at a time. */
  private suspend fun loadCommitPatches(commits: List<GiteaCommitDTO>): List<GitCommitShaWithPatches> = coroutineScope {
    val permits = Semaphore(PARALLEL_DIFF_REQUESTS)
    commits.map { commit ->
      async {
        val diff = permits.withPermit { connection.api.loadCommitDiff(repository, commit.sha) }
        GitCommitShaWithPatches(commit.sha, commit.parents.map { it.sha }, parsePatches(diff))
      }
    }.awaitAll()
  }

  private suspend fun ensureRevisionsFetched(revisions: List<String>) {
    if (GitCodeReviewUtils.testRevisionsExist(gitRepository, revisions)) {
      return
    }
    val remote = connection.repo.gitRemote
    pullRequest.base?.ref?.let { GitCodeReviewUtils.fetch(gitRepository, remote, it) }
    // Gitea keeps every PR head under refs/pull/N/head, also for PRs from forks; fetch without storing a ref
    GitCodeReviewUtils.fetch(gitRepository, remote, "refs/pull/${pullRequest.number}/head:")
    check(GitCodeReviewUtils.testRevisionsExist(gitRepository, revisions)) { "Could not fetch all revisions of #${pullRequest.number}" }
  }

  companion object {
    private const val PARALLEL_DIFF_REQUESTS = 4

    /** All file patches of a unified git diff, binary ones included (without hunks). */
    fun parsePatches(diff: String): List<FilePatch> {
      if (diff.isBlank()) {
        return emptyList()
      }
      return PatchReader(diff).apply { parseAllPatches() }.allPatches
    }
  }
}
