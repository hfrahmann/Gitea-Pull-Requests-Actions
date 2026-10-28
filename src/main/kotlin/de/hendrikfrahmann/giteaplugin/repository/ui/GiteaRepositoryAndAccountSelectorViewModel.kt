package de.hendrikfrahmann.giteaplugin.repository.ui

import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectMapping
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import git4idea.remote.hosting.ui.RepositoryAndAccountSelectorViewModelBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * "Which repository, which account?" for the PR and Actions tool windows (empty state).
 * Preselects the last used combination, else the obvious one ([GiteaSelection.preselect]).
 */
class GiteaRepositoryAndAccountSelectorViewModel(
  private val scope: CoroutineScope,
  projectsManager: GiteaProjectsManager,
  private val accountManager: GiteaAccountManager,
  private val defaultAccount: () -> GiteaAccount?,
  private val savedSelection: () -> Pair<String?, String?>,
  /** Read-only access without an account; `false` if the repository isn't public. */
  private val connectAnonymously: suspend (GiteaProjectMapping) -> Boolean = { false },
  onSelected: suspend (GiteaProjectMapping, GiteaAccount) -> Unit,
) : RepositoryAndAccountSelectorViewModelBase<GiteaProjectMapping, GiteaAccount>(scope, projectsManager, accountManager, onSelected) {

  /** `true` after [browseAnonymously] was refused for the selected repository (private, or the server requires sign-in). */
  val anonymousRefused: MutableStateFlow<Boolean> = MutableStateFlow(false)

  init {
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      combine(repositoriesState, accountManager.accountsState) { repos, accounts ->
        val (remoteUrl, accountId) = savedSelection()
        GiteaSelection.preselect(repos, accounts, defaultAccount(), remoteUrl, accountId)
      }.collect { (repo, account) ->
        if (repoSelectionState.value == null || repoSelectionState.value !in repositoriesState.value) {
          repoSelectionState.value = repo
        }
        if (accountSelectionState.value == null && account != null && repo == repoSelectionState.value) {
          accountSelectionState.value = account
        }
      }
    }
  }

  /** "Browse without logging in" for the selected repository. */
  fun browseAnonymously() {
    val repo = repoSelectionState.value ?: return
    scope.launch {
      anonymousRefused.value = false
      anonymousRefused.value = !connectAnonymously(repo)
    }
  }

  /** After a login from the selector: store the account and select it. */
  fun authenticateAccount(account: GiteaAccount, credentials: GiteaAccountCredentials, submit: Boolean) {
    scope.launch {
      accountManager.updateAccount(account, credentials)
      accountSelectionState.value = account
      if (submit) {
        submitSelection()
      }
    }
  }
}

object GiteaSelection {
  /**
   * Repository: the saved one if still known, else the only one. Account (for that repository's server): the saved one,
   * else the project default, else the only one.
   */
  fun preselect(
    repos: Collection<GiteaProjectMapping>,
    accounts: Collection<GiteaAccount>,
    defaultAccount: GiteaAccount?,
    savedRemoteUrl: String?,
    savedAccountId: String?,
  ): Pair<GiteaProjectMapping?, GiteaAccount?> {
    val repo = repos.firstOrNull { it.remote.url == savedRemoteUrl } ?: repos.singleOrNull() ?: return null to null
    val candidates = accounts.filter { it.server == repo.repository.serverPath }
    val account = candidates.firstOrNull { it.id == savedAccountId }
                  ?: defaultAccount?.takeIf { it in candidates }
                  ?: candidates.singleOrNull()
    return repo to account
  }

  /** The only repository and the only account for its server, else `null` (then the user has to choose). */
  fun single(repos: Collection<GiteaProjectMapping>, accounts: Collection<GiteaAccount>): Pair<GiteaProjectMapping, GiteaAccount>? {
    val repo = repos.singleOrNull() ?: return null
    val account = accounts.singleOrNull { it.server == repo.repository.serverPath } ?: return null
    return repo to account
  }
}
