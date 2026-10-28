package de.hendrikfrahmann.giteaplugin.api

import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val LOG = logger<GiteaApi>()

/**
 * REST client for one Gitea server (`/api/v1`). Request functions are extensions in the `api/request` package.
 *
 * Built on `java.net.http` directly instead of collaboration-tools' `HttpApiHelper`: that API is experimental and
 * binary-incompatible between 2026.1, 2026.2 and 2026.3 (`request()` became `suspend`, `sendAndAwaitCancellable` was
 * removed), so one plugin build couldn't run on all supported IDEs. Proxy and trusted certificates still come from the
 * platform, see [GiteaApiManager].
 */
interface GiteaApi {
  val server: GiteaServerPath

  /**
   * A request builder for [uri] with `User-Agent`, `Accept` and – only if [uri] belongs to [server] – `Authorization`.
   * Suspends because credentials may have to be loaded or refreshed.
   */
  suspend fun request(uri: URI): HttpRequest.Builder

  /**
   * Sends [request], follows redirects of `GET` requests (re-checking where credentials may go) and reads a 2xx body
   * with [reader] on an IO thread.
   *
   * @throws GiteaHttpStatusError for non-2xx responses
   * @throws java.io.IOException for connection problems
   */
  suspend fun <T> sendAndRead(
    request: HttpRequest,
    requestName: String = request.logName(),
    reader: (InputStream, HttpResponse.ResponseInfo) -> T,
  ): GiteaResponse<T>

  /** Server version info, cached per server. */
  suspend fun getMetadata(): GiteaServerMetadata
}

/** Credentials for the `Authorization` header. `toString()` never reveals the token. */
sealed class GiteaCredentials(val token: String) {
  abstract val authorizationHeaderValue: String

  /** Personal access token → `Authorization: token <t>`. */
  class AccessToken(token: String) : GiteaCredentials(token) {
    override val authorizationHeaderValue: String get() = "token $token"
    override fun toString(): String = "AccessToken(***)"
  }

  /** OAuth2 access token → `Authorization: Bearer <t>`. */
  class OAuthToken(token: String) : GiteaCredentials(token) {
    override val authorizationHeaderValue: String get() = "Bearer $token"
    override fun toString(): String = "OAuthToken(***)"
  }
}

/** A successful response: status, final URI (after redirects), headers and the already-read body. */
class GiteaResponse<out T>(
  val statusCode: Int,
  val uri: URI,
  val headers: HttpHeaders,
  val body: T,
) {
  fun header(name: String): String? = headers.firstValue(name).orElse(null)

  /** `X-Total-Count`, sent by list endpoints. */
  val totalCount: Int? get() = header(TOTAL_COUNT_HEADER)?.trim()?.toIntOrNull()

  /** `rel="next"` target of the `Link` header, resolved against [uri]. */
  val nextPageUri: URI? get() = header(LINK_HEADER)?.let { parseLinkHeader(it)["next"] }?.let { runCatching { uri.resolve(it) }.getOrNull() }

  val hasLinkHeader: Boolean get() = header(LINK_HEADER) != null

  fun <R> map(transform: (T) -> R): GiteaResponse<R> = GiteaResponse(statusCode, uri, headers, transform(body))

  companion object {
    const val TOTAL_COUNT_HEADER: String = "X-Total-Count"
    const val LINK_HEADER: String = "Link"

    private val LINK_PART = Regex("""<([^>]*)>\s*;\s*rel\s*=\s*"?([^";,]+)"?""")

    /** `<url>; rel="next", <url>; rel="last"` → `{next=url, last=url}` */
    fun parseLinkHeader(value: String): Map<String, String> =
      LINK_PART.findAll(value).associate { it.groupValues[2].trim() to it.groupValues[1].trim() }
  }
}

