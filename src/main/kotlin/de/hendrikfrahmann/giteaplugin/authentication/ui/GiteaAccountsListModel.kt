package de.hendrikfrahmann.giteaplugin.authentication.ui

import com.intellij.collaboration.auth.ui.AccountsListModel
import com.intellij.collaboration.auth.ui.MutableAccountsListModel
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials

/** Accounts as edited in the settings page; applied to [de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager] on Apply. */
class GiteaAccountsListModel : MutableAccountsListModel<GiteaAccount, GiteaAccountCredentials>(),
                               AccountsListModel.WithDefault<GiteaAccount, GiteaAccountCredentials> {
  override var defaultAccount: GiteaAccount? = null
}
