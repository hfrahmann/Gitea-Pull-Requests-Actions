package de.hendrikfrahmann.giteaplugin.api

import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.findPullRequestByMergeCommit
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentUser
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import kotlinx.coroutines.flow.toList
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
import org.junit.jupiter.api.assertThrows
import java.net.Authenticator
import java.net.http.HttpClient
import java.net.http.HttpHeaders

class GiteaApiTest {
  private lateinit var mock: MockWebServer
  private lateinit var server: GiteaServerPath
  private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

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

  private fun api(
    credentials: GiteaCredentials? = GiteaCredentials.AccessToken("secret-pat"),
    metadataCache: GiteaServerMetadataCache = GiteaServerMetadataCache(),
  ): GiteaApi = GiteaApiImpl(server, httpClient, credentials?.let { c -> { c } }, "Gitea-IntelliJ/test", metadataCache::getMetadata)

  private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse =
    MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json;charset=utf-8")
      .apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

  @Test
  fun `personal access token uses token scheme`() = runBlocking {
    mock.enqueue(json("""{"id":3,"login":"intellij-dev"}"""))
    val user = api().getCurrentUser()
    assertEquals("intellij-dev", user.login)

    val request = mock.takeRequest()
    assertEquals("/gitea/api/v1/user", request.target)
    assertEquals("token secret-pat", request.headers["Authorization"])
    assertEquals("Gitea-IntelliJ/test", request.headers["User-Agent"])
    assertEquals("application/json", request.headers["Accept"])
  }

  @Test
  fun `oauth token uses bearer scheme`() = runBlocking {
    mock.enqueue(json("""{"id":3,"login":"intellij-dev"}"""))
    api(GiteaCredentials.OAuthToken("oauth-at")).getCurrentUser()
    assertEquals("Bearer oauth-at", mock.takeRequest().headers["Authorization"])
  }

  @Test
  fun `unauthenticated client sends no authorization`() = runBlocking {
    mock.enqueue(json("""{"version":"28.1.0"}"""))
    assertEquals(GiteaVersion(28, 1, 0), api(credentials = null).loadServerMetadata().version)
    assertNull(mock.takeRequest().headers["Authorization"])
  }

  @Test
  fun `no credentials for urls outside the server`() = runBlocking {
    MockWebServer().use { other ->
      other.start()
      other.enqueue(json("""{"id":1,"login":"x"}"""))
      api().getJson<GiteaUserDTO>(other.url("/api/v1/user").toUri())
      assertNull(other.takeRequest().headers["Authorization"])
    }
  }

  @Test
  fun `redirects are followed and credentials dropped for another host`() = runBlocking {
    MockWebServer().use { other ->
      other.start()
      other.enqueue(MockResponse.Builder().body("zip-bytes").build())
      mock.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/gitea/api/v1/raw?sig=1").build())
      mock.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/download").toString()).build())

      val response = api().run {
        sendAndRead(request(server.apiUri("artifact")).GET().build()) { stream, _ -> stream.readAllBytes().decodeToString() }
      }
      assertEquals("zip-bytes", response.body)
      assertEquals(other.url("/download").toUri(), response.uri)
      assertEquals("token secret-pat", mock.takeRequest().headers["Authorization"])
      val sameHostRedirect = mock.takeRequest()
      assertEquals("/gitea/api/v1/raw?sig=1", sameHostRedirect.target)
      assertEquals("token secret-pat", sameHostRedirect.headers["Authorization"])
      assertNull(other.takeRequest().headers["Authorization"])
    }
  }

  @Test
  fun `writes to a moved repository are sent again to its new path, never to another host`() = runBlocking {
    // like Gitea for a transferred repository: 301 to the new owner's path
    mock.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/gitea/api/v1/repos/Public/demo/pulls").build())
    mock.enqueue(json("""{"number":7}""", 201))
    val api = api()
    val response = api.sendIgnoringBody(api.request(server.apiUri("repos", "admin", "demo", "pulls")).jsonBody("POST", mapOf("title" to "T")).build())
    assertEquals(201, response.statusCode)
    mock.takeRequest()
    val resent = mock.takeRequest()
    assertEquals("POST", resent.method)
    assertEquals("/gitea/api/v1/repos/Public/demo/pulls", resent.target)
    assertEquals("""{"title":"T"}""", resent.body?.utf8())
    assertEquals("token secret-pat", resent.headers["Authorization"])

    mock.enqueue(MockResponse.Builder().code(307).addHeader("Location", "https://elsewhere.example/pulls").build())
    val error = assertThrows<GiteaHttpStatusError> {
      runBlocking { api.sendIgnoringBody(api.request(server.apiUri("repos", "admin", "demo", "pulls")).jsonBody("POST", mapOf("title" to "T")).build()) }
    }
    assertEquals(307, error.statusCode)
  }

