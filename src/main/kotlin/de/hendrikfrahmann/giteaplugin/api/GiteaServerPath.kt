package de.hendrikfrahmann.giteaplugin.api

import com.intellij.collaboration.api.ServerPath
import com.intellij.openapi.util.NlsSafe
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * A Gitea server: scheme, host, optional port and optional path prefix (for Gitea served under a subpath).
 *
 * [uri] is normalized: lower-case scheme and host, no default port, no trailing slash, no query/fragment,
 * e.g. `https://git.example.com/gitea`. So two paths that differ only by a trailing slash are equal.
 */
@Tag("server")
class GiteaServerPath : ServerPath {

  @field:Attribute("uri")
  var uri: String = ""
    private set

  /** For XML deserialization only. */
  constructor()

  constructor(uri: String) {
    this.uri = normalize(uri)
  }

  val scheme: String get() = toURI().scheme
  val host: String get() = toURI().host
  /** `-1` when the default port of [scheme] is used. */
  val port: Int get() = toURI().port
  /** Path prefix without trailing slash, `""` when Gitea is served at the root. */
  val pathPrefix: String get() = toURI().rawPath.removeSuffix("/")

  /** Web root with trailing slash, e.g. `https://host/gitea/`. */
  override fun toURI(): URI = URI.create("$uri/")

  fun toWebUri(): URI = toURI()

  /** REST API root with trailing slash, e.g. `https://host/gitea/api/v1/`. */
  fun toApiUri(): URI = URI.create("$uri/api/v1/")

  /** `https://host/gitea/api/v1/<segments…>` with every segment percent-encoded. */
  fun apiUri(vararg pathSegments: String): URI = URI.create("$uri/api/v1/" + encodePath(pathSegments))

  /** `https://host/gitea/<segments…>` – web links built from coordinates, never from response URLs. */
  fun webUri(vararg pathSegments: String): URI = URI.create("$uri/" + encodePath(pathSegments))

  /**
   * Rewrites an absolute URL taken from an API response (`html_url`, `archive_download_url`, …) onto this server.
   *
   * Gitea builds those URLs from its `ROOT_URL`, which may differ from the URL the user configured
   * (other host, scheme, port or subpath). [remoteRoot] is that `ROOT_URL` as seen in responses (see [remoteRootOf]).
   * When it's unknown, scheme, host and port are swapped and our path prefix is added unless the path already starts with it.
   * Relative URLs (Gitea writes some with its own subpath, e.g. Actions' `target_url`) lose the subpath of [remoteRoot] –
   * or ours when it's unknown – and are put below this server.
   */
  fun rebase(url: String, remoteRoot: URI? = null): URI {
    val parsed = URI.create(url.trim())
    val path = parsed.rawPath.orEmpty()
    // the subpath Gitea itself uses in the URL (its ROOT_URL), or ours when that's unknown
    val remotePrefix = when (remoteRoot) {
      null -> pathPrefix
      else -> remoteRoot.rawPath.orEmpty().removeSuffix("/")
    }
    val pathOnServer = when {
      parsed.isAbsolute && remoteRoot != null -> path.removePrefix(remotePrefix)
      remotePrefix.isNotEmpty() && path.startsWith("$remotePrefix/") -> path.removePrefix(remotePrefix)
      else -> path
    }
    return URI.create(uri + "/" + pathOnServer.removePrefix("/") + parsed.rawQueryAndFragment())
  }

  /** `true` if [target] points to this server (same scheme, host, port and below the path prefix). */
  fun owns(target: URI): Boolean {
    val server = toURI()
    if (!target.isAbsolute) {
      return false
    }
    if (!target.scheme.equals(server.scheme, true) || !target.host.equals(server.host, true)) {
      return false
    }
    if (effectivePort(target) != effectivePort(server)) {
      return false
    }
    return (target.rawPath.orEmpty() + "/").startsWith(server.rawPath)
  }

  @NlsSafe
  override fun toString(): String = uri

  override fun equals(other: Any?): Boolean = this === other || (other is GiteaServerPath && uri == other.uri)

  override fun hashCode(): Int = uri.hashCode()

  companion object {
    /** Suggested in login dialogs. */
    const val DEFAULT_SERVER_URL: String = "https://gitea.com"

    /**
     * Lenient parsing of what users type or paste: adds `https://` when no scheme is given and strips
     * a trailing `/api/v1` (people paste the API URL), `/api/swagger` or `/swagger`.
     *
     * @throws IllegalArgumentException if it's still not an http(s) URL with a host
     */
    fun fromUserInput(text: String): GiteaServerPath {
      var url = text.trim()
      if (!url.contains("://")) {
        url = "https://$url"
      }
      url = url.substringBefore('?').substringBefore('#').trimEnd('/')
      for (suffix in listOf("/api/v1", "/api/swagger", "/swagger.v1.json", "/api")) {
        if (url.endsWith(suffix, ignoreCase = true)) {
          url = url.dropLast(suffix.length)
        }
      }
      return GiteaServerPath(url)
    }

    /**
     * Derives the server's `ROOT_URL` from an absolute URL in a response whose path is known to end with
     * [knownPathSuffix], e.g. a repository's `html_url` and `"owner/repo"`. `null` if it doesn't end with it.
     */
    fun remoteRootOf(absoluteUrl: String, knownPathSuffix: String): URI? {
      val parsed = runCatching { URI.create(absoluteUrl.trim()) }.getOrNull() ?: return null
      val path = parsed.rawPath?.removeSuffix("/") ?: return null
      val suffix = "/" + knownPathSuffix.trim('/')
      if (!path.endsWith(suffix, ignoreCase = true)) {
        return null
      }
      return URI(parsed.scheme, parsed.rawAuthority, null, null, null).resolve(path.dropLast(suffix.length) + "/")
    }

    /** @throws IllegalArgumentException for blank, relative or non-http(s) URLs. */
    fun normalize(url: String): String {
      val trimmed = url.trim()
      require(trimmed.isNotEmpty()) { "Server URL is empty" }
      val parsed = try {
        URI(trimmed)
      }
      catch (e: URISyntaxException) {
        throw IllegalArgumentException("Invalid server URL: $trimmed", e)
      }
      val scheme = parsed.scheme?.lowercase()
      require(scheme == "http" || scheme == "https") { "Server URL must start with http:// or https://: $trimmed" }
      val host = parsed.host?.lowercase()
      require(!host.isNullOrEmpty()) { "Server URL has no host: $trimmed" }
      val port = parsed.port.takeIf { it != -1 && it != defaultPort(scheme) }
      val path = parsed.rawPath.orEmpty().trimEnd('/')
      return buildString {
        append(scheme).append("://").append(host)
        if (port != null) {
          append(':').append(port)
        }
        append(path)
      }
    }

    private fun defaultPort(scheme: String?): Int = when (scheme?.lowercase()) {
      "http" -> 80
      "https" -> 443
      else -> -1
    }

    private fun effectivePort(uri: URI): Int = when {
      uri.port != -1 -> uri.port
      else -> defaultPort(uri.scheme)
    }

    private fun encodePath(segments: Array<out String>): String =
      segments.joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

    private fun URI.rawQueryAndFragment(): String =
      (rawQuery?.let { "?$it" } ?: "") + (rawFragment?.let { "#$it" } ?: "")
  }
}
