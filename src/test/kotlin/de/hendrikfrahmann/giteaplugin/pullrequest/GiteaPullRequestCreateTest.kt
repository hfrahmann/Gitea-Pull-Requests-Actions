package de.hendrikfrahmann.giteaplugin.pullrequest

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerMetadataCache
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.GiteaCreatePullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.findOpenPullRequest
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestCreateDefaults
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
import java.net.URLDecoder
import java.net.http.HttpClient

/** Creating a PR without a real server: defaults for title/description/draft, request JSON, finding an existing PR. */
class GiteaPullRequestCreateTest {
  private lateinit var mock: MockWebServer
  private lateinit var api: GiteaApi
  private val repo = GiteaRepositoryPath("intellij-dev", "plugin-playground")

  @BeforeEach
  fun setUp() {
    mock = MockWebServer()
    mock.start()
    api = GiteaApiImpl(GiteaServerPath(mock.url("/").toString()), HttpClient.newHttpClient(), { GiteaCredentials.AccessToken("t") }, "test",
                       GiteaServerMetadataCache()::getMetadata)
  }

  @AfterEach
  fun tearDown() = mock.close()

  @Test
  fun `title is the single commit's subject, else the branch`() {
    assertEquals("Fix the greeting", GiteaPullRequestCreateDefaults.title(listOf("Fix the greeting\n\nBecause."), "feature/x"))
    assertEquals("feature/x", GiteaPullRequestCreateDefaults.title(listOf("One", "Two"), "feature/x"))
    assertEquals("feature/x", GiteaPullRequestCreateDefaults.title(emptyList(), "feature/x"))
  }

  @Test
  fun `description is the template, else the single commit's body`() {
    assertEquals("Because.\nReally.", GiteaPullRequestCreateDefaults.description(listOf("Subject\n\nBecause.\nReally.\n"), null))
    assertEquals("## What", GiteaPullRequestCreateDefaults.description(listOf("Subject\n\nBody"), "## What"))
    assertEquals("", GiteaPullRequestCreateDefaults.description(listOf("One", "Two"), " "))
  }

  @Test
  fun `draft prefix`() {
    assertTrue(GiteaPullRequestCreateDefaults.isDraft("WIP: x"))
    assertTrue(GiteaPullRequestCreateDefaults.isDraft("[wip] x"))
    assertFalse(GiteaPullRequestCreateDefaults.isDraft("Wipe the cache"))
    assertEquals("WIP: x", GiteaPullRequestCreateDefaults.withDraft("[WIP] x", true))
    assertEquals("x", GiteaPullRequestCreateDefaults.withDraft("WIP: x", false))
  }

  @Test
  fun `templates are looked up in gitea's order`() {
    assertEquals(listOf("PULL_REQUEST_TEMPLATE.md", "pull_request_template.md", ".gitea/PULL_REQUEST_TEMPLATE.md",
                        ".gitea/pull_request_template.md", ".github/PULL_REQUEST_TEMPLATE.md", ".github/pull_request_template.md"),
                 GiteaPullRequestCreateDefaults.TEMPLATE_PATHS)
  }

  @Test
  fun `template is the first existing file of the default branch`() = runBlocking {
    mock.enqueue(response(404, """{"message":"not found"}"""))
    mock.enqueue(response(404, """{"message":"not found"}"""))
    mock.enqueue(response(200, "## Summary\n"))
    assertEquals("## Summary\n", GiteaPullRequestCreateDefaults.loadTemplate(api, repo, "main"))
    mock.takeRequest(); mock.takeRequest()
    assertEquals("/api/v1/repos/intellij-dev/plugin-playground/raw/.gitea/PULL_REQUEST_TEMPLATE.md?ref=main", mock.takeRequest().target)
  }

  @Test
  fun `create request leaves out empty metadata`() {
    val json = String(GiteaJson.toJsonBytes(GiteaCreatePullRequestRequest("T", null, "dev:feature", "main", labels = listOf(3))))
    assertEquals("""{"title":"T","head":"dev:feature","base":"main","assignees":[],"reviewers":[],"labels":[3]}""", json)
  }

  @Test
  fun `existing PR - a closed answer of the direct lookup falls back to the open PRs into the base`() = runBlocking {
    mock.enqueue(response(200, pr(7, "closed", "feature/x", "intellij-dev/plugin-playground")))
    mock.enqueue(response(200, "[${pr(8, "open", "feature/y", "intellij-dev/plugin-playground")}," +
                                                "${pr(9, "open", "feature/x", "intellij-reviewer/plugin-playground")}," +
                                                "${pr(10, "open", "feature/x", "intellij-dev/plugin-playground")}]"))
    val found = api.findOpenPullRequest(repo, "main", repo, "feature/x")
    assertEquals(10L, found?.number)
    assertEquals("/api/v1/repos/intellij-dev/plugin-playground/pulls/main/intellij-dev%3Afeature%2Fx", mock.takeRequest().target)
    val list = URLDecoder.decode(mock.takeRequest().target, Charsets.UTF_8)
    assertTrue("base_branch=main" in list && "state=open" in list, list)
  }

  @Test
  fun `existing PR - none for unknown branches`() = runBlocking {
    mock.enqueue(response(404, """{"message":"not found"}"""))
    assertNull(api.findOpenPullRequest(repo, "main", repo, "feature/x"))
  }

  private fun response(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

  private fun pr(number: Long, state: String, head: String, headRepo: String) =
    """{"id":$number,"number":$number,"title":"PR $number","state":"$state",
       "head":{"ref":"$head","sha":"abc","repo":{"id":1,"name":"plugin-playground","full_name":"$headRepo"}},
       "base":{"ref":"main","sha":"def"}}"""
}