internal class GiteaApiImpl(
  override val server: GiteaServerPath,
  private val httpClient: HttpClient,
  private val credentialsSupplier: (suspend () -> GiteaCredentials)?,
  private val userAgent: String,
  private val metadataLoader: suspend (GiteaApi) -> GiteaServerMetadata,
  private val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
  /**
   * Called once per request on `401` when credentials were sent; `true` means fresh credentials are available
   * (e.g. a refreshed OAuth token) and the request is repeated with them.
   */
  private val unauthorizedHandler: (suspend () -> Boolean)? = null,
) : GiteaApi {

  override suspend fun request(uri: URI): HttpRequest.Builder {
    val builder = HttpRequest.newBuilder(uri)
      .timeout(readTimeout)
      .header(USER_AGENT_HEADER, userAgent)
      .header(ACCEPT_HEADER, JSON_CONTENT_TYPE)
    if (credentialsSupplier != null) {
      if (server.owns(uri)) {
        builder.header(AUTHORIZATION_HEADER, credentialsSupplier.invoke().authorizationHeaderValue)
      }
      else {
        LOG.info("$uri is not on $server – sending it without credentials")
      }
    }
    return builder
  }

  override suspend fun <T> sendAndRead(
    request: HttpRequest,
    requestName: String,
    reader: (InputStream, HttpResponse.ResponseInfo) -> T,
  ): GiteaResponse<T> {
    var current = request
    var credentialsRefreshed = false
    repeat(MAX_REDIRECTS + 2) {
      val response = send(current)
      val status = response?.statusCode() ?: HTTP_UNAUTHORIZED
      if (response != null && status in HTTP_SUCCESS) {
        return readBody(response, reader)
      }
      if (status == HTTP_UNAUTHORIZED && !credentialsRefreshed && canRefreshCredentials(current)) {
        credentialsRefreshed = true
        response?.body()?.close()
        current = refreshCredentials(current) ?: throw GiteaHttpStatusError(status, null, requestName)
        return@repeat
      }
      val redirect = response?.let { redirectOf(current, it) }
      if (redirect != null) {
        response.body().close()
        current = redirect
        return@repeat
      }
      throw statusError(response, status, requestName)
    }
    throw IOException("$requestName: more than $MAX_REDIRECTS redirects")
  }

  override suspend fun getMetadata(): GiteaServerMetadata = metadataLoader(this)

  /** Sends [request]; `null` stands for a `401` the JDK client refused to hand out (see below). */
  private suspend fun send(request: HttpRequest): HttpResponse<InputStream>? {
    val started = System.nanoTime()
    val response = try {
      httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
    }
    catch (e: IOException) {
      // the JDK client (it has the IDE's proxy authenticator) refuses a 401 without `WWW-Authenticate`, which is what
      // Gitea sends, e.g. for Actions endpoints without a token
      if (e.message?.endsWith(MISSING_AUTHENTICATE_401) != true) {
        throw e
      }
      null
    }
    LOG.debug { "${request.logName()} -> ${response?.statusCode() ?: HTTP_UNAUTHORIZED} (${(System.nanoTime() - started) / NANOS_PER_MILLI} ms)" }
    if (LOG.isTraceEnabled) {
      LOG.trace("Request headers: ${redactHeaders(request.headers())}")
    }
    return response
  }

  private suspend fun <T> readBody(response: HttpResponse<InputStream>, reader: (InputStream, HttpResponse.ResponseInfo) -> T): GiteaResponse<T> {
    val info = ResponseInfo(response)
    val body = runInterruptible(Dispatchers.IO) { response.body().use { reader(it, info) } }
    return GiteaResponse(response.statusCode(), response.uri(), response.headers(), body)
  }

  private fun canRefreshCredentials(request: HttpRequest): Boolean =
    unauthorizedHandler != null && request.headers().firstValue(AUTHORIZATION_HEADER).isPresent

  /** [request] again with renewed credentials, or `null` if there are none (e.g. the OAuth refresh failed). */
  private suspend fun refreshCredentials(request: HttpRequest): HttpRequest? {
    if (unauthorizedHandler?.invoke() != true) {
      return null
    }
    val builder = HttpRequest.newBuilder(request) { name, _ -> !name.equals(AUTHORIZATION_HEADER, true) }
    credentialsSupplier?.let { builder.header(AUTHORIZATION_HEADER, it().authorizationHeaderValue) }
    return builder.build()
  }

  /** The request that follows a redirect [response], or `null` if [response] isn't one this client follows. */
  private suspend fun redirectOf(request: HttpRequest, response: HttpResponse<*>): HttpRequest? {
    val status = response.statusCode()
    val location = response.headers().firstValue(LOCATION_HEADER).orElse(null)
    if (status !in REDIRECT_CODES || location == null) {
      return null
    }
    val target = request.uri().resolve(location)
    return when {
      request.method() == "GET" -> followRedirect(request, target)
      // a renamed or transferred repository answers 301 to its old path (remotes keep the old URL): writes are sent
      // again, same method and body, but only to this server
      status != HTTP_SEE_OTHER && server.owns(target) -> {
        LOG.info("${request.method()} ${request.uri().path} moved to ${target.path}")
        HttpRequest.newBuilder(request) { _, _ -> true }.uri(target).build()
      }
      else -> null
    }
  }

  /** A `GET` of [target] with the previous `Accept` and timeout; credentials go along only to this server (see [request]). */
  private suspend fun followRedirect(previous: HttpRequest, target: URI): HttpRequest {
    val builder = request(target).GET()
    previous.headers().firstValue(ACCEPT_HEADER).ifPresent { builder.setHeader(ACCEPT_HEADER, it) }
    previous.timeout().ifPresent { builder.timeout(it) }
    return builder.build()
  }

  private suspend fun statusError(response: HttpResponse<InputStream>?, status: Int, requestName: String): GiteaHttpStatusError {
    val body = response?.let { readErrorBody(it) }
    val error = GiteaHttpStatusError(status, GiteaHttpStatusError.parseMessage(body), requestName)
    LOG.debug(error.message)
    return error
  }

  private suspend fun readErrorBody(response: HttpResponse<InputStream>): String =
    runInterruptible(Dispatchers.IO) { response.body().use { it.readNBytes(MAX_ERROR_BODY).toString(Charsets.UTF_8) } }

  private class ResponseInfo(private val response: HttpResponse<*>) : HttpResponse.ResponseInfo {
    override fun statusCode(): Int = response.statusCode()
    override fun headers(): HttpHeaders = response.headers()
    override fun version(): HttpClient.Version = response.version()
  }

  companion object {
    val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(60)
    private const val MAX_REDIRECTS = 5
    private const val MISSING_AUTHENTICATE_401 = "header missing for response code 401"
    private const val MAX_ERROR_BODY = 64 * 1024
    private const val NANOS_PER_MILLI = 1_000_000
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_SEE_OTHER = 303
    private val HTTP_SUCCESS = 200..299
    private val REDIRECT_CODES = setOf(301, 302, HTTP_SEE_OTHER, 307, 308)
  }
}

internal const val AUTHORIZATION_HEADER = "Authorization"
internal const val USER_AGENT_HEADER = "User-Agent"
internal const val ACCEPT_HEADER = "Accept"
internal const val LOCATION_HEADER = "Location"
internal const val CONTENT_TYPE_HEADER = "Content-Type"
internal const val JSON_CONTENT_TYPE = "application/json"

/** `GET https://host/api/v1/user` – query omitted, it may contain search terms. */
fun HttpRequest.logName(): String = "${method()} ${uri().scheme}://${uri().rawAuthority}${uri().rawPath}"

/** Headers for logging, with `Authorization` (and any `*token*` header) values replaced by `***`. */
fun redactHeaders(headers: HttpHeaders): String =
  headers.map().entries.joinToString(", ", "{", "}") { (name, values) ->
    val sensitive = name.equals(AUTHORIZATION_HEADER, true) || name.contains("token", true) || name.equals("Cookie", true)
    "$name=" + if (sensitive) {
      "***"
    }
    else {
      values.joinToString(",")
    }
  }
