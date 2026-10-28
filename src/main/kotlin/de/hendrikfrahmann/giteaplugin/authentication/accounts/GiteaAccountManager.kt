package de.hendrikfrahmann.giteaplugin.authentication.accounts

import com.intellij.collaboration.auth.AccountManagerBase
import com.intellij.collaboration.auth.AccountsRepository
import com.intellij.collaboration.auth.CredentialsRepository
import com.intellij.collaboration.auth.PasswordSafeCredentialsRepository
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaOAuthService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val LOG = logger<GiteaAccountManager>()

/** All Gitea accounts of the IDE; credentials in PasswordSafe (service `Gitea`, key = account id). */
@Service(Service.Level.APP)
class GiteaAccountManager : AccountManagerBase<GiteaAccount, GiteaAccountCredentials>(LOG) {
  // AccountManagerBase.dataGuard is only accessible from 2026.3 on – own lock for refreshes
  private val refreshGuard = Mutex()

  // Called from the AccountManagerBase constructor: must not use fields of this class. The return type is deprecated
  // (ObservableAccountsRepository), but that's what AccountManagerBase declares in 261–263 – unavoidable.
  @Suppress("DEPRECATION")
  override fun accountsRepository(): AccountsRepository<GiteaAccount> = service<GiteaPersistentAccounts>()

  override fun credentialsRepository(): CredentialsRepository<GiteaAccount, GiteaAccountCredentials> =
    PasswordSafeCredentialsRepository(CREDENTIALS_SERVICE_NAME, GiteaAccountCredentials.Mapper)

  /**
   * Credentials of [account]; OAuth access tokens are refreshed if they expire within a minute or if [forceRefresh].
   *
   * @throws GiteaMissingCredentialsException if there are none (or PasswordSafe failed)
   * @throws GiteaCredentialsRefreshException if the OAuth refresh failed → the user must log in again
   */
  suspend fun getAndRefreshCredentials(account: GiteaAccount, forceRefresh: Boolean = false): GiteaAccountCredentials =
    refreshGuard.withLock {
      when (val credentials = findStoredCredentials(account) ?: throw GiteaMissingCredentialsException(account)) {
        is GiteaAccountCredentials.Token -> credentials
        is GiteaAccountCredentials.OAuth -> when {
          !forceRefresh && credentials.isAccessTokenValid() -> credentials
          else -> refreshOAuthToken(account, credentials)
        }
      }
    }

  fun isAccountUnique(server: GiteaServerPath, username: String): Boolean =
    accountsState.value.none { it.server == server && it.name.equals(username, ignoreCase = true) }

  /** The credentials in PasswordSafe; a PasswordSafe failure counts as missing credentials. */
  private suspend fun findStoredCredentials(account: GiteaAccount): GiteaAccountCredentials? =
    try {
      findCredentials(account)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      throw GiteaMissingCredentialsException(account, e)
    }

  /** Gets new OAuth tokens and stores them – Gitea rotates the refresh token, so the old one is used up. */
  private suspend fun refreshOAuthToken(account: GiteaAccount, credentials: GiteaAccountCredentials.OAuth): GiteaAccountCredentials.OAuth {
    val refreshed = try {
      GiteaOAuthService.getInstance().refresh(account.server, credentials)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.warn("Could not refresh the OAuth token for $account", e)
      throw GiteaCredentialsRefreshException(account, e)
    }
    updateAccount(account, refreshed)
    return refreshed
  }

  companion object {
    const val CREDENTIALS_SERVICE_NAME: String = "Gitea"

    fun getInstance(): GiteaAccountManager = service()
  }
}

class GiteaMissingCredentialsException(val account: GiteaAccount, cause: Throwable? = null)
  : RuntimeException("No credentials for $account", cause)

class GiteaCredentialsRefreshException(val account: GiteaAccount, cause: Throwable? = null)
  : RuntimeException("Could not refresh the OAuth token for $account", cause)

/**
 * Client for [account]: credentials are loaded (and OAuth tokens refreshed) before each request;
 * on `401` an OAuth token is refreshed once and the request repeated.
 */
fun GiteaApiManager.getClient(account: GiteaAccount, accountManager: GiteaAccountManager = GiteaAccountManager.getInstance()): GiteaApi =
  getClient(
    account.server,
    onUnauthorized = {
      val current = accountManager.findCredentials(account)
      current is GiteaAccountCredentials.OAuth && runCatching { accountManager.getAndRefreshCredentials(account, forceRefresh = true) }.isSuccess
    },
  ) { accountManager.getAndRefreshCredentials(account).toApiCredentials() }
