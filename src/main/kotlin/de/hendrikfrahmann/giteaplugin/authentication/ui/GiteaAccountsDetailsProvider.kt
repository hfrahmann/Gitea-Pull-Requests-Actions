package de.hendrikfrahmann.giteaplugin.authentication.ui

import com.intellij.collaboration.auth.AccountDetails
import com.intellij.collaboration.auth.ui.LazyLoadingAccountsDetailsProvider
import com.intellij.collaboration.auth.ui.cancelOnRemoval
import com.intellij.icons.AllIcons
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.isGiteaUnauthorized
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentUser
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginException
import de.hendrikfrahmann.giteaplugin.authentication.GiteaSecurityUtil
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaCredentialsRefreshException
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaMissingCredentialsException
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.awt.Image
import java.net.URI
import javax.imageio.ImageIO

/** Name and avatar of an account, from `GET /user`. */
class GiteaAccountDetails(override val name: String, override val avatarUrl: String?) : AccountDetails

/** Loads [GiteaAccountDetails] for the accounts list; errors become "log in again" hints where that helps. */
class GiteaAccountsDetailsProvider private constructor(
  scope: CoroutineScope,
  private val apiSupplier: suspend (GiteaAccount) -> GiteaApi,
) : LazyLoadingAccountsDetailsProvider<GiteaAccount, GiteaAccountDetails>(scope, AllIcons.General.User) {

  /** For the settings page: uses credentials not yet applied from [accountsModel]. */
  constructor(scope: CoroutineScope, accountManager: GiteaAccountManager, accountsModel: GiteaAccountsListModel)
    : this(scope, { account ->
    val apiManager = GiteaApiManager.getInstance()
    accountsModel.newCredentials[account]?.let { apiManager.getClient(account.server, it.toApiCredentials()) }
    ?: apiManager.getClient(account, accountManager)
  }) {
    cancelOnRemoval(accountsModel.accountsListModel)
  }

  constructor(scope: CoroutineScope, accountManager: GiteaAccountManager)
    : this(scope, { GiteaApiManager.getInstance().getClient(it, accountManager) }) {
    cancelOnRemoval(scope, accountManager)
  }

  override suspend fun loadDetails(account: GiteaAccount): Result<GiteaAccountDetails> =
    try {
      val api = apiSupplier(account)
      GiteaSecurityUtil.checkServer(api)
      val user = api.getCurrentUser()
      Result.Success(GiteaAccountDetails(user.fullName?.takeIf { it.isNotBlank() } ?: user.login, user.avatarUrl))
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: GiteaLoginException) {
      Result.Error(e.localizedMessage, false)
    }
    catch (_: GiteaCredentialsRefreshException) {
      Result.Error(GiteaBundle.message("account.error.refresh.failed"), true)
    }
    catch (_: GiteaMissingCredentialsException) {
      Result.Error(GiteaBundle.message("account.error.token.missing"), true)
    }
    catch (e: GiteaHttpStatusError) {
      when {
        e.isGiteaUnauthorized -> Result.Error(GiteaBundle.message("account.error.token.invalid"), true)
        else -> Result.Error(e.localizedMessage, false)
      }
    }
    catch (e: Exception) {
      Result.Error(e.localizedMessage ?: e.javaClass.simpleName, false)
    }

  override suspend fun loadAvatar(account: GiteaAccount, url: String): Image? =
    try {
      val api = apiSupplier(account)
      val uri = URI.create(url).takeIf { it.isAbsolute } ?: account.server.rebase(url)
      api.sendAndRead(api.request(uri).setHeader("Accept", "image/*").GET().build()) { stream, _ -> ImageIO.read(stream) }.body
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (_: Exception) {
      null
    }
}
