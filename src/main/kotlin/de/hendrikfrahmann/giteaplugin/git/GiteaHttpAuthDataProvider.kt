package de.hendrikfrahmann.giteaplugin.git

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.AuthData
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginMethod
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginResult
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginUtil
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaProjectDefaultAccountHolder
import de.hendrikfrahmann.giteaplugin.repository.GiteaServerResolver
import de.hendrikfrahmann.giteaplugin.repository.GiteaServersManager
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import git4idea.remote.GitHttpAuthDataProvider
import git4idea.remote.hosting.GitHostingUrlUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LOG = logger<GiteaHttpAuthDataProvider>()

/** Auth data that remembers its account, so a rejected token can be ignored for that URL. */
internal class GiteaAccountAuthData(val account: GiteaAccount, login: String, token: String) : AuthData(login, token)

/** Accounts that can authenticate git operations on a git URL: host mapping first, else same host as the account's server. */
internal object GiteaGitAccounts {
  fun serversFor(gitUrl: String): (GiteaServerPath) -> Boolean {
    return when (val mapped = GiteaSettings.getInstance().hostMappings.value.serverFor(GiteaServerResolver.hostOf(gitUrl))) {
      null -> { server -> GitHostingUrlUtil.matchHost(server.toURI(), gitUrl) }
      else -> { server -> server == mapped }
    }
  }

  fun accountsFor(project: Project, gitUrl: String, login: String?): List<GiteaAccount> {
    val matchesServer = serversFor(gitUrl)
    val failures = GiteaGitAuthenticationFailureManager.getInstance(project)
    val accounts = GiteaAccountManager.getInstance().accountsState.value
      .filter { matchesServer(it.server) && !failures.isAccountIgnored(gitUrl, it) }
      .filter { login == null || it.name.equals(login, ignoreCase = true) }
    val default = GiteaProjectDefaultAccountHolder.getInstance(project).account
    // Default account first – used when several match
    return accounts.sortedByDescending { it == default }
  }

  suspend fun authData(account: GiteaAccount): GiteaAccountAuthData? =
    try {
      val credentials = GiteaAccountManager.getInstance().getAndRefreshCredentials(account)
      GiteaAccountAuthData(account, account.name, credentials.accessToken)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("No usable credentials for $account: ${e.message}")
      null
    }
}

/**
 * Silent provider for git HTTPS: if an account for the remote's server exists, use its login and token (refreshed for
 * OAuth). With several accounts, the project's default account wins, otherwise git falls through to the interactive one.
 */
class GiteaHttpAuthDataProvider : GitHttpAuthDataProvider {
  override fun isSilent(): Boolean = true

  override fun getAuthData(project: Project, url: String): AuthData? = runBlockingMaybeCancellable { find(project, url, null) }

  override fun getAuthData(project: Project, url: String, login: String): AuthData? = runBlockingMaybeCancellable { find(project, url, login) }

  private suspend fun find(project: Project, url: String, login: String?): AuthData? {
    val accounts = GiteaGitAccounts.accountsFor(project, url, login)
    val default = GiteaProjectDefaultAccountHolder.getInstance(project).account
    val account = accounts.firstOrNull { it == default } ?: accounts.singleOrNull() ?: return null
    return GiteaGitAccounts.authData(account)
  }

  override fun forgetPassword(project: Project, url: String, authData: AuthData) {
    if (authData is GiteaAccountAuthData) {
      GiteaGitAuthenticationFailureManager.getInstance(project).ignoreAccount(url, authData.account)
    }
  }
}

/**
 * Interactive provider, asked when the silent one had nothing: choose among several accounts, or log in to a Gitea
 * server without (working) account. Returns `null` – git's own password prompt – for non-Gitea hosts or on cancel.
 */
class GiteaInteractiveHttpAuthDataProvider : GitHttpAuthDataProvider {
  override fun getAuthData(project: Project, url: String): AuthData? = runBlockingMaybeCancellable { ask(project, url, null) }

  override fun getAuthData(project: Project, url: String, login: String): AuthData? = runBlockingMaybeCancellable { ask(project, url, login) }

  override fun forgetPassword(project: Project, url: String, authData: AuthData) {
    if (authData is GiteaAccountAuthData) {
      GiteaGitAuthenticationFailureManager.getInstance(project).ignoreAccount(url, authData.account)
    }
  }

  private suspend fun ask(project: Project, url: String, login: String?): AuthData? {
    val accounts = GiteaGitAccounts.accountsFor(project, url, login)
    if (accounts.size > 1) {
      val account = onEdt {
        GiteaLoginUtil.chooseAccount(project, null, GiteaBundle.message("git.auth.choose.account", url), accounts)
      } ?: return null
      return GiteaGitAccounts.authData(account) ?: login(project, account.server, account)
    }

    // No working account: is this a Gitea server at all?
    val server = accounts.singleOrNull()?.server
                 ?: GiteaSettings.getInstance().hostMappings.value.serverFor(GiteaServerResolver.hostOf(url))
                 ?: GiteaServersManager.getInstance().discoverServer(url.trimEnd('/') + "/owner/repo.git")
                 ?: return null
    return login(project, server, accounts.singleOrNull())
  }

  /** Asks for a login (new, or again for [existing]) and stores the account. */
  private suspend fun login(project: Project, server: GiteaServerPath, existing: GiteaAccount?): AuthData? {
    val accountManager = GiteaAccountManager.getInstance()
    val result = onEdt {
      val method = askLoginMethod(project, server) ?: return@onEdt GiteaLoginResult.Failure
      val unique: (GiteaServerPath, String) -> Boolean = { s, name -> accountManager.isAccountUnique(s, name) }
      when {
        existing != null -> GiteaLoginUtil.reLogIn(project, null, existing, method, unique)
        method == GiteaLoginMethod.OAUTH -> GiteaLoginUtil.logInViaOAuth(project, null, server, unique)
        else -> GiteaLoginUtil.logInViaToken(project, null, server, unique)
      }
    }
    if (result !is GiteaLoginResult.Success) {
      return null
    }
    accountManager.updateAccount(result.account, result.credentials)
    return GiteaAccountAuthData(result.account, result.account.name, result.credentials.accessToken)
  }

  /** "Log in to <server>": browser or token; `null` on cancel. */
  private fun askLoginMethod(project: Project, server: GiteaServerPath): GiteaLoginMethod? {
    val methods = listOf(GiteaLoginMethod.OAUTH, GiteaLoginMethod.TOKEN)
    val choice = Messages.showDialog(
      project,
      GiteaBundle.message("git.auth.login.message", server),
      GiteaBundle.message("login.dialog.title"),
      arrayOf(GiteaBundle.message("login.action.oauth"), GiteaBundle.message("login.action.token"), Messages.getCancelButton()),
      0, Messages.getQuestionIcon(),
    )
    return methods.getOrNull(choice)
  }

  private suspend fun <T> onEdt(block: () -> T): T = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { block() }
}
