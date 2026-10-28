package de.hendrikfrahmann.giteaplugin.authentication

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.expectThrows
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** OAuth flow against a fake Gitea; the "browser" follows the redirect to our loopback listener. */
class GiteaOAuthServiceTest : BasePlatformTestCase() {
  private lateinit var mock: MockWebServer
  private lateinit var server: GiteaServerPath
  private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  override fun setUp() {
    super.setUp()
    mock = MockWebServer()
    mock.start()
    server = GiteaServerPath(mock.url("/").toString())
  }

  override fun tearDown() {
    try {
      mock.close()
    }
    finally {
      super.tearDown()
    }
  }

  private fun service(browser: (URI) -> Unit) = GiteaOAuthService().apply {
    apiFactory = { GiteaApiImpl(it, httpClient, null, "test", { api -> api.loadServerMetadata() }) }
    this.browser = browser
  }

  private fun query(uri: URI) = LoopbackListener.parseQuery(uri.rawQuery)

  /** Simulates the browser after consent: GET redirect_uri with [params] (state taken from the request unless overridden). */
  private fun fakeBrowser(page: CompletableFuture<String>, params: (Map<String, String>) -> String): (URI) -> Unit = { authorizeUri ->
    val q = query(authorizeUri)
    CompletableFuture.runAsync {
      val target = URI.create(q.getValue("redirect_uri") + "?" + params(q))
      page.complete(httpClient.send(HttpRequest.newBuilder(target).build(), HttpResponse.BodyHandlers.ofString()).body())
    }
  }

  private fun tokenResponse(access: String = "at-1", refresh: String = "rt-1") =
    MockResponse.Builder().addHeader("Content-Type", "application/json")
      .body("""{"access_token":"$access","token_type":"bearer","expires_in":3600,"refresh_token":"$refresh"}""").build()

  fun `test authorization code flow with pkce`() {
    mock.enqueue(tokenResponse())
    val page = CompletableFuture<String>()
    var authorizeUri: URI? = null
    val service = service { uri ->
      authorizeUri = uri
      fakeBrowser(page) { q -> "code=the-code&state=${q["state"]}" }(uri)
    }

    val credentials = runBlocking { service.authorize(server, "my-client", timeout = 10.seconds) }

    assertEquals("at-1", credentials.accessToken)
    assertEquals("rt-1", credentials.refreshToken)
    assertEquals("my-client", credentials.clientId)
    assertTrue(credentials.isAccessTokenValid())
    assertTrue(page.get(10, TimeUnit.SECONDS).contains("logged in"))

    val q = query(authorizeUri!!)
    assertEquals("/login/oauth/authorize", authorizeUri.path)
    assertEquals("my-client", q["client_id"])
    assertEquals("code", q["response_type"])
    assertEquals("S256", q["code_challenge_method"])
    assertTrue(q.getValue("redirect_uri").matches(Regex("""http://127\.0\.0\.1:\d+/""")))

    val tokenRequest = mock.takeRequest()
    assertEquals("POST", tokenRequest.method)
    assertEquals("/login/oauth/access_token", tokenRequest.target)
    val body = GiteaJson.mapper.readTree(tokenRequest.body!!.utf8())
    assertEquals("authorization_code", body["grant_type"].asText())
    assertEquals("the-code", body["code"].asText())
    assertEquals("my-client", body["client_id"].asText())
    assertEquals(q["redirect_uri"], body["redirect_uri"].asText())
    assertEquals(q["code_challenge"], GiteaOAuthService.codeChallengeS256(body["code_verifier"].asText()))
  }

  fun `test state mismatch is rejected without token exchange`() {
    val page = CompletableFuture<String>()
    val service = service(fakeBrowser(page) { "code=c&state=forged" })
    val error = expectThrows<GiteaOAuthException> { runBlocking { service.authorize(server, "c", timeout = 10.seconds) } }
    assertTrue(error.message!!.contains("did not match"))
    assertTrue(page.get(10, TimeUnit.SECONDS).contains("failed"))
    assertEquals(0, mock.requestCount)
  }

  fun `test denied consent`() {
    val page = CompletableFuture<String>()
    val service = service(fakeBrowser(page) { q -> "error=access_denied&error_description=user+said+no&state=${q["state"]}" })
    val error = expectThrows<GiteaOAuthException> { runBlocking { service.authorize(server, "c", timeout = 10.seconds) } }
    assertTrue(error.message!!.contains("user said no"))
  }

  fun `test failed token exchange is reported`() {
    mock.enqueue(MockResponse.Builder().code(400).body("""{"error":"invalid_grant"}""").build())
    val page = CompletableFuture<String>()
    val service = service(fakeBrowser(page) { q -> "code=c&state=${q["state"]}" })
    expectThrows<GiteaOAuthException> { runBlocking { service.authorize(server, "c", timeout = 10.seconds) } }
    assertTrue(page.get(10, TimeUnit.SECONDS).contains("failed"))
  }

  fun `test refresh`() {
    mock.enqueue(tokenResponse("at-2", "rt-2"))
    val old = GiteaAccountCredentials.OAuth("at-1", "rt-1", "client", 0)
    val refreshed = runBlocking { service {}.refresh(server, old) }
    assertEquals("at-2", refreshed.accessToken)
    assertEquals("rt-2", refreshed.refreshToken)
    val body = GiteaJson.mapper.readTree(mock.takeRequest().body!!.utf8())
    assertEquals("refresh_token", body["grant_type"].asText())
    assertEquals("rt-1", body["refresh_token"].asText())
    assertEquals("client", body["client_id"].asText())
  }

  fun `test other paths on the listener get 404`() {
    LoopbackListener.start().use { listener ->
      val response = httpClient.send(HttpRequest.newBuilder(URI.create(listener.redirectUri + "favicon.ico")).build(),
                                     HttpResponse.BodyHandlers.discarding())
      assertEquals(404, response.statusCode())
      assertFalse(listener.callback.isCompleted)
    }
  }
}
