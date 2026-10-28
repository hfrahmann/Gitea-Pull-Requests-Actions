package de.hendrikfrahmann.giteaplugin.pullrequest

import com.intellij.diff.util.Side
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerMetadataCache
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReviewEvent
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestReviewData
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestTimelineData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Review comments are local drafts until the review is submitted in one request (Gitea adds a timeline entry per request).
 *
 * Comment changes finish and reload even when the caller is cancelled meanwhile (the comment field's inlay or editor was
 * rebuilt): before, the server had the comment but the reload was skipped – it never showed up and was sent again.
 */
class GiteaReviewDataTest {
  private lateinit var mock: MockWebServer
  private lateinit var api: GiteaApi
  private lateinit var scope: CoroutineScope
  private val repo = GiteaRepositoryPath("intellij-dev", "plugin-playground")

  @BeforeEach
  fun setUp() {
    mock = MockWebServer()
    mock.start()
    api = GiteaApiImpl(GiteaServerPath(mock.url("/").toString()), HttpClient.newHttpClient(), { GiteaCredentials.AccessToken("t") }, "test",
                       GiteaServerMetadataCache()::getMetadata)
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }

  @AfterEach
  fun tearDown() {
    scope.cancel()
    mock.close()
  }

  @Test
  fun `new review comment is loaded although its field went away`() = runBlocking {
    val posted = AtomicBoolean()
    mock.dispatcher = dispatcher { request ->
      val path = request.url.encodedPath
      when {
        request.method == "POST" -> json(REVIEW, delayed = true).also { posted.set(true) }
        path.endsWith("/reviews/7/comments") -> json("[$REVIEW_COMMENT]")
        path.endsWith("/reviews") -> json(if (posted.get()) {
          "[$REVIEW]"
        }
        else {
          "[]"
        })
        else -> MockResponse.Builder().code(404).build()
      }
    }
    val data = GiteaPullRequestReviewData(scope, api, repo, 1, "me")
    withTimeout(TIMEOUT) { data.state.first { it != null } }

    val field = scope.launch { data.addComment("src/Hello.kt", Side.RIGHT, 2, "abc", "typo", pending = false) }
    withTimeout(TIMEOUT) {
      while (!posted.get()) {
        delay(POLL_MS)
      }
    }
    field.cancel()

    val state = withTimeout(TIMEOUT) { data.state.first { it?.getOrNull()?.threads?.isNotEmpty() == true } }
    assertEquals(listOf("typo"), state!!.getOrThrow().threads.single().comments.map { it.body })
  }

  @Test
  fun `new timeline comment is loaded although its field went away`() = runBlocking {
    val posted = AtomicBoolean()
    mock.dispatcher = dispatcher { request ->
      val path = request.url.encodedPath
      when {
        request.method == "POST" -> json(COMMENT, delayed = true).also { posted.set(true) }
        path.endsWith("/timeline") && request.url.queryParameter("page") == "1" && posted.get() ->
          json("""[{"id":5,"type":"comment","body":"hi","user":{"id":1,"login":"me"}}]""")
        else -> json("[]")
      }
    }
    val data = GiteaPullRequestTimelineData(scope, api, repo, 1)
    withTimeout(TIMEOUT) { data.state.first { it != null } }

    val field = scope.launch { data.addComment("hi") }
    withTimeout(TIMEOUT) {
      while (!posted.get()) {
        delay(POLL_MS)
      }
    }
    field.cancel()

    val state = withTimeout(TIMEOUT) { data.state.first { it?.getOrNull()?.events?.isNotEmpty() == true } }
    assertEquals(listOf("hi"), state!!.getOrThrow().events.map { it.body })
  }

