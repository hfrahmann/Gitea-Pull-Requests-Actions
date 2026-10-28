package de.hendrikfrahmann.giteaplugin.pullrequest

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerMetadataCache
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.findOpenPullRequestByHead
import de.hendrikfrahmann.giteaplugin.pullrequest.review.GiteaCurrentBranchPullRequestService
import de.hendrikfrahmann.giteaplugin.pullrequest.review.GiteaCurrentBranchPullRequestService.BranchState
import git4idea.branch.GitBranchSyncStatus
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.http.HttpClient

/** Current-branch integration without a server: PR of the current branch by head, push detection from repository states. */
class GiteaCurrentBranchTest {
  private lateinit var mock: MockWebServer
  private lateinit var api: GiteaApi
  private val repo = GiteaRepositoryPath("intellij-dev", "plugin-playground")
  private val fork = GiteaRepositoryPath("intellij-reviewer", "plugin-playground")

  @BeforeEach
  fun setUp() {
    mock = MockWebServer()
    mock.start()
    api = GiteaApiImpl(GiteaServerPath(mock.url("/").toString()), HttpClient.newHttpClient(), { GiteaCredentials.AccessToken("t") }, "test",
                       GiteaServerMetadataCache()::getMetadata)
  }

  @AfterEach
  fun tearDown() = mock.close()

  private fun page(vararg prs: String) = MockResponse.Builder().code(200).body("[${prs.joinToString(",")}]").build()

  private fun pr(number: Long, head: String, headRepo: GiteaRepositoryPath, sha: String = "sha$number") =
    """{"id":$number,"number":$number,"title":"PR $number","state":"open",
       "head":{"ref":"$head","sha":"$sha","repo":{"id":1,"name":"${headRepo.name}","full_name":"$headRepo"}},"base":{"ref":"main","sha":"b"}}"""

  @Test
  fun `head branch must match in the head repository`() = runBlocking {
    mock.enqueue(page(pr(1, "feature", repo), pr(2, "feature", fork)))
    assertEquals(2L, api.findOpenPullRequestByHead(repo, fork, "feature", null)?.number)
  }

  @Test
  fun `detached head falls back to the head commit`() = runBlocking {
    mock.enqueue(page(pr(1, "feature", repo, sha = "abc"), pr(2, "other", repo)))
    assertEquals(1L, api.findOpenPullRequestByHead(repo, repo, null, "abc")?.number)
    mock.enqueue(page(pr(1, "feature", repo)))
    assertNull(api.findOpenPullRequestByHead(repo, repo, "nope", "nope"))
  }

  @Test
  fun `a push moves the tracked branch to the unchanged local head`() {
    fun state(revision: String, remote: String?) = BranchState("feature", revision, repo, "feature", remote)
    assertTrue(GiteaCurrentBranchPullRequestService.isPush(state("b", "a"), state("b", "b")), "pushed commits")
    assertTrue(GiteaCurrentBranchPullRequestService.isPush(state("b", null), state("b", "b")), "first push with upstream")
    assertFalse(GiteaCurrentBranchPullRequestService.isPush(state("a", "a"), state("b", "b")), "pull moves the local head too")
    assertFalse(GiteaCurrentBranchPullRequestService.isPush(state("b", "a"), state("b", "c")), "fetch of someone else's commits")
    assertFalse(GiteaCurrentBranchPullRequestService.isPush(state("b", "b"), state("b", "b")), "nothing changed")
    assertFalse(GiteaCurrentBranchPullRequestService.isPush(null, state("b", "b")))
  }

  @Test
  fun `sync status against the PR head`() = runBlocking {
    // history: a <- b (PR head) <- c (local commit); x diverged from a
    val parents = mapOf("b" to "a", "c" to "b", "x" to "a")
    val ancestor: suspend (String, String) -> Boolean = { older, newer ->
      generateSequence(newer) { parents[it] }.contains(older)
    }
    assertEquals(GitBranchSyncStatus.SYNCED, GiteaCurrentBranchPullRequestService.syncStatus("b", "b", ancestor))
    assertEquals(GitBranchSyncStatus(false, true), GiteaCurrentBranchPullRequestService.syncStatus("c", "b", ancestor), "local commits only")
    assertEquals(GitBranchSyncStatus(true, false), GiteaCurrentBranchPullRequestService.syncStatus("a", "b", ancestor), "behind")
    assertEquals(GitBranchSyncStatus(true, true), GiteaCurrentBranchPullRequestService.syncStatus("x", "b", ancestor), "diverged")
    assertNull(GiteaCurrentBranchPullRequestService.syncStatus(null, "b", ancestor))
  }

  @Test
  fun `a PR head git doesn't know yet counts as behind`() = runBlocking {
    val unknown: suspend (String, String) -> Boolean = { older, _ -> throw IllegalStateException("fatal: Not a valid commit name $older") }
    assertEquals(GitBranchSyncStatus(true, false), GiteaCurrentBranchPullRequestService.syncStatus("a", "b", unknown))
  }
}
