package de.hendrikfrahmann.giteaplugin.authentication

import com.fasterxml.jackson.core.JacksonException
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.GiteaServerMetadata
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.GiteaUnsupportedServerException
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentTokenScopes
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentUser
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import org.jetbrains.annotations.Nls
import java.net.URI

object GiteaSecurityUtil {
  /**
   * Token scopes the plugin needs: repositories/PRs/Actions (`repository`), PR comments and reviews (`issue`),
   * the login (`user`) and orgs for clone/share (`organization`).
   */
  val REQUIRED_SCOPES: List<String> = listOf("write:repository", "write:issue", "read:user", "read:organization")

  /** Page where users create access tokens (Gitea has no parameters to prefill the form). */
  fun newTokenPageUri(server: GiteaServerPath): URI = server.webUri("user", "settings", "applications")

  /**
   * Checks the server version (≥ 28.0) and the credentials, returns the user.
   *
   * @throws GiteaLoginException with a user-facing message for each problem
   */
  suspend fun validateAndResolveUser(
    server: GiteaServerPath,
    credentials: GiteaCredentials,
    requiredUsername: String? = null,
    uniqueAccountPredicate: (GiteaServerPath, String) -> Boolean = { _, _ -> true },
    apiManager: GiteaApiManager = GiteaApiManager.getInstance(),
  ): GiteaUserDTO {
    val api = apiManager.getClient(server, credentials)
    checkServer(api)
    val user = try {
      api.getCurrentUser()
    }
    catch (e: GiteaHttpStatusError) {
      throw GiteaLoginException(e.loginMessage(), e)
    }
    if (requiredUsername != null && !user.login.equals(requiredUsername, ignoreCase = true)) {
      throw GiteaLoginException(GiteaBundle.message("login.error.username.mismatch", requiredUsername, user.login))
    }
    if (!uniqueAccountPredicate(server, user.login)) {
      throw GiteaLoginException(GiteaBundle.message("login.error.account.exists", user.login))
    }
    return user
  }

  /** `GET /version`; anything that isn't Gitea ≥ 28 is a [GiteaLoginException]. */
  suspend fun checkServer(api: GiteaApi): GiteaServerMetadata {
    val metadata = try {
      api.getMetadata()
    }
    catch (e: GiteaHttpStatusError) {
      throw GiteaLoginException(GiteaBundle.message("login.error.not.gitea", api.server), e)
    }
    catch (e: JacksonException) {
      throw GiteaLoginException(GiteaBundle.message("login.error.not.gitea", api.server), e)
    }
    catch (e: IllegalArgumentException) {
      throw GiteaLoginException(GiteaBundle.message("login.error.not.gitea", api.server), e)
    }
    try {
      return metadata.checkSupported(GiteaSettings.getInstance().acceptPreReleases)
    }
    catch (e: GiteaUnsupportedServerException) {
      throw GiteaLoginException(e.localizedMessage, e)
    }
  }

  /** Scopes of the token (`GET /token`), `null` if the server doesn't tell (e.g. for OAuth tokens). */
  suspend fun loadTokenScopes(api: GiteaApi): List<String>? =
    runCatching { api.getCurrentTokenScopes() }.getOrNull()

  /** Required scopes not covered by [scopes] (`all`, and `write:x` covering `read:x`, are taken into account). */
  fun missingScopes(scopes: Collection<String>): List<String> {
    if ("all" in scopes) {
      return emptyList()
    }
    return REQUIRED_SCOPES.filter { required ->
      val area = required.substringAfter(':')
      required !in scopes && !(required.startsWith("read:") && "write:$area" in scopes)
    }
  }
}

/** What to tell the user about a failed request while logging in: an invalid token gets its own message. */
internal fun GiteaHttpStatusError.loginMessage(): @Nls String = when (kind) {
  GiteaHttpStatusError.Kind.UNAUTHORIZED -> GiteaBundle.message("login.error.token.invalid")
  else -> localizedMessage
}

/** A login problem with a message for the user (shown in login dialogs). */
class GiteaLoginException(message: @Nls String, cause: Throwable? = null) : Exception(message, cause)
