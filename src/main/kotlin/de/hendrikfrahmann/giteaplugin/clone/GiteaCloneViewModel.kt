package de.hendrikfrahmann.giteaplugin.clone

import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaRepositoryDTO
import de.hendrikfrahmann.giteaplugin.api.request.currentUserRepositoriesFlow
import de.hendrikfrahmann.giteaplugin.api.request.httpCloneUrl
import de.hendrikfrahmann.giteaplugin.authentication.GiteaConnectionTester
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Nls

/** One row in the clone list. */
sealed interface GiteaCloneItem {
  val account: GiteaAccount

  data class Repository(override val account: GiteaAccount, val repository: GiteaRepositoryDTO) : GiteaCloneItem {
    /** HTTPS URL on the configured server (not Gitea's `ROOT_URL`), or the SSH URL. */
    fun cloneUrl(useSsh: Boolean): String =
      repository.sshUrl?.takeIf { useSsh && it.isNotBlank() } ?: account.server.httpCloneUrl(repository)
  }

  data class Error(override val account: GiteaAccount, val message: @Nls String) : GiteaCloneItem
}

/**
 * Repositories of all accounts for "Get from VCS": `GET /user/repos` (owned, collaborations and organizations' repos),
 * loaded page by page per account and shown as they arrive.
 */
class GiteaCloneViewModel(private val cs: CoroutineScope, private val accountManager: GiteaAccountManager) {
  val accounts: StateFlow<Set<GiteaAccount>> = accountManager.accountsState

  private val itemsByAccount = MutableStateFlow<Map<GiteaAccount, List<GiteaCloneItem>>>(emptyMap())
  private val loadingAccounts = MutableStateFlow<Set<GiteaAccount>>(emptySet())

  /** Accounts in their order, each account's repositories sorted by full name. */
  val items: StateFlow<List<GiteaCloneItem>> = combine(accounts, itemsByAccount) { accounts, byAccount ->
    accounts.flatMap { byAccount[it].orEmpty() }
  }.stateIn(cs, SharingStarted.Eagerly, emptyList())

  val isLoading: StateFlow<Boolean> = loadingAccounts.map { it.isNotEmpty() }.stateIn(cs, SharingStarted.Eagerly, false)

  private val jobs = mutableMapOf<GiteaAccount, Job>()

  init {
    cs.launch {
      accounts.collect { accounts ->
        (jobs.keys - accounts).forEach { removed -> jobs.remove(removed)?.cancel() }
        itemsByAccount.value = itemsByAccount.value.filterKeys { it in accounts }
        accounts.filter { it !in jobs }.forEach(::load)
      }
    }
  }

  fun reload() {
    jobs.values.forEach { it.cancel() }
    jobs.clear()
    itemsByAccount.value = emptyMap()
    accounts.value.forEach(::load)
  }

  private fun load(account: GiteaAccount) {
    jobs[account] = cs.launch {
      loadingAccounts.value += account
      try {
        val api = GiteaApiManager.getInstance().getClient(account, accountManager)
        val loaded = mutableListOf<GiteaRepositoryDTO>()
        api.currentUserRepositoriesFlow().collect { page ->
          loaded += page
          itemsByAccount.value += account to loaded.sortedBy { it.fullName.lowercase() }.map { GiteaCloneItem.Repository(account, it) }
        }
        if (loaded.isEmpty()) {
          itemsByAccount.value += account to emptyList()
        }
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        itemsByAccount.value += account to listOf(GiteaCloneItem.Error(account, GiteaConnectionTester.describe(e)))
      }
      finally {
        loadingAccounts.value -= account
      }
    }
  }

  companion object {
    /** Matches the search text against `owner/name` and the description. */
    fun matches(item: GiteaCloneItem, query: String): Boolean {
      if (query.isBlank()) {
        return true
      }
      if (item !is GiteaCloneItem.Repository) {
        return true
      }
      val text = query.trim()
      return item.repository.fullName.contains(text, ignoreCase = true) || item.repository.description.orEmpty().contains(text, ignoreCase = true)
    }

    /** A pasted URL is cloned as is. */
    fun isUrl(text: String): Boolean {
      val trimmed = text.trim()
      return URL_WITH_SCHEME.matches(trimmed) || SCP_LIKE_URL.matches(trimmed)
    }

    private val URL_WITH_SCHEME = Regex("^(https?|ssh|git)://\\S+$")
    private val SCP_LIKE_URL = Regex("^[\\w.-]+@[\\w.-]+:\\S+$")
  }
}
