package de.hendrikfrahmann.giteaplugin.actions

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerMetadataCache
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.GiteaActionRunsFilter
import de.hendrikfrahmann.giteaplugin.api.request.artifactZipUri
import de.hendrikfrahmann.giteaplugin.api.request.dispatchWorkflow
import de.hendrikfrahmann.giteaplugin.api.request.download
import de.hendrikfrahmann.giteaplugin.api.request.getBranch
import de.hendrikfrahmann.giteaplugin.api.request.loadJobLogFrom
import de.hendrikfrahmann.giteaplugin.api.request.loadRunAttemptJobs
import de.hendrikfrahmann.giteaplugin.api.request.loadRuns
import de.hendrikfrahmann.giteaplugin.api.request.rerunJob
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
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path

/** Request shapes of the Actions endpoints against MockWebServer. */
class GiteaActionsApiTest {
  private lateinit var mock: MockWebServer
  private lateinit var server: GiteaServerPath
  private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
  private val repository = GiteaRepositoryPath("intellij-dev", "plugin-playground")

  @BeforeEach
  fun setUp() {
    mock = MockWebServer()
    mock.start()
    server = GiteaServerPath(mock.url("/gitea").toString())
  }

  @AfterEach
  fun tearDown() {
    mock.close()
  }

  private fun api(): GiteaApi = GiteaApiImpl(server, httpClient, { GiteaCredentials.AccessToken("secret-pat") }, "Gitea-IntelliJ/test",
                                             GiteaServerMetadataCache()::getMetadata)

  private fun json(body: String, code: Int = 200) =
    MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json;charset=utf-8").build()

  @Test
  fun `runs are filtered by branch and head sha, the list is wrapped`() = runBlocking {
    mock.enqueue(json("""{"total_count":1,"workflow_runs":[{"id":24,"path":"ci.yml@refs/pull/6/head","event":"pull_request",
      "pull_requests":[{"id":297,"number":6}]}]}"""))
    val result = api().loadRuns(repository, GiteaActionRunsFilter(branch = "feature/x", headSha = "abc"), 2, 30)
    assertEquals(1, result.workflowRuns.size)
    assertEquals(6L, result.workflowRuns[0].pullRequests.single().number)
    val request = mock.takeRequest()
    assertEquals("/gitea/api/v1/repos/intellij-dev/plugin-playground/actions/runs?branch=feature%2Fx&head_sha=abc&page=2&limit=30",
                 request.target)
  }

  @Test
  fun `a branch with a slash is read as path segments`() = runBlocking {
    mock.enqueue(json("""{"name":"feature/x","commit":{"id":"abc"}}"""))
    assertEquals("abc", api().getBranch(repository, "feature/x").commit?.id)
    assertEquals("/gitea/api/v1/repos/intellij-dev/plugin-playground/branches/feature/x", mock.takeRequest().target)
  }

  @Test
  fun `attempt jobs, job re-run and dispatch use the documented paths`() = runBlocking {
    mock.enqueue(json("""{"total_count":1,"jobs":[{"id":9,"name":"build","run_attempt":1,"steps":[{"number":0,"name":"Say hello"}]}]}"""))
    mock.enqueue(MockResponse.Builder().code(201).build())
    mock.enqueue(MockResponse.Builder().code(204).build())
    val api = api()

    val jobs = api.loadRunAttemptJobs(repository, 5, 1)
    assertEquals("Say hello", jobs.single().steps.single().name)
    assertEquals("/gitea/api/v1/repos/intellij-dev/plugin-playground/actions/runs/5/attempts/1/jobs", mock.takeRequest().target)

    api.rerunJob(repository, 5, 29)
    val rerun = mock.takeRequest()
    assertEquals("POST", rerun.method)
    assertEquals("/gitea/api/v1/repos/intellij-dev/plugin-playground/actions/runs/5/jobs/29/rerun", rerun.target)

    assertNull(api.dispatchWorkflow(repository, "ci.yml", "main", mapOf("dry-run" to "true")), "204: server without run details")
    val dispatch = mock.takeRequest()
    assertEquals("/gitea/api/v1/repos/intellij-dev/plugin-playground/actions/workflows/ci.yml/dispatches?return_run_details=true", dispatch.target)
    assertEquals("""{"ref":"main","inputs":{"dry-run":"true"}}""", dispatch.body?.utf8())

    // 28.1 with return_run_details
    mock.enqueue(json("""{"workflow_run_id":36,"run_url":"https://one.intranet/api/v1/repos/x/y/actions/runs/36"}"""))
    assertEquals(36L, api.dispatchWorkflow(repository, "inputs.yml", "main", emptyMap()))
  }

  @Test
  fun `job log is read incrementally with a byte range`() = runBlocking {
    val api = api()
    mock.enqueue(MockResponse.Builder().code(200).body("line 1\n").build())
    val whole = api.loadJobLogFrom(repository, 50, 0)!!
    assertEquals("line 1\n", whole.text)
    assertEquals(7L, whole.totalBytes)
    assertNull(mock.takeRequest().headers["Range"], "the first request reads everything")

    mock.enqueue(MockResponse.Builder().code(206).body("line 2\n").addHeader("Content-Range", "bytes 7-13/14").build())
    val next = api.loadJobLogFrom(repository, 50, 7)!!
    assertEquals("line 2\n", next.text)
    assertEquals(14L, next.totalBytes)
    assertFalse(next.isWhole)
    assertEquals("bytes=7-", mock.takeRequest().headers["Range"])

    mock.enqueue(MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */14").build())
    assertNull(api.loadJobLogFrom(repository, 50, 14), "416: nothing new")
    mock.takeRequest()

    // a server ignoring Range sends everything again
    mock.enqueue(MockResponse.Builder().code(200).body("line 1\nline 2\n").build())
    assertTrue(api.loadJobLogFrom(repository, 50, 7)!!.isWhole)
  }

  @Test
  fun `artifact download follows the signed redirect with credentials only on the same server`(@TempDir dir: Path) = runBlocking {
    val other = MockWebServer().apply { start() }
    try {
      mock.enqueue(MockResponse.Builder().code(302)
                     .addHeader("Location", "/gitea/api/v1/repos/intellij-dev/plugin-playground/actions/artifacts/12/zip/raw?sig=x").build())
      mock.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/blob").toString()).build())
      other.enqueue(MockResponse.Builder().code(200).body("PK-zip-bytes").addHeader("Content-Type", "application/zip").build())

      val api = api()
      var lastProgress = 0L
      val target = dir.resolve("build-info.zip")
      api.download(api.artifactZipUri(repository, 12), target) { read, _ -> lastProgress = read }

      assertEquals("PK-zip-bytes", Files.readString(target))
      assertEquals(12L, lastProgress)
      assertEquals("token secret-pat", mock.takeRequest().headers["Authorization"])
      assertEquals("token secret-pat", mock.takeRequest().headers["Authorization"])
      assertNull(other.takeRequest().headers["Authorization"], "credentials must not reach another host")
      assertFalse(Files.list(dir).anyMatch { it.fileName.toString().endsWith(".part") })
    }
    finally {
      other.close()
    }
  }

  @Test
  fun `failed download leaves no file behind`(@TempDir dir: Path) = runBlocking {
    mock.enqueue(json("""{"message":"artifact not found"}""", 404))
    val api = api()
    val target = dir.resolve("gone.zip")
    runCatching { api.download(api.artifactZipUri(repository, 99), target) }
    assertFalse(Files.exists(target))
    assertEquals(0L, Files.list(dir).count())
  }
}
