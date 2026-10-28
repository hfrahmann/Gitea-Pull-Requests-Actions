package de.hendrikfrahmann.giteaplugin.authentication.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginMethod
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginResult
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginUtil
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationsScope
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.launch
import javax.swing.JComponent

/**
 * "Log in to <server>…" in place of write actions of an anonymous connection: a popup offers browser or token
 * login for the server; the new account is stored, and the connection manager then reconnects with it.
 */
object GiteaAnonymousLoginLink {
  fun create(project: Project, server: GiteaServerPath, text: String = GiteaBundle.message("anonymous.login.link", server.host)): ActionLink {
    lateinit var link: ActionLink
    link = ActionLink(text) { showPopup(project, link, server) }
    return link
  }

  fun showPopup(project: Project, component: JComponent, server: GiteaServerPath) {
    val methods = listOf(GiteaLoginMethod.OAUTH, GiteaLoginMethod.TOKEN)
    val step = object : BaseListPopupStep<GiteaLoginMethod>(null, methods) {
      override fun getTextFor(value: GiteaLoginMethod): String = when (value) {
        GiteaLoginMethod.OAUTH -> GiteaBundle.message("login.action.oauth")
        GiteaLoginMethod.TOKEN -> GiteaBundle.message("login.action.token")
      }

      override fun onChosen(selectedValue: GiteaLoginMethod, finalChoice: Boolean): PopupStep<*>? =
        doFinalStep { logIn(project, component, server, selectedValue) }
    }
    JBPopupFactory.getInstance().createListPopup(step).show(RelativePoint.getSouthWestOf(component))
  }

  fun logIn(project: Project, component: JComponent?, server: GiteaServerPath, method: GiteaLoginMethod) {
    val accountManager = GiteaAccountManager.getInstance()
    val unique = { s: GiteaServerPath, name: String -> accountManager.isAccountUnique(s, name) }
    val result = when (method) {
      GiteaLoginMethod.OAUTH -> GiteaLoginUtil.logInViaOAuth(project, component, server, unique)
      GiteaLoginMethod.TOKEN -> GiteaLoginUtil.logInViaToken(project, component, server, unique)
    }
    if (result is GiteaLoginResult.Success) {
      service<GiteaNotificationsScope>().cs.launch { accountManager.updateAccount(result.account, result.credentials) }
    }
  }
}