  @Test
  fun `errors are mapped by status with gitea message`() = runBlocking {
    val cases = listOf(
      401 to GiteaHttpStatusError.Kind.UNAUTHORIZED,
      403 to GiteaHttpStatusError.Kind.FORBIDDEN,
      404 to GiteaHttpStatusError.Kind.NOT_FOUND,
      409 to GiteaHttpStatusError.Kind.CONFLICT,
      405 to GiteaHttpStatusError.Kind.CONFLICT,
      422 to GiteaHttpStatusError.Kind.VALIDATION,
      500 to GiteaHttpStatusError.Kind.SERVER,
      418 to GiteaHttpStatusError.Kind.OTHER,
    )
    for ((code, kind) in cases) {
      mock.enqueue(json("""{"message":"msg $code","url":"https://one.intranet/api/swagger"}""", code))
      val error = assertThrows<GiteaHttpStatusError> { runBlocking { api().getCurrentUser() } }
      assertEquals(code, error.statusCode)
      assertEquals(kind, error.kind)
      assertEquals("msg $code", error.apiMessage)
      assertTrue(error.message!!.contains("GET "))
      assertFalse(error.message!!.contains("secret-pat"))
    }
    assertTrue(assertThrows<GiteaHttpStatusError> { throw GiteaHttpStatusError(401, null, "x") }.isGiteaUnauthorized)
  }

  @Test
  fun `401 without WWW-Authenticate is unauthorized with an authenticator`() = runBlocking {
    // like the IDE's client: with an authenticator the JDK throws an IOException for such a response
    val withAuthenticator = HttpClient.newBuilder().authenticator(object : Authenticator() {}).build()
    mock.enqueue(json("""{"message":"token is required"}""", 401))
    val error = assertThrows<GiteaHttpStatusError> {
      runBlocking { GiteaApiImpl(server, withAuthenticator, null, "Gitea-IntelliJ/test", GiteaServerMetadataCache()::getMetadata).getCurrentUser() }
    }
    assertTrue(error.isGiteaUnauthorized)
  }

  @Test
  fun `non json error body`() = runBlocking {
    mock.enqueue(MockResponse.Builder().code(502).body("<html>Bad Gateway</html>").build())
    val error = assertThrows<GiteaHttpStatusError> { runBlocking { api().getCurrentUser() } }
    assertEquals(GiteaHttpStatusError.Kind.SERVER, error.kind)
    assertNull(error.apiMessage)
  }

  @Test
  fun `pagination follows link header`() = runBlocking {
    mock.enqueue(json("""[{"id":1,"login":"a"},{"id":2,"login":"b"}]""", 200,
                      "X-Total-Count" to "3", "Link" to "<${mock.url("/gitea/api/v1/users?page=2&limit=2")}>; rel=\"next\",<x>; rel=\"last\""))
    mock.enqueue(json("""[{"id":3,"login":"c"}]""", 200,
                      "X-Total-Count" to "3", "Link" to "<${mock.url("/gitea/api/v1/users?page=1&limit=2")}>; rel=\"first\""))
    val api = api()
    val users = GiteaPagination.loadAllPages<GiteaUserDTO>(pageSize = 2) { page, limit ->
      api.getJsonList(server.apiUri("users").withQuery { page(page, limit) })
    }
    assertEquals(listOf("a", "b", "c"), users.map { it.login })
    assertEquals("/gitea/api/v1/users?page=1&limit=2", mock.takeRequest().target)
    assertEquals("/gitea/api/v1/users?page=2&limit=2", mock.takeRequest().target)
    assertEquals(2, mock.requestCount)
  }

  @Test
  fun `pagination uses total count when the server caps the page size`() = runBlocking {
    // Asked for 100, server sends 2 per page (MAX_RESPONSE_ITEMS), no Link header
    mock.enqueue(json("""[{"id":1,"login":"a"},{"id":2,"login":"b"}]""", 200, "X-Total-Count" to "3"))
    mock.enqueue(json("""[{"id":3,"login":"c"}]""", 200, "X-Total-Count" to "3"))
    val api = api()
    val pages = GiteaPagination.pagesFlow<GiteaUserDTO>(pageSize = 100) { page, limit ->
      api.getJsonList(server.apiUri("users").withQuery { page(page, limit) })
    }.toList()
    assertEquals(listOf(2, 1), pages.map { it.size })
    assertEquals(2, mock.requestCount)
  }

