package de.hendrikfrahmann.giteaplugin.authentication.ui

import com.intellij.collaboration.auth.ui.login.LoginPanelModelBase
import com.intellij.collaboration.auth.ui.login.LoginTokenGenerator
import com.intellij.ide.BrowserUtil
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginException
import de.hendrikfrahmann.giteaplugin.authentication.GiteaSecurityUtil
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle

/** Model of the token login dialog: server URL + personal access token, validated with `/version` and `/user`. */
class GiteaTokenLoginPanelModel(
  var requiredUsername: String? = null,
  var uniqueAccountPredicate: (GiteaServerPath, String) -> Boolean,
) : LoginPanelModelBase(), LoginTokenGenerator {

  override suspend fun checkToken(): String {
    val server = getServerPathOrNull() ?: throw GiteaLoginException(GiteaBundle.message("login.error.server.url.invalid"))
    return GiteaSecurityUtil.validateAndResolveUser(server, GiteaCredentials.AccessToken(token.trim()), requiredUsername, uniqueAccountPredicate).login
  }

  fun getServerPath(): GiteaServerPath = GiteaServerPath.fromUserInput(serverUri)

  fun getServerPathOrNull(): GiteaServerPath? = parseServer(serverUri)

  override fun canGenerateToken(serverUri: String): Boolean = parseServer(serverUri) != null

  /** Opens the server's token page in the browser. */
  override fun generateToken(serverUri: String) {
    val server = parseServer(serverUri) ?: return
    BrowserUtil.browse(GiteaSecurityUtil.newTokenPageUri(server))
  }

  private fun parseServer(serverUri: String): GiteaServerPath? = runCatching { GiteaServerPath.fromUserInput(serverUri) }.getOrNull()
}
