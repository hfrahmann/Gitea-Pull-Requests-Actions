package de.hendrikfrahmann.giteaplugin.mcp

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerMetadataCache
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReviewEvent
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestReviewData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList

/** The MCP tool services against MockWebServer: setting gates, argument validation, requests and JSON shapes. */
class GiteaMcpServiceTest {
  private lateinit var mock: MockWebServer
  private lateinit var api: GiteaApi
  private val repository = GiteaRepositoryPath("johndoe", "demo")
  private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** `"METHOD /path"` (query left out, path relative to the repository's API URL) → response; anything else is 404. */
  private val routes = mutableMapOf<String, () -> MockResponse>()
  private val requests = CopyOnWriteArrayList<RecordedRequest>()

  private val env = object : GiteaMcpEnvironment {
    var tools = true
    var write = true
    var anonymous = false
    var reviewData: ((Long) -> GiteaPullRequestReviewData)? = null
    val changed = CopyOnWriteArrayList<Long>()

    override val toolsEnabled get() = tools
    override val writeToolsEnabled get() = tools && write

    override suspend fun repositories() = listOf(
      GiteaMcpRepository(repository, "http://gitea", "origin", "http://gitea/johndoe/demo.git", "/work/demo", true, "johndoe"))

    override suspend fun target(repository: String?): GiteaMcpTarget {
      if (repository != null && repository != this@GiteaMcpServiceTest.repository.toString()) {
        throw GiteaMcpException("$repository is not a Gitea repository of this project.")
      }
      return GiteaMcpTarget(api, this@GiteaMcpServiceTest.repository, anonymous, reviewData) { changed += it }
    }
  }

  private val prs = GiteaPullRequestsMcpService(env)
  private val actions = GiteaActionsMcpService(env)

  @BeforeEach
  fun setUp() {
    mock = MockWebServer()
    mock.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val key = "${request.method} ${request.target.substringBefore('?').removePrefix("/api/v1/repos/johndoe/demo")}"
        return routes[key]?.invoke() ?: MockResponse.Builder().code(404).body("""{"message":"no route $key"}""").build()
      }
    }
    mock.start()
    api = GiteaApiImpl(GiteaServerPath(mock.url("/").toString()), HttpClient.newHttpClient(), { GiteaCredentials.AccessToken("t") },
                       "Gitea-IntelliJ/test", GiteaServerMetadataCache()::getMetadata)
  }

  @AfterEach
  fun tearDown() {
    cs.cancel()
    mock.close()
  }

  private fun route(key: String, body: String, code: Int = 200) {
    routes[key] = { MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build() }
  }

  private fun parse(json: String): Map<*, *> = GiteaJson.fromJson(json, Map::class.java)

  private fun request(prefix: String): RecordedRequest = requests.first { "${it.method} ${it.target}".startsWith(prefix) }

  private val pr6 = """{"id":1,"number":6,"title":"Add feature","state":"open","user":{"id":1,"login":"janedoe"},
    "head":{"ref":"feature","sha":"abc","repo":{"id":2,"name":"demo","full_name":"johndoe/demo"}},"base":{"ref":"main","sha":"def"},
    "mergeable":true,"labels":[{"id":1,"name":"bug"}],"requested_reviewers":[{"id":3,"login":"maxmustermann"}]}"""

  // ---- gates ----

  @Test
  fun `every tool is refused while the tools are off`() {
    env.tools = false
    val read = assertThrows<GiteaMcpException> { runBlocking { prs.getPullRequest(null, 6) } }
    assertTrue(read.message!!.contains("Offer Gitea tools to the IDE's MCP server"), read.message)
    assertThrows<GiteaMcpException> { runBlocking { prs.listRepositories() } }
    assertThrows<GiteaMcpException> { runBlocking { actions.getRun(null, 1) } }
    assertThrows<GiteaMcpException> { runBlocking { prs.addComment(null, 6, "hi") } }
    assertTrue(requests.isEmpty())
  }

  @Test
  fun `write tools are refused without the second switch and on anonymous connections`() {
    env.write = false
    val off = assertThrows<GiteaMcpException> { runBlocking { prs.addComment(null, 6, "hi") } }
    assertTrue(off.message!!.contains("Also allow changing pull requests and runs"), off.message)
    assertThrows<GiteaMcpException> { runBlocking { actions.cancel(null, 3) } }
    assertThrows<GiteaMcpException> { runBlocking { prs.discardPendingReview(null, 6) } }

    env.write = true
    env.anonymous = true
    val anonymous = assertThrows<GiteaMcpException> { runBlocking { prs.mergePullRequest(null, 6, "merge", null, null, null, null, false) } }
    assertTrue(anonymous.message!!.contains("anonymous"), anonymous.message)
    assertThrows<GiteaMcpException> { runBlocking { actions.dispatch(null, "ci.yml", "main", emptyMap()) } }
    assertTrue(requests.isEmpty())
  }

  @Test
  fun `read tools work without the second switch`() = runBlocking {
    env.write = false
    route("GET /pulls/6", pr6)
    route("GET /pulls/6/reviews", "[]")
    assertEquals(6, parse(prs.getPullRequest(null, 6))["number"])
  }

  // ---- argument validation ----

  @Test
  fun `invalid arguments are explained before any request`() {
    assertThrows<GiteaMcpException> { runBlocking { prs.listPullRequests(null, "merged", null, null, emptyList(), false, 1, 20) } }
    assertThrows<GiteaMcpException> { runBlocking { prs.listPullRequests(null, "open", null, null, emptyList(), false, 0, 20) } }
    assertThrows<GiteaMcpException> { runBlocking { prs.listPullRequests(null, "open", null, null, emptyList(), false, 1, 51) } }
    assertThrows<GiteaMcpException> { runBlocking { prs.getPullRequest(null, 0) } }
    assertThrows<GiteaMcpException> { runBlocking { prs.addComment(null, 6, "  ") } }
    assertThrows<GiteaMcpException> { runBlocking { prs.submitReview(null, 6, "LGTM", "", emptyList()) } }
    assertThrows<GiteaMcpException> {
      runBlocking { prs.submitReview(null, 6, "COMMENT", "", listOf(GiteaMcpReviewComment("a.kt", 3, "MIDDLE", "x"))) }
    }
    assertThrows<GiteaMcpException> {
      runBlocking { prs.submitReview(null, 6, "COMMENT", "", listOf(GiteaMcpReviewComment("a.kt", 0, "RIGHT", "x"))) }
    }
    assertThrows<GiteaMcpException> { runBlocking { prs.mergePullRequest(null, 6, "octopus", null, null, null, null, false) } }
    assertThrows<GiteaMcpException> { runBlocking { prs.setPullRequestState(null, 6, "merged") } }
    assertThrows<GiteaMcpException> { runBlocking { actions.getJobLog(null, 5, null, 0, false) } }
    assertThrows<GiteaMcpException> { runBlocking { actions.rerun(null, 3, 4, true) } }
    assertThrows<GiteaMcpException> { runBlocking { actions.listRuns(null, null, 6, "abc", null, null, null, 1, 20) } }
    assertTrue(requests.isEmpty())
  }

  @Test
  fun `a COMMENT review without text or comments is refused`() {
    route("GET /pulls/6", pr6)
    val e = assertThrows<GiteaMcpException> { runBlocking { prs.submitReview(null, 6, "COMMENT", " ", emptyList()) } }
    assertTrue(e.message!!.contains("body"), e.message)
    assertTrue(requests.none { it.method == "POST" })
  }

  @Test
  fun `review events accept the usual spellings`() {
    assertEquals(GiteaReviewEvent.APPROVED, GiteaPullRequestsMcpService.reviewEvent("approve"))
    assertEquals(GiteaReviewEvent.REQUEST_CHANGES, GiteaPullRequestsMcpService.reviewEvent("request changes"))
    assertEquals(GiteaReviewEvent.COMMENT, GiteaPullRequestsMcpService.reviewEvent("COMMENT"))
  }

  @Test
  fun `an unknown repository is reported`() {
    val e = assertThrows<GiteaMcpException> { runBlocking { prs.getPullRequest("other/repo", 6) } }
    assertTrue(e.message!!.contains("other/repo"))
  }

  // ---- read tools ----

  @Test
  fun `repositories are listed with the connected one`() = runBlocking {
    val repo = (parse(prs.listRepositories())["repositories"] as List<*>).single() as Map<*, *>
    assertEquals("johndoe/demo", repo["repository"])
    assertEquals(true, repo["connected"])
    assertEquals(false, repo["anonymous"])
  }

  @Test
  fun `pull requests are listed compactly, searches go through the issue endpoint`() = runBlocking {
    route("GET /pulls", "[$pr6]")
    val list = parse(prs.listPullRequests(null, "open", null, "janedoe", emptyList(), false, 1, 20))
    val item = (list["pullRequests"] as List<*>).single() as Map<*, *>
    assertEquals(mapOf("number" to 6, "title" to "Add feature", "state" to "open", "author" to "janedoe", "head" to "feature", "base" to "main",
                       "labels" to listOf("bug"), "mergeable" to true), item)
    assertEquals(false, list["mayHaveMore"])
    assertTrue(request("GET /api/v1/repos/johndoe/demo/pulls?").target.contains("poster=janedoe"))

    route("GET /issues", """[{"id":1,"number":7,"title":"Fix","state":"open","pull_request":{"draft":true}}]""")
    val found = parse(prs.listPullRequests(null, "all", "crash", null, listOf("bug"), false, 1, 20))
    assertEquals(true, ((found["pullRequests"] as List<*>).single() as Map<*, *>)["draft"])
    val search = request("GET /api/v1/repos/johndoe/demo/issues?").target
    assertTrue(search.contains("type=pulls") && search.contains("q=crash") && search.contains("labels=bug") && search.contains("state=all"), search)
  }

  @Test
  fun `details carry the latest verdict of each reviewer`() = runBlocking {
    route("GET /pulls/6", pr6)
    route("GET /pulls/6/reviews", """[
      {"id":1,"state":"REQUEST_CHANGES","user":{"id":3,"login":"maxmustermann"}},
      {"id":2,"state":"APPROVED","user":{"id":3,"login":"maxmustermann"}},
      {"id":3,"state":"REQUEST_REVIEW","user":{"id":4,"login":"johndoe"}},
      {"id":4,"state":"PENDING","user":{"id":4,"login":"johndoe"}}]""")
    val details = parse(prs.getPullRequest(null, 6))
    assertEquals(listOf(mapOf("user" to "maxmustermann", "state" to "APPROVED")), details["reviews"])
    assertEquals(listOf("maxmustermann"), details["requestedReviewers"])
    assertEquals(mapOf("ref" to "feature", "sha" to "abc", "repository" to "johndoe/demo"), details["head"])
    assertNull(details["mergedBy"])
  }

  @Test
  fun `files are listed, one file's diff is cut out of the pull request diff`() = runBlocking {
    route("GET /pulls/6/files", """[{"filename":"a.kt","status":"modified","additions":2,"deletions":1}]""")
    val files = parse(prs.getPullRequestFiles(null, 6, null))
    assertEquals(listOf(mapOf("path" to "a.kt", "status" to "modified", "additions" to 2, "deletions" to 1)), files["files"])

    val diff = "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-x\n+y\ndiff --git a/b.kt b/b.kt\n+z\n"
    routes["GET /pulls/6.diff"] = { MockResponse.Builder().body(diff).build() }
    assertEquals("diff --git a/b.kt b/b.kt\n+z", parse(prs.getPullRequestFiles(null, 6, "b.kt"))["diff"])
    assertThrows<GiteaMcpException> { runBlocking { prs.getPullRequestFiles(null, 6, "c.kt") } }
    Unit
  }

  @Test
  fun `checks link Gitea Actions jobs of this repository`() = runBlocking {
    route("GET /pulls/6", pr6)
    route("GET /commits/abc/status", """{"state":"failure","statuses":[
      {"id":1,"status":"failure","context":"CI / build (push)","target_url":"/johndoe/demo/actions/runs/12/jobs/34"},
      {"id":2,"status":"success","context":"external","description":"","target_url":"https://ci.example.com/1"}]}""")
    val checks = parse(prs.getPullRequestChecks(null, 6))
    assertEquals("failure", checks["state"])
    val (actionsJob, external) = (checks["checks"] as List<*>).map { it as Map<*, *> }
    assertEquals(12, actionsJob["runId"])
    assertEquals(34, actionsJob["jobId"])
    assertNull(external["runId"])
    assertNull(external["description"])
  }

  @Test
  fun `review threads are built from the reviews' comments`() = runBlocking {
    route("GET /pulls/6/reviews", """[{"id":5,"state":"COMMENT","comments_count":2,"commit_id":"abc","user":{"id":3,"login":"maxmustermann"}}]""")
    route("GET /pulls/6/reviews/5/comments", """[
      {"id":50,"body":"Why?","path":"a.kt","position":3,"commit_id":"abc","user":{"id":3,"login":"maxmustermann"},
       "resolver":{"id":1,"login":"janedoe"},"created_at":"2026-11-01T10:00:00+01:00"},
      {"id":51,"body":"Because.","path":"a.kt","position":3,"commit_id":"abc","user":{"id":1,"login":"janedoe"},
       "created_at":"2026-11-01T11:00:00+01:00"}]""")
    val thread = (parse(prs.getReviewThreads(null, 6, true))["threads"] as List<*>).single() as Map<*, *>
    assertEquals(50, thread["id"])
    assertEquals("RIGHT", thread["side"])
    assertEquals(true, thread["resolved"])
    assertEquals(listOf("Why?", "Because."), (thread["comments"] as List<*>).map { (it as Map<*, *>)["body"] })
    assertEquals(emptyList<Any>(), parse(prs.getReviewThreads(null, 6, false))["threads"])
  }

  @Test
  fun `timeline without events keeps comments and reviews`() = runBlocking {
    routes["GET /issues/6/timeline"] = {
      val page = requests.last().target.substringAfter("page=").substringBefore('&')
      val body = when (page) {
        "1" -> """[{"id":1,"type":"comment","body":"Hi","user":{"id":1,"login":"janedoe"}},
                   {"id":2,"type":"label","label":{"id":1,"name":"bug"},"old_title":"","new_ref":"","ref_commit_sha":""},
                   {"id":3,"type":"pull_push","body":"{\"is_force_push\":false,\"commit_ids\":[\"abc\"]}"}]"""
        else -> "[]"
      }
      MockResponse.Builder().body(body).addHeader("Content-Type", "application/json").build()
    }
    val all = parse(prs.getPullRequestTimeline(null, 6, true))["timeline"] as List<*>
    assertEquals(listOf("comment", "label", "pull_push"), all.map { (it as Map<*, *>)["type"] })
    assertEquals(mapOf("id" to 2, "type" to "label", "label" to "bug"), all[1])
    assertEquals(listOf("abc"), (all[2] as Map<*, *>)["pushedCommits"])
    assertNull((all[2] as Map<*, *>)["body"])
    assertEquals(listOf("comment"), (parse(prs.getPullRequestTimeline(null, 6, false))["timeline"] as List<*>).map { (it as Map<*, *>)["type"] })
  }

  // ---- write tools ----

  @Test
  fun `a comment is posted and the pull request's views refresh`() = runBlocking {
    route("POST /issues/6/comments", """{"id":99,"body":"hi","html_url":"http://gitea/johndoe/demo/pulls/6#issuecomment-99"}""", 201)
    assertEquals(99, parse(prs.addComment(null, 6, "hi"))["commentId"])
    assertEquals("""{"body":"hi"}""", request("POST").body!!.utf8())
    assertEquals(listOf(6L), env.changed)
  }

  @Test
  fun `reactions are checked against the server's list`() = runBlocking {
    // not below the repository: the key keeps the whole path
    route("GET /api/v1/settings/ui", """{"allowed_reactions":["+1","heart"]}""")
    val e = assertThrows<GiteaMcpException> { runBlocking { prs.addReaction(null, 6, "rocket", null) } }
    assertTrue(e.message!!.contains("+1, heart"), e.message)
    route("POST /issues/comments/50/reactions", """{"content":"heart"}""", 201)
    assertEquals("heart", parse(prs.addReaction(null, 6, ":heart:", 50))["reaction"])
    assertEquals("""{"content":"heart"}""", request("POST").body!!.utf8())
  }

  @Test
  fun `a review outside the connected repository goes in one request with its inline comments`() = runBlocking {
    route("GET /pulls/6", pr6)
    route("POST /pulls/6/reviews", """{"id":7,"state":"REQUEST_CHANGES"}""")
    val result = parse(prs.submitReview(null, 6, "REQUEST_CHANGES", "Please fix",
                                        listOf(GiteaMcpReviewComment("a.kt", 3, "RIGHT", "new"), GiteaMcpReviewComment("/b.kt", 2, "left", "old"))))
    assertEquals(2, result["inlineComments"])
    val body = parse(request("POST").body!!.utf8())
    assertEquals("REQUEST_CHANGES", body["event"])
    assertEquals("abc", body["commit_id"])
    assertEquals(listOf(mapOf("path" to "a.kt", "body" to "new", "new_position" to 3), mapOf("path" to "b.kt", "body" to "old", "old_position" to 2)),
                 body["comments"])
  }

  @Test
  fun `review mode queues comments in the IDE's pending review and submits them together`() = runBlocking {
    route("GET /pulls/6", pr6)
    route("GET /pulls/6/reviews", "[]")
    route("POST /pulls/6/reviews", """{"id":7,"state":"COMMENT"}""")
    val reviewData = GiteaPullRequestReviewData(cs, api, repository, 6, "johndoe")
    env.reviewData = { reviewData }

    assertEquals(1, parse(prs.addPendingReviewComment(null, 6, GiteaMcpReviewComment("a.kt", 3, "RIGHT", "first")))["pendingComments"])
    val pending = parse(prs.listPendingReviewComments(null, 6))["comments"] as List<*>
    assertEquals(listOf(mapOf("id" to -1, "path" to "a.kt", "line" to 3, "side" to "RIGHT", "body" to "first", "onServer" to false)), pending)
    assertTrue(requests.none { it.method == "POST" }, "nothing is sent before the review is submitted")

    val result = parse(prs.submitReview(null, 6, "COMMENT", "", listOf(GiteaMcpReviewComment("b.kt", 1, "RIGHT", "second"))))
    assertEquals(2, result["inlineComments"])
    val body = parse(request("POST").body!!.utf8())
    assertEquals(listOf("first", "second"), (body["comments"] as List<*>).map { (it as Map<*, *>)["body"] })
    assertEquals(1, requests.count { it.method == "POST" })
  }

  @Test
  fun `review mode is only offered for the connected repository`() {
    env.reviewData = null
    val e = assertThrows<GiteaMcpException> { runBlocking { prs.listPendingReviewComments(null, 6) } }
    assertTrue(e.message!!.contains("giteapr_submit_review"), e.message)
  }

  @Test
  fun `drafts are set by the title prefix`() = runBlocking {
    route("GET /pulls/6", pr6)
    route("PATCH /pulls/6", pr6.replace("Add feature", "WIP: Add feature"), 201)
    assertEquals("WIP: Add feature", parse(prs.setPullRequestDraft(null, 6, true))["title"])
    assertEquals("""{"title":"WIP: Add feature"}""", request("PATCH").body!!.utf8())
  }

  @Test
  fun `merge sends the style and the expected head`() = runBlocking {
    routes["POST /pulls/6/merge"] = { MockResponse.Builder().code(200).build() }
    assertEquals(true, parse(prs.mergePullRequest(null, 6, "Squash", null, null, true, "abc", false))["merged"])
    assertEquals(mapOf("Do" to "squash", "delete_branch_after_merge" to true, "head_commit_id" to "abc"), parse(request("POST").body!!.utf8()))
  }

  // ---- Actions ----

  @Test
  fun `runs of a pull request are the runs of its head commit`() = runBlocking {
    route("GET /pulls/6", pr6)
    route("GET /actions/runs", """{"total_count":1,"workflow_runs":[{"id":12,"run_number":3,"path":"ci.yml@refs/heads/feature",
      "event":"push","status":"completed","conclusion":"failure","head_branch":"feature","head_sha":"abc"}]}""")
    val result = parse(actions.listRuns(null, null, 6, null, null, null, null, 1, 20))
    val run = (result["runs"] as List<*>).single() as Map<*, *>
    assertEquals(mapOf("id" to 12, "runNumber" to 3, "attempt" to 0, "workflow" to "ci.yml", "event" to "push", "status" to "completed",
                       "conclusion" to "failure", "branch" to "feature", "sha" to "abc"), run)
    assertTrue(request("GET /api/v1/repos/johndoe/demo/actions/runs?").target.contains("head_sha=abc"))
  }

  @Test
  fun `job logs come without colours and timestamps, the end by default`() = runBlocking {
    route("GET /actions/jobs/34", """{"id":34,"name":"build","status":"completed","conclusion":"failure"}""")
    val log = (1..5).joinToString("\n") { "2026-11-01T10:00:0$it.123Z \u001B[31mline $it\u001B[0m" } + "\n"
    routes["GET /actions/jobs/34/logs"] = { MockResponse.Builder().body(log).build() }
    val tail = parse(actions.getJobLog(null, 34, null, 2, false))
    assertEquals(mapOf("jobId" to 34, "name" to "build", "status" to "completed", "conclusion" to "failure", "totalLines" to 5, "fromLine" to 4,
                       "toLine" to 5, "log" to "line 4\nline 5"), tail)
    assertEquals("2026-11-01T10:00:01.123Z line 1", parse(actions.getJobLog(null, 34, 1, 1, true))["log"])
  }

  @Test
  fun `workflows tell whether and how they can be dispatched`() = runBlocking {
    // GET /repos/johndoe/demo itself
    route("GET ", """{"id":1,"name":"demo","full_name":"johndoe/demo","default_branch":"main"}""")
    route("GET /actions/workflows", """{"workflows":[{"id":"ci.yml","name":"CI","path":".gitea/workflows/ci.yml","state":"active"}]}""")
    routes["GET /raw/.gitea/workflows/ci.yml"] = {
      MockResponse.Builder().body("on:\n  workflow_dispatch:\n    inputs:\n      level:\n        type: choice\n        options: [a, b]\n").build()
    }
    val workflow = (parse(actions.listWorkflows(null, null))["workflows"] as List<*>).single() as Map<*, *>
    assertEquals(true, workflow["dispatchable"])
    assertEquals(listOf(mapOf("name" to "level", "required" to false, "type" to "choice", "options" to listOf("a", "b"))), workflow["inputs"])
    assertTrue(request("GET /api/v1/repos/johndoe/demo/raw/").target.endsWith("ref=main"))
  }

  @Test
  fun `re-runs pick the endpoint by their arguments`() = runBlocking {
    routes["POST /actions/runs/12/rerun-failed-jobs"] = { MockResponse.Builder().code(201).build() }
    routes["POST /actions/runs/12/jobs/34/rerun"] = { MockResponse.Builder().code(201).build() }
    assertEquals("failed jobs", parse(actions.rerun(null, 12, null, true))["rerun"])
    assertEquals("job", parse(actions.rerun(null, 12, 34, false))["rerun"])
  }

  @Test
  fun `log lines keep text that only looks like a timestamp later in the line`() {
    assertEquals(listOf("a 2026-11-01T10:00:00Z", ""), GiteaActionsMcpService.logLines("2026-11-01T10:00:00Z a 2026-11-01T10:00:00Z\n\n", false))
    assertEquals(emptyList<String>(), GiteaActionsMcpService.logLines("", false))
  }
}
