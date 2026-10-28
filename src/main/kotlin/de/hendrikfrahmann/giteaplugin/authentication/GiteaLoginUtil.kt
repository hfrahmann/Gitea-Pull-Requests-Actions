package de.hendrikfrahmann.giteaplugin.authentication

import com.intellij.collaboration.auth.Account
import com.intellij.collaboration.auth.ui.login.LoginModel
import com.intellij.collaboration.auth.ui.login.TokenLoginDialog
import com.intellij.collaboration.auth.ui.login.TokenLoginInputPanelFactory
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPresenter
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaProjectDefaultAccountHolder
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaChooseAccountDialog
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaOAuthLoginDialog
import de.hendrikfrahmann.giteaplugin.authentication.ui.GiteaTokenLoginPanelModel
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.annotations.Nls
import java.awt.Component
import javax.swing.JComponent

private val LOG = logger<GiteaLoginUtil>()

/** Login dialogs. The caller stores a [GiteaLoginResult.Success] (accounts list model or account manager). */
object GiteaLoginUtil {
  /** `true` when PasswordSafe only keeps secrets in memory – tokens would be lost on restart. */
  val isPasswordSafeMemoryOnly: Boolean get() = PasswordSafe.instance.isMemoryOnly

  /** Server URL + personal access token. */
  @RequiresEdt
  fun logInViaToken(
    project: Project,
    parentComponent: JComponent?,
    server: GiteaServerPath? = null,
    uniqueAccountPredicate: (GiteaServerPath, String) -> Boolean,
  ): GiteaLoginResult = tokenLogin(project, parentComponent, server?.uri ?: GiteaSettings.getInstance().defaultServer, null, null,
                                   GiteaBundle.message("login.dialog.title"), uniqueAccountPredicate)

  /** Browser login with the server's OAuth app (built-in `tea` by default). */
  @RequiresEdt
  fun logInViaOAuth(
    project: Project,
    parentComponent: JComponent?,
    server: GiteaServerPath? = null,
    uniqueAccountPredicate: (GiteaServerPath, String) -> Boolean,
  ): GiteaLoginResult = oauthLogin(project, parentComponent, server?.uri ?: GiteaSettings.getInstance().defaultServer, serverEditable = true,
                                   requiredUsername = null, accountId = null, uniqueAccountPredicate)

  /** Replace the credentials of [account] (same server and user), by the method the user picks. */
  @RequiresEdt
  fun reLogIn(
    project: Project,
    parentComponent: JComponent?,
    account: GiteaAccount,
    method: GiteaLoginMethod,
    uniqueAccountPredicate: (GiteaServerPath, String) -> Boolean,
  ): GiteaLoginResult {
    val predicate = withoutAccount(account, uniqueAccountPredicate)
    return when (method) {
      GiteaLoginMethod.OAUTH -> oauthLogin(project, parentComponent, account.server.uri, serverEditable = false,
                                           requiredUsername = account.name, accountId = account.id, predicate)
      GiteaLoginMethod.TOKEN -> tokenLogin(project, parentComponent, account.server.uri, account.name, account.id,
                                           GiteaBundle.message("login.dialog.relogin.title"), predicate)
    }
  }

  @RequiresEdt
  fun chooseAccount(project: Project, parentComponent: Component?, description: @Nls String?, accounts: Collection<GiteaAccount>): GiteaAccount? {
    val dialog = GiteaChooseAccountDialog(project, parentComponent, accounts, showHosts = accounts.map { it.server }.distinct().size > 1,
                                          allowDefault = true, description = description)
    if (!dialog.showAndGet()) {
      return null
    }
    if (dialog.setDefault) {
      GiteaProjectDefaultAccountHolder.getInstance(project).account = dialog.account
    }
    return dialog.account
  }