  @Test
  fun `pagination stops on short or empty page without headers`() = runBlocking {
    mock.enqueue(json("""[{"id":1,"login":"a"},{"id":2,"login":"b"}]"""))
    mock.enqueue(json("""[]"""))
    val api = api()
    val users = GiteaPagination.loadAllPages<GiteaUserDTO>(pageSize = 2) { page, limit ->
      api.getJsonList(server.apiUri("users").withQuery { page(page, limit) })
    }
    assertEquals(2, users.size)
    assertEquals(2, mock.requestCount)
  }

  @Test
  fun `null list body is an empty list`() = runBlocking {
    mock.enqueue(json("null"))
    assertEquals(emptyList<GiteaUserDTO>(), api().getJsonList<GiteaUserDTO>(server.apiUri("users")).body)
  }

  @Test
  fun `metadata is cached per server`() = runBlocking {
    mock.enqueue(json("""{"version":"28.1.0"}"""))
    val cache = GiteaServerMetadataCache()
    assertEquals(GiteaVersion(28, 1, 0), api(metadataCache = cache).getMetadata().version)
    assertEquals(GiteaVersion(28, 1, 0), api(metadataCache = cache).getMetadata().version)
    assertEquals(1, mock.requestCount)

    cache.invalidate(server)
    mock.enqueue(json("""{"version":"28.2.0"}"""))
    assertEquals(GiteaVersion(28, 2, 0), api(metadataCache = cache).getMetadata().version)
  }

  @Test
  fun `json body is sent with content type`() = runBlocking {
    mock.enqueue(MockResponse.Builder().code(204).build())
    val api = api()
    api.sendIgnoringBody(api.request(server.apiUri("x")).jsonBody("PUT", mapOf("data" to "v", "description" to null)).build())
    val request = mock.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("application/json", request.headers["Content-Type"])
    assertEquals("""{"data":"v"}""", request.body!!.utf8())
  }

  @Test
  fun `query builder encodes and repeats keys`() {
    val uri = server.apiUri("pulls").withQuery {
      "state" eq "open"
      "q" eq "a b&c"
      "labels" eq listOf(1, 2)
      "skip" eq null as String?
    }
    assertTrue(uri.toString().endsWith("/pulls?state=open&q=a%20b%26c&labels=1&labels=2"), uri.toString())
  }

  @Test
  fun `link header parsing`() {
    val links = GiteaResponse.parseLinkHeader("""<https://h/api/v1/x?page=2>; rel="next",<https://h/api/v1/x?page=5>; rel="last"""")
    assertEquals("https://h/api/v1/x?page=2", links["next"])
    assertEquals("https://h/api/v1/x?page=5", links["last"])
  }

  @Test
  fun `authorization is redacted in logs`() {
    val headers = HttpHeaders.of(mapOf("Authorization" to listOf("token secret-pat"), "Accept" to listOf("application/json"))) { _, _ -> true }
    val logged = redactHeaders(headers)
    assertFalse(logged.contains("secret-pat"))
    assertTrue(logged.contains("Authorization=***"))
    assertTrue(logged.contains("Accept=application/json"))
    assertFalse(GiteaCredentials.AccessToken("secret-pat").toString().contains("secret-pat"))
  }

  @Test
  fun `pull request by merge commit, null on 404`() = runBlocking {
    val repo = GiteaRepositoryPath("intellij-dev", "plugin-playground")
    mock.enqueue(json("""{"id":7,"number":2,"title":"squash","state":"closed","merged":true}"""))
    assertEquals(2L, api().findPullRequestByMergeCommit(repo, "c06313f")!!.number)
    assertEquals("/gitea/api/v1/repos/intellij-dev/plugin-playground/commits/c06313f/pull", mock.takeRequest().target)

    mock.enqueue(json("""{"message":"pull request does not exist","url":"x"}""", 404))
    assertNull(api().findPullRequestByMergeCommit(repo, "4aef9fa"))

    mock.enqueue(json("""{"message":"forbidden","url":"x"}""", 403))
    assertThrows<GiteaHttpStatusError> { runBlocking { api().findPullRequestByMergeCommit(repo, "4aef9fa") } }
    Unit
  }
}
