package de.hendrikfrahmann.giteaplugin.authentication

import de.hendrikfrahmann.giteaplugin.GiteaTestEnv
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentUser
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

/**
 * Full OAuth flow with the built-in `tea` app against the dev Gitea. The "browser" logs in as `intellij-reviewer`
 * with a cookie session and grants consent via the form, like a user would. `./gradlew integrationTest`
 */
@Tag("integration")
class GiteaOAuthIntegrationTest {
  private val env = GiteaTestEnv.load()
  private val server = GiteaServerPath(env.getProperty("GITEA_URL"))
  private val apiClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
  private val browser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  // Manual cookie jar: Gitea marks its session cookie `Secure` when ROOT_URL is https (as in the dev stack),
  // and java.net.CookieManager never sends Secure cookies over http – browsers do for 127.0.0.1.
  private val cookies = mutableMapOf<String, String>()

  private fun send(builder: HttpRequest.Builder): HttpResponse<String> {
    if (cookies.isNotEmpty()) {
      builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }
    val response = browser.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    response.headers().allValues("Set-Cookie").forEach { cookie ->
      val (name, value) = cookie.substringBefore(';').split('=', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" } }
      cookies[name] = value
    }
    return response
  }

  private fun form(vararg fields: Pair<String, String>) =
    HttpRequest.BodyPublishers.ofString(fields.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, Charsets.UTF_8) })

  private fun post(uri: URI, vararg fields: Pair<String, String>): HttpResponse<String> =
    send(HttpRequest.newBuilder(uri).header("Content-Type", "application/x-www-form-urlencoded").POST(form(*fields)))

  private fun get(uri: URI): HttpResponse<String> = send(HttpRequest.newBuilder(uri))

  /** Logs in, opens the authorize URL, grants consent if asked, then follows the redirect to our listener. */
  private fun actAsUser(authorizeUri: URI) {
    val login = post(server.webUri("user", "login"), "user_name" to "intellij-reviewer", "password" to env.getProperty("INTELLIJ_REVIEWER_PASSWORD"))
    assertEquals(303, login.statusCode(), "login failed")

    var response = get(authorizeUri)
    if (response.statusCode() == 200) {
      // Consent page: the grant form carries the request parameters as hidden fields
      val hidden = Regex("""<input type="hidden" name="([a-z_]+)" value="([^"]*)">""").findAll(response.body())
        .associate { it.groupValues[1] to it.groupValues[2].replace("&amp;", "&") }
      response = post(server.webUri("login", "oauth", "grant"), *(hidden + ("granted" to "true")).toList().toTypedArray())
    }
    val redirect = response.headers().firstValue("Location").orElseThrow { AssertionError("no redirect: ${response.statusCode()}") }
    assertEquals(true, redirect.startsWith("http://127.0.0.1:"), redirect)
    get(URI.create(redirect))
  }

  @Test
  fun `oauth login with tea app and refresh`() {
    var browserRun: CompletableFuture<Void>? = null
    val service = GiteaOAuthService().apply {
      apiFactory = { GiteaApiImpl(it, apiClient, null, "test", { api -> api.loadServerMetadata() }) }
      browser = { uri -> browserRun = CompletableFuture.runAsync { actAsUser(uri) } }
    }

    val credentials = try {
      runBlocking { service.authorize(server, GiteaOAuthService.BUILT_IN_CLIENT_ID, timeout = 30.seconds) }
    }
    catch (e: Exception) {
      // Show why the simulated browser didn't get to the redirect
      browserRun?.get(1, java.util.concurrent.TimeUnit.SECONDS)
      throw e
    }
    val api = GiteaApiImpl(server, apiClient, { credentials.toApiCredentials() }, "test", { it.loadServerMetadata() })
    assertEquals("intellij-reviewer", runBlocking { api.getCurrentUser() }.login)

    // Never print tokens in assertion messages. Access tokens are JWTs with second resolution, so a refresh within
    // the same second may legitimately return the same string – just check that the refreshed token works.
    val refreshed = runBlocking { service.refresh(server, credentials) }
    assertTrue(refreshed.accessToken.isNotEmpty() && refreshed.refreshToken.isNotEmpty(), "refresh returned empty tokens")
    assertTrue(refreshed.isAccessTokenValid(), "refreshed token already expired")
    val refreshedApi = GiteaApiImpl(server, apiClient, { refreshed.toApiCredentials() }, "test", { it.loadServerMetadata() })
    assertEquals("intellij-reviewer", runBlocking { refreshedApi.getCurrentUser() }.login)
  }
}
