package de.hendrikfrahmann.giteaplugin.authentication

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.text.StringUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.loadJsonValue
import de.hendrikfrahmann.giteaplugin.api.withQuery
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.withTimeout
import org.jetbrains.annotations.Nls
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val LOG = logger<GiteaOAuthService>()

/**
 * OAuth2 authorization-code flow with PKCE (S256) against Gitea's `/login/oauth/authorize` and `/login/oauth/access_token`.
 *
 * The redirect goes to a one-shot listener on `http://127.0.0.1:<random port>/`: Gitea's built-in `tea` app only
 * accepts loopback redirect URIs **without path** and not `localhost`, so the IDE's built-in web server (path-based)
 * can't receive it.
 */
@Service(Service.Level.APP)
class GiteaOAuthService {
  // Replaceable in tests
  internal var apiFactory: (GiteaServerPath) -> GiteaApi = { GiteaApiManager.getInstance().getUnauthenticatedClient(it) }
  internal var browser: (URI) -> Unit = { BrowserUtil.browse(it) }

  /**
   * Opens the browser for consent and waits (cancellable, up to [timeout]) for the redirect.
   *
   * @throws GiteaOAuthException if the user denies, `state` doesn't match or the token exchange fails
   */
  suspend fun authorize(
    server: GiteaServerPath,
    clientId: String = GiteaSettings.getInstance().oauthClientIdFor(server),
    timeout: Duration = 5.minutes,
  ): GiteaAccountCredentials.OAuth {
    val pkce = Pkce(codeVerifier = randomToken(PKCE_VERIFIER_BYTES), state = randomToken())
    LoopbackListener.start().use { listener ->
      browser(authorizeUri(server, clientId, listener.redirectUri, pkce))
      val callback = withTimeout(timeout) { listener.callback.await() }
      val result = runCatching { exchangeCallback(server, clientId, listener.redirectUri, pkce, callback) }
      // the browser tab waits for this, so it can say whether the login worked
      listener.respond(result.isSuccess, result.exceptionOrNull()?.localizedMessage)
      return result.getOrElse { throw it.asOAuthException() }
    }
  }

  /** `grant_type=refresh_token`; Gitea rotates the refresh token, so store the whole result. */
  suspend fun refresh(server: GiteaServerPath, credentials: GiteaAccountCredentials.OAuth): GiteaAccountCredentials.OAuth =
    exchange(server, credentials.clientId, mapOf("grant_type" to "refresh_token", "refresh_token" to credentials.refreshToken))

  private fun authorizeUri(server: GiteaServerPath, clientId: String, redirectUri: String, pkce: Pkce): URI =
    server.webUri("login", "oauth", "authorize").withQuery {
      "client_id" eq clientId
      "redirect_uri" eq redirectUri
      "response_type" eq "code"
      "code_challenge" eq codeChallengeS256(pkce.codeVerifier)
      "code_challenge_method" eq "S256"
      "state" eq pkce.state
    }

  /** Checks the redirect's query parameters and exchanges its `code` for tokens. */
  private suspend fun exchangeCallback(server: GiteaServerPath, clientId: String, redirectUri: String, pkce: Pkce,
                                       callback: Map<String, String>): GiteaAccountCredentials.OAuth {
    callbackProblem(callback, pkce)?.let { throw GiteaOAuthException(it) }
    val code = callback.getValue("code")
    val params = mapOf("grant_type" to "authorization_code", "code" to code, "code_verifier" to pkce.codeVerifier, "redirect_uri" to redirectUri)
    return exchange(server, clientId, params)
  }

  private suspend fun exchange(server: GiteaServerPath, clientId: String, params: Map<String, String>): GiteaAccountCredentials.OAuth {
    val api = apiFactory(server)
    val request = api.request(server.webUri("login", "oauth", "access_token"))
      .jsonBody("POST", params + ("client_id" to clientId))
      .build()
    val dto = api.loadJsonValue<GiteaOAuthTokenDTO>(request, "POST /login/oauth/access_token").body
    val refreshToken = dto.refreshToken ?: throw GiteaOAuthException(GiteaBundle.message("login.oauth.error.no.refresh"))
    val expiresAt = System.currentTimeMillis() / 1000 + (dto.expiresIn ?: DEFAULT_EXPIRES_IN)
    return GiteaAccountCredentials.OAuth(dto.accessToken, refreshToken, clientId, expiresAt)
  }

  /** Why the redirect can't be used (wrong `state`, consent denied, no `code`), `null` if it can. */
  private fun callbackProblem(callback: Map<String, String>, pkce: Pkce): @Nls String? {
    val error = callback["error"]
    return when {
      callback["state"] != pkce.state -> GiteaBundle.message("login.oauth.error.state")
      error != null -> GiteaBundle.message("login.oauth.error.denied", callback["error_description"] ?: error)
      callback["code"].isNullOrEmpty() -> GiteaBundle.message("login.oauth.error.no.code")
      else -> null
    }
  }

