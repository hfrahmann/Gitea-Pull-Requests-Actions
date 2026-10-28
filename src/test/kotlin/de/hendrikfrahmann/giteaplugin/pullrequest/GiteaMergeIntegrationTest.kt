package de.hendrikfrahmann.giteaplugin.pullrequest

import de.hendrikfrahmann.giteaplugin.GiteaTestEnv
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.loadJsonValue
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.request.GiteaEditPullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaMergePullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaMergeStyle
import de.hendrikfrahmann.giteaplugin.api.request.countCommitsBetween
import de.hendrikfrahmann.giteaplugin.api.request.editPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.getBranch
import de.hendrikfrahmann.giteaplugin.api.request.getPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.api.request.mergePullRequest
import de.hendrikfrahmann.giteaplugin.api.request.updatePullRequestBranch
import de.hendrikfrahmann.giteaplugin.api.sendIgnoringBody
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestMergeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.util.Base64

/**
 * Merging, closing and updating against the dev Gitea on throwaway PRs in `pr-list-playground`: every merge style, `head_commit_id` guard,
 * behind + update branch, close/reopen and the WIP draft prefix. `./gradlew integrationTest`.
 */
@Tag("integration")
class GiteaMergeIntegrationTest {
  private val env = GiteaTestEnv.load()
  private val repo = GiteaRepositoryPath("intellij-dev", "pr-list-playground")
  private val dev: GiteaApi = GiteaApiImpl(GiteaServerPath(env.getProperty("GITEA_URL")), HttpClient.newHttpClient(),
                                           { GiteaCredentials.AccessToken(env.getProperty("INTELLIJ_DEV_TOKEN")) }, "Gitea-IntelliJ/test",
                                           { it.loadServerMetadata() })
  private val stamp = System.currentTimeMillis()

  private data class NewFile(val branch: String, val new_branch: String?, val message: String, val content: String)
  private data class NewPr(val title: String, val head: String, val base: String)

  private suspend fun commit(branch: String, newBranch: String?, name: String) {
    val content = Base64.getEncoder().encodeToString("$name $stamp\n".toByteArray())
    dev.sendIgnoringBody(dev.request(dev.server.repoApiUri(repo, "contents", "probe", "merge-it-$stamp-$name.txt"))
                           .jsonBody("POST", NewFile(branch, newBranch, "merge it $name", content)).build())
  }

  private suspend fun newPr(name: String): GiteaPullRequestDTO {
    val branch = "probe/merge-it-$stamp-$name"
    commit("main", branch, name)
    val pr = dev.loadJsonValue<GiteaPullRequestDTO>(dev.request(dev.server.repoApiUri(repo, "pulls"))
                                                       .jsonBody("POST", NewPr("Merge IT $name", branch, "main")).build()).body
    // Gitea checks mergeability in the background ("Please try again later" until done)
    repeat(20) {
      if (dev.getPullRequest(repo, pr.number).mergeable != null) {
        return pr
      }
      delay(300)
    }
    return pr
  }

  private suspend fun mergeWithRetry(pr: GiteaPullRequestDTO, request: GiteaMergePullRequestRequest): Boolean {
    repeat(10) {
      try {
        return dev.mergePullRequest(repo, pr.number, request)
      }
      catch (e: GiteaHttpStatusError) {
        if (e.statusCode != 405 || e.apiMessage?.contains("try again") != true) {
          throw e
        }
        delay(500)
      }
    }
    error("Gitea kept asking to try again")
  }

  @Test
  fun `every merge style merges and deletes the branch`() = runBlocking {
    for (style in GiteaMergeStyle.entries) {
      val pr = newPr(style.value)
      val head = dev.getPullRequest(repo, pr.number).head!!.sha
      assertTrue(mergeWithRetry(pr, GiteaMergePullRequestRequest(style.value, "Merge IT ${style.value}", null, true, head)), style.value)
      val merged = dev.getPullRequest(repo, pr.number)
      assertTrue(merged.merged, style.value)
      assertTrue(merged.head!!.ref.startsWith("refs/pull/"), "head branch deleted: ${merged.head.ref}")
    }
  }

  @Test
  fun `stale head is refused, behind branch is updated, state and draft change`() = runBlocking {
    val pr = newPr("guard")
    try {
      val stale = assertThrows(GiteaHttpStatusError::class.java) {
        runBlocking { mergeWithRetry(pr, GiteaMergePullRequestRequest("merge", headCommitId = "0".repeat(40))) }
      }
      assertEquals(409, stale.statusCode)

      commit("main", null, "base-moves")
      var current = dev.getPullRequest(repo, pr.number)
      assertTrue(current.mergeBase != current.base!!.sha, "merge base stays behind the base tip")
      assertTrue(dev.countCommitsBetween(repo, current.mergeBase!!, current.base.sha) >= 1)
      dev.updatePullRequestBranch(repo, pr.number, rebase = false)
      // verified: `merge_base` follows about a second after the update
      repeat(20) {
        current = dev.getPullRequest(repo, pr.number)
        if (current.mergeBase == current.base!!.sha) {
          return@repeat
        }
        delay(300)
      }
      assertEquals(current.base.sha, current.mergeBase)
      assertEquals(0, dev.countCommitsBetween(repo, current.mergeBase, current.base.sha))

      val draft = dev.editPullRequest(repo, pr.number, GiteaEditPullRequestRequest(title = GiteaPullRequestMergeViewModel.draftTitle(current.title, true)))
      assertTrue(draft.draft)
      val ready = dev.editPullRequest(repo, pr.number, GiteaEditPullRequestRequest(title = GiteaPullRequestMergeViewModel.draftTitle(draft.title, false)))
      assertFalse(ready.draft)
      assertEquals("closed", dev.editPullRequest(repo, pr.number, GiteaEditPullRequestRequest(state = "closed")).state)
      assertEquals("open", dev.editPullRequest(repo, pr.number, GiteaEditPullRequestRequest(state = "open")).state)

      val main = dev.getBranch(repo, "main")
      assertTrue(main.userCanMerge)
    }
    finally {
      dev.editPullRequest(repo, pr.number, GiteaEditPullRequestRequest(state = "closed"))
      dev.sendIgnoringBody(dev.request(dev.server.repoApiUri(repo, "branches", "probe/merge-it-$stamp-guard")).DELETE().build())
    }
  }
}
