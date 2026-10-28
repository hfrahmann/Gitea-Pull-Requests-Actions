package de.hendrikfrahmann.giteaplugin.authentication.accounts

import com.intellij.collaboration.auth.PasswordSafeCredentialsRepository
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaJson

/** What we keep in PasswordSafe for an account. `toString()` never reveals tokens. */
sealed class GiteaAccountCredentials {
  abstract val accessToken: String

  abstract fun toApiCredentials(): GiteaCredentials

  /** Personal access token. */
  class Token(override val accessToken: String) : GiteaAccountCredentials() {
    override fun toApiCredentials(): GiteaCredentials = GiteaCredentials.AccessToken(accessToken)
    override fun toString(): String = "Token(***)"
  }

  /** OAuth2 tokens from the authorization-code flow (Gitea access tokens live 3600 s). */
  class OAuth(
    override val accessToken: String,
    val refreshToken: String,
    val clientId: String,
    /** Epoch seconds. */
    val expiresAt: Long,
  ) : GiteaAccountCredentials() {
    override fun toApiCredentials(): GiteaCredentials = GiteaCredentials.OAuthToken(accessToken)

    fun isAccessTokenValid(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
      nowSeconds < expiresAt - EXPIRY_MARGIN_SECONDS

    override fun toString(): String = "OAuth(***, clientId=$clientId, expiresAt=$expiresAt)"
  }

  companion object {
    /** Refresh this long before the access token expires, so it doesn't run out mid-operation. */
    const val EXPIRY_MARGIN_SECONDS: Long = 60
  }

  /** JSON in PasswordSafe. A non-JSON value is read as a plain token. */
  internal object Mapper : PasswordSafeCredentialsRepository.CredentialsMapper<GiteaAccountCredentials> {
    private data class Stored(
      val type: String,
      val accessToken: String,
      val refreshToken: String? = null,
      val clientId: String? = null,
      val expiresAt: Long = 0,
    )

    private const val TOKEN = "token"
    private const val OAUTH = "oauth"

    override fun serialize(credentials: GiteaAccountCredentials): String = GiteaJson.toJsonString(
      when (credentials) {
        is Token -> Stored(TOKEN, credentials.accessToken)
        is OAuth -> Stored(OAUTH, credentials.accessToken, credentials.refreshToken, credentials.clientId, credentials.expiresAt)
      }
    )

    override fun deserialize(credentials: String): GiteaAccountCredentials {
      val stored = try {
        GiteaJson.fromJson<Stored>(credentials)
      }
      catch (_: Exception) {
        return Token(credentials)
      }
      return when {
        stored.type == OAUTH && stored.refreshToken != null && stored.clientId != null ->
          OAuth(stored.accessToken, stored.refreshToken, stored.clientId, stored.expiresAt)
        else -> Token(stored.accessToken)
      }
    }
  }
}
