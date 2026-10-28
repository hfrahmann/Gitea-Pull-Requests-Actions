package de.hendrikfrahmann.giteaplugin.authentication

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentUser
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaCredentialsRefreshException
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaMissingCredentialsException
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import org.jetbrains.annotations.Nls
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import javax.net.ssl.SSLException

/** "Test connection": who am I, which server version, which token scopes – or a precise reason why not. */
object GiteaConnectionTester {
  sealed interface Outcome {
    val message: @Nls String

    data class Success(val login: String, val version: String, val scopes: List<String>?, val missingScopes: List<String>) : Outcome {
      override val message: @Nls String
        get() = buildString {
          append(GiteaBundle.message("connection.test.success", login, version))
          append(' ')
          append(scopes?.let { GiteaBundle.message("connection.test.scopes", it.joinToString(", ")) }
                 ?: GiteaBundle.message("connection.test.scopes.unknown"))
          if (missingScopes.isNotEmpty()) {
            append(' ').append(GiteaBundle.message("connection.test.scopes.missing", missingScopes.joinToString(", ")))
          }
        }
    }

    data class Failure(override val message: @Nls String) : Outcome
  }

  suspend fun test(api: GiteaApi): Outcome =
    try {
      val metadata = GiteaSecurityUtil.checkServer(api)
      val user = api.getCurrentUser()
      val scopes = GiteaSecurityUtil.loadTokenScopes(api)
      Outcome.Success(user.login, metadata.version.toString(), scopes, scopes?.let(GiteaSecurityUtil::missingScopes).orEmpty())
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      Outcome.Failure(describe(e))
    }

  fun describe(e: Throwable): @Nls String = when (e) {
    is GiteaLoginException -> e.localizedMessage
    is GiteaHttpStatusError -> e.loginMessage()
    is GiteaMissingCredentialsException -> GiteaBundle.message("account.error.token.missing")
    is GiteaCredentialsRefreshException -> GiteaBundle.message("account.error.refresh.failed")
    is SSLException -> GiteaBundle.message("connection.error.tls", e.localizedMessage ?: e.javaClass.simpleName)
    is UnknownHostException -> GiteaBundle.message("connection.error.unknown.host", e.localizedMessage ?: "")
    is HttpConnectTimeoutException, is ConnectException -> GiteaBundle.message("connection.error.connect", e.localizedMessage ?: e.javaClass.simpleName)
    is HttpTimeoutException -> GiteaBundle.message("connection.error.timeout")
    else -> e.cause?.takeIf { it is SSLException || it is ConnectException || it is UnknownHostException }?.let(::describe)
            ?: (e.localizedMessage ?: e.javaClass.simpleName)
  }
}