  private fun Throwable.asOAuthException(): GiteaOAuthException =
    this as? GiteaOAuthException ?: GiteaOAuthException(GiteaBundle.message("login.oauth.error.exchange", localizedMessage ?: javaClass.simpleName), this)

  /** The secrets of one authorization: the PKCE code verifier and the `state` that the redirect must echo. */
  private class Pkce(val codeVerifier: String, val state: String)

  companion object {
    /** Gitea's built-in `tea` OAuth app (public client, no secret). Admins can disable it → custom client id in settings. */
    const val BUILT_IN_CLIENT_ID: String = "d57cb8c4-630c-4168-8324-ec79935e18d4"

    private const val DEFAULT_EXPIRES_IN = 3600L
    private const val PKCE_VERIFIER_BYTES = 32

    fun getInstance(): GiteaOAuthService = service()

    private val random = SecureRandom()

    /** URL-safe random string from [bytes] random bytes (32 bytes → 43 chars, a valid PKCE verifier). */
    internal fun randomToken(bytes: Int = 24): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

    /** RFC 7636: `BASE64URL(SHA256(ascii(verifier)))` without padding. */
    internal fun codeChallengeS256(codeVerifier: String): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII)))
  }
}

class GiteaOAuthException(message: @Nls String, cause: Throwable? = null) : RuntimeException(message, cause)

internal data class GiteaOAuthTokenDTO(
  val accessToken: String,
  val tokenType: String? = null,
  val expiresIn: Long? = null,
  val refreshToken: String? = null,
)

/**
 * Accepts exactly one redirect on `http://127.0.0.1:<port>/` and keeps the browser connection open until [respond]
 * (so the page can say whether the token exchange worked). Other paths (`/favicon.ico`) get a 404.
 */
internal class LoopbackListener private constructor(private val server: HttpServer) : AutoCloseable {
  val redirectUri: String = "http://127.0.0.1:${server.address.port}/"

  /** Query parameters of the redirect. */
  val callback = CompletableDeferred<Map<String, String>>()
  private val response = CompletableDeferred<Pair<Boolean, String?>>()
  /** Completed once the result page has been written, so [close] doesn't cut it off. */
  private val pageWritten = CompletableFuture<Unit>()

  init {
    server.createContext("/") { exchange -> handle(exchange) }
    server.start()
  }

  fun respond(success: Boolean, message: String?) {
    response.complete(success to message)
  }

  override fun close() {
    response.complete(false to null)
    if (callback.isCompleted) {
      runCatching { pageWritten.get(PAGE_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    }
    callback.cancel()
    server.stop(0)
  }

  private fun handle(exchange: HttpExchange) {
    if (exchange.requestURI.rawPath != "/" || exchange.requestMethod != "GET" || callback.isCompleted) {
      exchange.use { it.sendResponseHeaders(404, -1) }
      return
    }
    try {
      callback.complete(parseQuery(exchange.requestURI.rawQuery))
      exchange.use { writePage(it) }
    }
    finally {
      pageWritten.complete(Unit)
    }
  }

  private fun writePage(exchange: HttpExchange) {
    val (success, message) = try {
      // Listener thread, not a coroutine: wait for the token exchange so the page can report its outcome
      response.asCompletableFuture().get(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
    catch (_: Exception) {
      false to null
    }
    val bytes = page(success, message).toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
    exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
    exchange.responseHeaders.add("Cache-Control", "no-store")
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.write(bytes)
  }

  companion object {
    private const val RESPONSE_TIMEOUT_MS = 30_000L
    private const val PAGE_WRITE_TIMEOUT_MS = 5_000L
    private const val SUCCESS_COLOR = "#2e7d32"
    private const val FAILURE_COLOR = "#c7222d"

    fun start(): LoopbackListener {
      // Explicitly 127.0.0.1 – Gitea's tea app rejects `localhost`, and ::1 wouldn't match the redirect URI
      val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
      server.executor = null
      LOG.debug("OAuth loopback listener on port ${server.address.port}")
      return LoopbackListener(server)
    }

    fun parseQuery(rawQuery: String?): Map<String, String> =
      rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        val key = URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8)
        val value = URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        key to value
      }

    private fun page(success: Boolean, message: String?): String {
      val text = when {
        success -> GiteaBundle.message("login.oauth.page.success")
        else -> GiteaBundle.message("login.oauth.page.failure") + (message?.let { " $it" } ?: "")
      }
      val color = when {
        success -> SUCCESS_COLOR
        else -> FAILURE_COLOR
      }
      return """<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>Gitea</title></head>
        |<body style="margin:0;padding:24px;font-family:system-ui;font-size:1.4em"><p style="color:$color">${StringUtil.escapeXmlEntities(text)}</p></body></html>""".trimMargin()
    }
  }
}
