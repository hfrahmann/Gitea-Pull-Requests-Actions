package de.hendrikfrahmann.giteaplugin.pullrequest

import com.intellij.diff.util.Side
import de.hendrikfrahmann.giteaplugin.GiteaTestEnv
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.loadJsonValue
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReviewEvent
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.api.request.loadTimeline
import de.hendrikfrahmann.giteaplugin.api.sendIgnoringBody
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestReviewData
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestReviewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.util.Base64

/**
 * Review round trip against the dev Gitea on a throwaway PR in `pr-list-playground` (closed and its branch deleted at
 * the end): pending comments are drafts submitted in one review (one timeline entry), submit, reply, resolve, edit, delete, old-side comment, discard pending.
 * `./gradlew integrationTest`; skipped without gitea-test.local.env.
 */
@Tag("integration")
class GiteaReviewIntegrationTest {
  private val env = GiteaTestEnv.load()
  private val repo = GiteaRepositoryPath("intellij-dev", "pr-list-playground")
  private fun api(token: String): GiteaApi = GiteaApiImpl(GiteaServerPath(env.getProperty("GITEA_URL")), HttpClient.newHttpClient(),
                                                          { GiteaCredentials.AccessToken(env.getProperty(token)) }, "Gitea-IntelliJ/test",
                                                          { it.loadServerMetadata() })
  private val dev = api("INTELLIJ_DEV_TOKEN")
  private val reviewer = api("INTELLIJ_REVIEWER_TOKEN")

  private data class NewFile(val branch: String, val new_branch: String, val message: String, val content: String)
  private data class NewPr(val title: String, val head: String, val base: String)
  private data class Close(val state: String)

  private suspend fun GiteaPullRequestReviewData.awaitState(predicate: (GiteaPullRequestReviewState) -> Boolean): GiteaPullRequestReviewState =
    withTimeout(15_000) { state.filterNotNull().first { it.getOrNull()?.let(predicate) == true }.getOrThrow() }

  @Test
  fun `review round trip`() = runBlocking {
    val branch = "probe/review-it-${System.currentTimeMillis()}"
    val content = Base64.getEncoder().encodeToString("line1\nline2\nline3\nline4\n".toByteArray())
    dev.sendIgnoringBody(dev.request(dev.server.repoApiUri(repo, "contents", "probe", "review-it.txt"))
                           .jsonBody("POST", NewFile("main", branch, "review it", content)).build())
    val pr = dev.loadJsonValue<GiteaPullRequestDTO>(dev.request(dev.server.repoApiUri(repo, "pulls"))
                                                       .jsonBody("POST", NewPr("Review IT", branch, "main")).build()).body
    val head = pr.head!!.sha
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val data = GiteaPullRequestReviewData(scope, reviewer, repo, pr.number, "intellij-reviewer")
      data.awaitState { true }

      data.addComment("probe/review-it.txt", Side.RIGHT, 2, head, "pending one", pending = true)
      data.addComment("probe/review-it.txt", Side.RIGHT, 3, head, "pending two", pending = true)
      var state = data.awaitState { it.hasPendingReview && it.threads.size == 2 }
      assertEquals(0, state.reviews.count { it.state == "PENDING" }, "drafts stay local until submitted")
      assertTrue(state.threads.all { it.isPending })

      data.submitReview(GiteaReviewEvent.COMMENT, "looks fine", head)
      state = data.awaitState { !it.hasPendingReview && it.threads.size == 2 && it.threads.none { t -> t.isPending } }
      assertEquals(1, state.threads.map { it.reviewId }.distinct().size, "both comments in one review")
      assertEquals(1, dev.loadTimeline(repo, pr.number).count { it.type == "review" }, "one timeline entry per submitted review")
      val thread = state.threads.first { it.line == 2 }

      // the author answers and resolves
      val authorData = GiteaPullRequestReviewData(scope, dev, repo, pr.number, "intellij-dev")
      authorData.awaitState { it.threads.size == 2 }
      authorData.reply(thread, "thanks")
      authorData.setResolved(thread, true)
      state = authorData.awaitState { s -> s.threads.first { it.id == thread.id }.let { it.comments.size == 2 && it.isResolved } }
      assertEquals(listOf("pending one", "thanks"), state.threads.first { it.id == thread.id }.comments.map { it.body })

      data.editComment(thread.id, "edited")
      data.deleteComment(state.threads.first { it.line == 3 }.id)
      state = data.awaitState { s -> s.threads.size == 1 && s.threads.single().root.body == "edited" }

      // single comment on a removed line of a changed file: none in this PR, so on the old side of a new line is invalid –
      // use line 1 of the new file as single (non-pending) comment instead
      data.addComment("probe/review-it.txt", Side.RIGHT, 1, head, "single", pending = false)
      state = data.awaitState { s -> s.threads.any { it.root.body == "single" } }
      assertFalse(state.hasPendingReview)

      data.addComment("probe/review-it.txt", Side.RIGHT, 4, head, "to discard", pending = true)
      data.awaitState { it.hasPendingReview }
      data.discardPendingReview()
      state = data.awaitState { s -> !s.hasPendingReview && s.threads.none { it.root.body == "to discard" } }
    }
    finally {
      scope.cancel()
      dev.loadJsonValue<GiteaPullRequestDTO>(dev.request(dev.server.repoApiUri(repo, "pulls", pr.number.toString()))
                                               .jsonBody("PATCH", Close("closed")).build())
      dev.sendIgnoringBody(dev.request(dev.server.repoApiUri(repo, "branches", branch)).DELETE().build())
    }
  }
}
