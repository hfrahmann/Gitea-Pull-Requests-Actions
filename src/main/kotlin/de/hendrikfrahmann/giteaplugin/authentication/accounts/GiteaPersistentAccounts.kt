package de.hendrikfrahmann.giteaplugin.authentication.accounts

import com.intellij.collaboration.auth.ObservableAccountsRepository
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection
import de.hendrikfrahmann.giteaplugin.settings.GiteaLegacyState
import kotlinx.coroutines.flow.MutableStateFlow

/** Account list (no secrets) in `de.hendrikfrahmann.giteaplugin.xml`. Not roamed: tokens are machine-local, an account without one is useless. */
@Service(Service.Level.APP)
@State(
  name = "de.hendrikfrahmann.giteaplugin.Accounts",
  category = SettingsCategory.TOOLS,
  exportable = true,
  storages = [Storage(value = "de.hendrikfrahmann.giteaplugin.xml", roamingType = RoamingType.DISABLED)],
  reportStatistic = false,
)
class GiteaPersistentAccounts : ObservableAccountsRepository<GiteaAccount>, PersistentStateComponent<GiteaPersistentAccounts.AccountsState> {

  class AccountsState {
    @get:XCollection(style = XCollection.Style.v2)
    var accounts: MutableList<GiteaAccount> = mutableListOf()
  }

  override val accountsFlow: MutableStateFlow<Set<GiteaAccount>> = MutableStateFlow(emptySet())

  override var accounts: Set<GiteaAccount>
    get() = accountsFlow.value
    set(value) {
      accountsFlow.value = value.toSet()
    }

  override fun getState(): AccountsState = AccountsState().apply { accounts = accountsFlow.value.toMutableList() }

  override fun loadState(state: AccountsState) {
    // Drop entries without a usable server (hand-edited or broken XML)
    accountsFlow.value = state.accounts.filter { it.server.uri.isNotEmpty() && it.name.isNotEmpty() }.toSet()
  }

  override fun noStateLoaded() {
    val legacy = GiteaLegacyState.read(LEGACY_COMPONENT_NAME, AccountsState::class.java)
    if (legacy != null) {
      loadState(legacy)
    }
    else {
      accountsFlow.value = emptySet()
    }
  }

  companion object {
    /** Component name before 1.0.5, see [GiteaLegacyState]. */
    internal const val LEGACY_COMPONENT_NAME: String = "GiteaAccounts"
  }
}