  @Test
  fun `review comments are drafts sent in one request on submit`() = runBlocking {
    val posts = CopyOnWriteArrayList<String>()
    mock.dispatcher = dispatcher { request ->
      val path = request.url.encodedPath
      when {
        request.method == "POST" -> json(REVIEW).also { posts += request.body!!.utf8() }
        path.endsWith("/reviews/7/comments") -> json("[$REVIEW_COMMENT]")
        path.endsWith("/reviews") -> json(if (posts.isEmpty()) {
          "[]"
        }
        else {
          "[$REVIEW]"
        })
        else -> MockResponse.Builder().code(404).build()
      }
    }
    val data = GiteaPullRequestReviewData(scope, api, repo, 1, "me")
    withTimeout(TIMEOUT) { data.state.first { it != null } }

    data.addComment("src/Hello.kt", Side.RIGHT, 2, "abc", "typo", pending = true)
    data.addComment("src/Hello.kt", Side.LEFT, 5, "abc", "gone?", pending = true)
    data.addComment("src/Hello.kt", Side.RIGHT, 9, "abc", "drop me", pending = true)
    var state = data.state.value!!.getOrThrow()
    assertTrue(posts.isEmpty(), "drafts stay local")
    assertEquals(3, state.draftCount)
    assertTrue(state.hasPendingReview && state.threads.all { it.isPending && it.id < 0 && it.root.author?.login == "me" })

    data.editComment(state.threads.first { it.line == 2 }.id, "typo!")
    data.deleteComment(state.threads.first { it.line == 9 }.id)
    state = data.state.value!!.getOrThrow()
    assertEquals(listOf("typo!", "gone?"), state.threads.sortedBy { it.line }.map { it.root.body })
    assertTrue(posts.isEmpty())

    data.submitReview(GiteaReviewEvent.COMMENT, "ok", "head")
    assertEquals(1, posts.size)
    val sent = posts.single()
    assertTrue(sent.contains("\"event\":\"COMMENT\"") && sent.contains("\"commit_id\":\"abc\"") && sent.contains("\"body\":\"ok\""), sent)
    assertTrue(sent.contains("\"new_position\":2") && sent.contains("typo!") && sent.contains("\"old_position\":5"), sent)
    state = data.state.value!!.getOrThrow()
    assertFalse(state.hasPendingReview)
    assertEquals(listOf("typo"), state.threads.map { it.root.body })
  }

  @Test
  fun `drafts survive a failed submit`() = runBlocking {
    mock.dispatcher = dispatcher { request ->
      if (request.method == "POST") {
        MockResponse.Builder().code(422).body("{}").build()
      }
      else {
        json("[]")
      }
    }
    val data = GiteaPullRequestReviewData(scope, api, repo, 1, "me")
    withTimeout(TIMEOUT) { data.state.first { it != null } }
    data.addComment("src/Hello.kt", Side.RIGHT, 2, "abc", "typo", pending = true)

    runCatching { data.submitReview(GiteaReviewEvent.APPROVED, "", "abc") }
    assertEquals(1, data.state.value!!.getOrThrow().draftCount)

    data.discardPendingReview()
    assertFalse(data.state.value!!.getOrThrow().hasPendingReview)
  }

  private fun dispatcher(respond: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
  }

  /** [delayed]: the answer comes late, so the caller can be cancelled while the request runs. */
  private fun json(body: String, delayed: Boolean = false) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
    .body(body).apply {
      if (delayed) {
        headersDelay(REQUEST_DELAY_MS, TimeUnit.MILLISECONDS)
      }
    }.build()

  private companion object {
    const val TIMEOUT = 10_000L
    const val REQUEST_DELAY_MS = 300L
    const val POLL_MS = 10L
    const val REVIEW = """{"id":7,"state":"COMMENT","user":{"id":1,"login":"me"},"comments_count":1}"""
    const val REVIEW_COMMENT = """{"id":11,"body":"typo","user":{"id":1,"login":"me"},"path":"src/Hello.kt","position":2,"commit_id":"abc"}"""
    const val COMMENT = """{"id":5,"body":"hi","user":{"id":1,"login":"me"}}"""
  }
}
