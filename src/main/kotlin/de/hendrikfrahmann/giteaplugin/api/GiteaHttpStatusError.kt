package de.hendrikfrahmann.giteaplugin.api

import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import org.jetbrains.annotations.Nls

/**
 * A non-2xx response from Gitea. Gitea error bodies are `{"message": "...", "url": "..."}`;
 * [apiMessage] is that message (may be `null` for non-JSON bodies, e.g. from a reverse proxy).
 */
class GiteaHttpStatusError(
  val statusCode: Int,
  val apiMessage: String?,
  val requestName: String,
) : RuntimeException("$requestName failed with HTTP $statusCode" + (apiMessage?.let { ": $it" } ?: "")) {

  enum class Kind {
    /** 401 – token missing, invalid, expired or revoked → re-login. */
    UNAUTHORIZED,
    /** 403 – token scope or repository permission insufficient. */
    FORBIDDEN,
    NOT_FOUND,
    /** 400, 422 – invalid input (Gitea validation errors). */
    VALIDATION,
    /** 405, 409 – state conflict, e.g. PR not mergeable, head changed, already exists. */
    CONFLICT,
    /** 5xx */
    SERVER,
    OTHER,
  }

  val kind: Kind = kindOf(statusCode)

  override fun getLocalizedMessage(): @Nls String {
    val text = when (kind) {
      Kind.UNAUTHORIZED -> GiteaBundle.message("api.error.unauthorized")
      Kind.FORBIDDEN -> GiteaBundle.message("api.error.forbidden")
      Kind.NOT_FOUND -> GiteaBundle.message("api.error.not.found")
      Kind.VALIDATION -> GiteaBundle.message("api.error.validation")
      Kind.CONFLICT -> GiteaBundle.message("api.error.conflict")
      Kind.SERVER -> GiteaBundle.message("api.error.server", statusCode)
      Kind.OTHER -> GiteaBundle.message("api.error.other", statusCode)
    }
    return when {
      apiMessage.isNullOrBlank() -> text
      else -> "$text ($apiMessage)"
    }
  }

  companion object {
    const val HTTP_NOT_FOUND: Int = 404

    fun kindOf(statusCode: Int): Kind = when (statusCode) {
      401 -> Kind.UNAUTHORIZED
      403 -> Kind.FORBIDDEN
      HTTP_NOT_FOUND -> Kind.NOT_FOUND
      400, 422 -> Kind.VALIDATION
      405, 409 -> Kind.CONFLICT
      in 500..599 -> Kind.SERVER
      else -> Kind.OTHER
    }

    /** Extracts `message` from a Gitea error body; `null` if the body isn't a Gitea error JSON. */
    fun parseMessage(body: String?): String? {
      if (body.isNullOrBlank()) {
        return null
      }
      return try {
        GiteaJson.mapper.readTree(body)?.get("message")?.takeIf { it.isTextual }?.asText()
      }
      catch (_: Exception) {
        null
      }
    }
  }
}

val Throwable.isGiteaUnauthorized: Boolean
  get() = this is GiteaHttpStatusError && kind == GiteaHttpStatusError.Kind.UNAUTHORIZED

/** Runs [block]; an answer with HTTP [statusCode] (default `404`) becomes `null`, other errors are rethrown. */
inline fun <T> nullOnHttpStatus(statusCode: Int = GiteaHttpStatusError.HTTP_NOT_FOUND, block: () -> T): T? =
  try {
    block()
  }
  catch (e: GiteaHttpStatusError) {
    if (e.statusCode != statusCode) {
      throw e
    }
    null
  }
