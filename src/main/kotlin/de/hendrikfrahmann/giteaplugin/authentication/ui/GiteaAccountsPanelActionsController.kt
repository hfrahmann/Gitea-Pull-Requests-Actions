package de.hendrikfrahmann.giteaplugin.authentication.ui

import com.intellij.collaboration.auth.ui.AccountsPanelActionsController
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginMethod
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginResult
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginUtil
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import javax.swing.JComponent

/** "+" and "edit" in the accounts list: both offer OAuth (browser) or token login. */
class GiteaAccountsPanelActionsController(
  private val project: Project,
  private val model: GiteaAccountsListModel,
) : AccountsPanelActionsController<GiteaAccount> {
  override val isAddActionWithPopup: Boolean = true

  override fun addAccount(parentComponent: JComponent, point: RelativePoint?) {
    showPopup(parentComponent, point,
              DumbAwareAction.create(GiteaBundle.message("login.action.oauth")) {
                GiteaLoginUtil.logInViaOAuth(project, parentComponent, uniqueAccountPredicate = ::isAccountUnique).addTo(model)
              },
              DumbAwareAction.create(GiteaBundle.message("login.action.token")) {
                GiteaLoginUtil.logInViaToken(project, parentComponent, uniqueAccountPredicate = ::isAccountUnique).addTo(model)
              })
  }

  override fun editAccount(parentComponent: JComponent, account: GiteaAccount) {
    fun relogin(method: GiteaLoginMethod) {
      val result = GiteaLoginUtil.reLogIn(project, parentComponent, account, method, ::isAccountUnique)
      if (result is GiteaLoginResult.Success) {
        model.update(result.account, result.credentials)
      }
    }
    showPopup(parentComponent, null,
              DumbAwareAction.create(GiteaBundle.message("login.action.oauth")) { relogin(GiteaLoginMethod.OAUTH) },
              DumbAwareAction.create(GiteaBundle.message("login.action.token")) { relogin(GiteaLoginMethod.TOKEN) })
  }

  private fun showPopup(parentComponent: JComponent, point: RelativePoint?, vararg actions: DumbAwareAction) {
    JBPopupFactory.getInstance()
      .createActionGroupPopup(null, DefaultActionGroup(*actions), DataManager.getInstance().getDataContext(parentComponent),
                              JBPopupFactory.ActionSelectionAid.MNEMONICS, false)
      .show(point ?: RelativePoint.getCenterOf(parentComponent))
  }

  private fun isAccountUnique(server: GiteaServerPath, username: String): Boolean =
    model.accounts.none { it.server == server && it.name.equals(username, ignoreCase = true) }

  private fun GiteaLoginResult.addTo(model: GiteaAccountsListModel) {
    if (this is GiteaLoginResult.Success) {
      model.add(account, credentials)
    }
  }
}