  private fun tokenLogin(
    project: Project,
    parentComponent: JComponent?,
    serverUri: String,
    requiredUsername: String?,
    accountId: String?,
    title: @Nls String,
    uniqueAccountPredicate: (GiteaServerPath, String) -> Boolean,
  ): GiteaLoginResult {
    val model = GiteaTokenLoginPanelModel(requiredUsername, uniqueAccountPredicate).apply { this.serverUri = serverUri }
    val cs = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
    try {
      val dialog = TokenLoginDialog(project, cs, parentComponent, model, title) {
        TokenLoginInputPanelFactory(model).createIn(
          this,
          serverFieldDisabled = requiredUsername != null,
          tokenNote = GiteaBundle.message("login.token.scopes.note", GiteaSecurityUtil.REQUIRED_SCOPES.joinToString(", ")),
          errorPresenter = ErrorStatusPresenter.simple(GiteaBundle.message("login.error.title"),
                                                       descriptionProvider = { e: Throwable -> e.localizedMessage ?: e.javaClass.simpleName }),
        )
      }
      if (!dialog.showAndGet()) {
        return GiteaLoginResult.Failure
      }
      val connected = model.loginState.value as? LoginModel.LoginState.Connected ?: return GiteaLoginResult.Failure
      val account = GiteaAccount(connected.username, model.getServerPath(), accountId ?: Account.generateId())
      return GiteaLoginResult.Success(account, GiteaAccountCredentials.Token(model.token.trim()))
    }
    finally {
      cs.cancel()
    }
  }

  private fun oauthLogin(
    project: Project,
    parentComponent: JComponent?,
    serverUri: String,
    serverEditable: Boolean,
    requiredUsername: String?,
    accountId: String?,
    uniqueAccountPredicate: (GiteaServerPath, String) -> Boolean,
  ): GiteaLoginResult {
    val dialog = GiteaOAuthLoginDialog(project, parentComponent, serverUri, serverEditable)
    if (!dialog.showAndGet()) {
      return GiteaLoginResult.Failure
    }
    val server = dialog.server
    return try {
      runWithModalProgressBlocking(project, GiteaBundle.message("login.oauth.progress", server)) {
        authorizeInBrowser(server, dialog.clientId, requiredUsername, accountId, uniqueAccountPredicate)
      }
    }
    catch (_: CancellationException) {
      GiteaLoginResult.Failure
    }
    catch (e: Exception) {
      LOG.info("Gitea OAuth login to $server failed", e)
      showLoginError(project, parentComponent, e.localizedMessage ?: e.javaClass.simpleName)
      GiteaLoginResult.Failure
    }
  }

  private suspend fun authorizeInBrowser(server: GiteaServerPath, clientId: String, requiredUsername: String?, accountId: String?,
                                         uniqueAccountPredicate: (GiteaServerPath, String) -> Boolean): GiteaLoginResult.Success {
    // check the server before sending the user to the browser
    GiteaSecurityUtil.checkServer(GiteaApiManager.getInstance().getUnauthenticatedClient(server))
    val credentials = GiteaOAuthService.getInstance().authorize(server, clientId)
    val user = GiteaSecurityUtil.validateAndResolveUser(server, credentials.toApiCredentials(), requiredUsername, uniqueAccountPredicate)
    return GiteaLoginResult.Success(GiteaAccount(user.login, server, accountId ?: Account.generateId()), credentials)
  }

  private fun showLoginError(project: Project, parentComponent: JComponent?, message: @Nls String) {
    val title = GiteaBundle.message("login.error.title")
    if (parentComponent != null) {
      Messages.showErrorDialog(parentComponent, message, title)
    }
    else {
      Messages.showErrorDialog(project, message, title)
    }
  }

  private fun withoutAccount(account: GiteaAccount, predicate: (GiteaServerPath, String) -> Boolean): (GiteaServerPath, String) -> Boolean =
    { server, username -> (server == account.server && username.equals(account.name, true)) || predicate(server, username) }
}

enum class GiteaLoginMethod { OAUTH, TOKEN }

sealed interface GiteaLoginResult {
  data class Success(val account: GiteaAccount, val credentials: GiteaAccountCredentials) : GiteaLoginResult
  data object Failure : GiteaLoginResult
}
