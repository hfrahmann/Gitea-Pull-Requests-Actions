package de.hendrikfrahmann.giteaplugin.pullrequest

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
import de.hendrikfrahmann.giteaplugin.api.request.GiteaCreateReviewRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaEditPullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaNewReviewComment
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReviewEvent
import de.hendrikfrahmann.giteaplugin.api.request.createIssueComment
import de.hendrikfrahmann.giteaplugin.api.request.createReview
import de.hendrikfrahmann.giteaplugin.api.request.editPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.api.request.requestReviewers
import de.hendrikfrahmann.giteaplugin.api.sendIgnoringBody
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestReviewData
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestTimeline
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestTimelineData
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaTimelineItem
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaTimelineItem.EventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.util.Base64

/**
 * Timeline against the dev Gitea on a throwaway PR in `pr-list-playground` (closed and its branch deleted at the end):
 * more than one page of items, combined label changes, title change, review request, a review with an inline thread,
 * edit and delete of a conversation comment. `./gradlew integrationTest`; skipped without gitea-test.local.env.
 */
@Tag("integration")
class GiteaTimelineIntegrationTest {
  private val env = GiteaTestEnv.load()
  private val repo = GiteaRepositoryPath("intellij-dev", "pr-list-playground")
  private fun api(token: String): GiteaApi = GiteaApiImpl(GiteaServerPath(env.getProperty("GITEA_URL")), HttpClient.newHttpClient(),
                                                          { GiteaCredentials.AccessToken(env.getProperty(token)) }, "Gitea-IntelliJ/test",
                                                          { it.loadServerMetadata() })
  private val dev = api("INTELLIJ_DEV_TOKEN")
  private val reviewer = api("INTELLIJ_REVIEWER_TOKEN")

  private data class NewFile(val branch: String, val new_branch: String, val message: String, val content: String)
  private data class NewPr(val title: String, val head: String, val base: String)
  private data class Title(val title: String)
  private data class Close(val state: String)

  private suspend fun GiteaPullRequestTimelineData.awaitState(predicate: (GiteaPullRequestTimeline) -> Boolean): GiteaPullRequestTimeline =
    withTimeout(15_000) { state.filterNotNull().first { it.getOrNull()?.let(predicate) == true }.getOrThrow() }

  @Test
  fun `timeline round trip`(): Unit = runBlocking {
    val branch = "probe/timeline-it-${System.currentTimeMillis()}"
    val content = Base64.getEncoder().encodeToString("line1\nline2\nline3\n".toByteArray())
    dev.sendIgnoringBody(dev.request(dev.server.repoApiUri(repo, "contents", "probe", "timeline-it.txt"))
                           .jsonBody("POST", NewFile("main", branch, "timeline it", content)).build())
    val pr = dev.loadJsonValue<GiteaPullRequestDTO>(dev.request(dev.server.repoApiUri(repo, "pulls"))
                                                       .jsonBody("POST", NewPr("Timeline IT", branch, "main")).build()).body
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      // label added and removed right away: Gitea's web UI shows nothing for it
      dev.editPullRequest(repo, pr.number, GiteaEditPullRequestRequest(labels = listOf(1)))
      dev.editPullRequest(repo, pr.number, GiteaEditPullRequestRequest(labels = emptyList()))
      dev.loadJsonValue<GiteaPullRequestDTO>(dev.request(dev.server.repoApiUri(repo, "pulls", pr.number.toString()))
                                               .jsonBody("PATCH", Title("Timeline IT (renamed)")).build())
      dev.requestReviewers(repo, pr.number, listOf("intellij-reviewer"))
      reviewer.createReview(repo, pr.number, GiteaCreateReviewRequest(GiteaReviewEvent.COMMENT, "looks fine", pr.head!!.sha,
                                                                      listOf(GiteaNewReviewComment("probe/timeline-it.txt", "nit", newPosition = 2))))
      repeat(55) { dev.createIssueComment(repo, pr.number, "comment $it") }

      val timelineData = GiteaPullRequestTimelineData(scope, dev, repo, pr.number)
      val reviewData = GiteaPullRequestReviewData(scope, dev, repo, pr.number, "intellij-dev")
      val timeline = timelineData.awaitState { true }
      val reviews = withTimeout(15_000) { reviewData.state.filterNotNull().first() }.getOrThrow()
      assertTrue(timeline.events.size > 50, "more than one page: ${timeline.events.size}")
      assertEquals(timeline.events.map { it.id }.sorted(), timeline.events.map { it.id }, "oldest first")

      val items = GiteaTimelineItem.build(timeline.events, reviews.reviews, reviews.threads, timeline.commitTitles)
      assertEquals((0 until 55).map { "comment $it" }, items.filterIsInstance<GiteaTimelineItem.Comment>().map { it.body })
      val kinds = items.filterIsInstance<GiteaTimelineItem.Event>().map { it.kind }
      assertTrue(kinds.none { it is EventKind.Labels }, "label add + remove cancel out: $kinds")
      assertTrue(EventKind.Title("Timeline IT", "Timeline IT (renamed)") in kinds, "$kinds")
      assertTrue(EventKind.ReviewRequests(listOf("intellij-reviewer"), emptyList()) in kinds, "$kinds")
      val push = kinds.filterIsInstance<EventKind.Pushed>().single()
      assertEquals("timeline it", push.commits.single().title)
      val review = items.filterIsInstance<GiteaTimelineItem.Review>().single { it.threads.isNotEmpty() }
      assertEquals("looks fine", review.body)
      assertEquals("nit", review.threads.single().root.body)
      assertTrue(review.threads.single().diffHunk.orEmpty().startsWith("@@"), "diff hunk: ${review.threads.single().diffHunk}")

      val first = items.filterIsInstance<GiteaTimelineItem.Comment>().first()
      timelineData.editComment(first.id, "edited")
      timelineData.awaitState { t -> t.events.any { it.id == first.id && it.body == "edited" } }
      timelineData.deleteComment(first.id)
      timelineData.awaitState { t -> t.events.none { it.id == first.id } }
    }
    finally {
      scope.cancel()
      dev.loadJsonValue<GiteaPullRequestDTO>(dev.request(dev.server.repoApiUri(repo, "pulls", pr.number.toString()))
                                               .jsonBody("PATCH", Close("closed")).build())
      dev.sendIgnoringBody(dev.request(dev.server.repoApiUri(repo, "branches", branch)).DELETE().build())
    }
  }
}
