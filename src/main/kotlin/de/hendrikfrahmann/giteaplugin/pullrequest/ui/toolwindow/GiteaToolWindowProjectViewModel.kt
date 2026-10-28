package de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaProjectDefaultAccountHolder
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnectionManager
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectMapping
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectSettings
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import de.hendrikfrahmann.giteaplugin.repository.ui.GiteaRepositoryAndAccountSelectorViewModel
import de.hendrikfrahmann.giteaplugin.repository.ui.GiteaSelection
import de.hendrikfrahmann.giteaplugin.util.childScope
import de.hendrikfrahmann.giteaplugin.util.mapScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State of the PR tool window for the project: available when a Gitea remote exists; not connected → repository/account
 * selector, connected → [GiteaToolWindowConnectedProjectViewModel]. Lives as long as the project, so the content
 * survives hiding the tool window.
 */
private val LOG = logger<GiteaToolWindowProjectViewModel>()

@Service(Service.Level.PROJECT)
class GiteaToolWindowProjectViewModel(private val project: Project, parentCs: CoroutineScope) {
  private val cs = parentCs.childScope(Dispatchers.Default)

  private val projectsManager = GiteaProjectsManager.getInstance(project)
  private val connectionManager = GiteaProjectConnectionManager.getInstance(project)
  private val accountManager = GiteaAccountManager.getInstance()

  val isAvailable: StateFlow<Boolean> = projectsManager.knownRepositoriesState.map { it.isNotEmpty() }
    .stateIn(cs, SharingStarted.Eagerly, projectsManager.knownRepositoriesState.value.isNotEmpty())

  val connectedProjectVm: StateFlow<GiteaToolWindowConnectedProjectViewModel?> =
    connectionManager.connectionState.mapScoped { connection ->
      connection?.let { GiteaToolWindowConnectedProjectViewModel(this, project, it, ::activate) }
    }.stateIn(cs, SharingStarted.Eagerly, null)

  val selectorVm: StateFlow<GiteaRepositoryAndAccountSelectorViewModel?> = isAvailable.mapScoped { available ->
    if (!available) {
      return@mapScoped null
    }
    val settings = GiteaProjectSettings.getInstance(project)
    GiteaRepositoryAndAccountSelectorViewModel(
      this, projectsManager, accountManager,
      defaultAccount = { GiteaProjectDefaultAccountHolder.getInstance(project).account },
      savedSelection = { settings.selectedRemoteUrl to settings.selectedAccountId },
      onSelected = ::connect,
    ).also { selector ->
      // connect right away to the last used repository and account, like GitLab
      launch {
        val (remoteUrl, accountId) = settings.selectedRemoteUrl to settings.selectedAccountId
        if (remoteUrl == null || accountId == null) {
          tryAnonymousOnce()
          // nothing to choose with one repository and one account for its server (fresh clone), like GitHub
          combine(projectsManager.knownRepositoriesState, accountManager.accountsState, GiteaSelection::single)
            .filterNotNull().distinctUntilChanged()
            .collect { (repo, account) ->
              if (connectionManager.connectionState.value != null) {
                return@collect
              }
              selector.selectRepoAndAccount(repo, account)
              selector.submitSelection()
            }
          return@launch
        }
        if (GiteaAccount.isAnonymousId(accountId)) {
          val repo = projectsManager.knownRepositoriesState.map { repos -> repos.find { it.remote.url == remoteUrl } }.filterNotNull().first()
          connectAnonymously(repo)
          return@launch
        }
        val (repo, account) = combine(projectsManager.knownRepositoriesState, accountManager.accountsState) { repos, accounts ->
          val repo = repos.find { it.remote.url == remoteUrl }
          val account = accounts.find { it.id == accountId && it.server == repo?.repository?.serverPath }
          if (repo != null && account != null) {
            repo to account
          }
          else {
            null
          }
        }.filterNotNull().first()
        selector.selectRepoAndAccount(repo, account)
        selector.submitSelection()
      }
    }
  }.stateIn(cs, SharingStarted.Eagerly, null)

  /** Switching makes sense unless there is exactly one repository and one account for it. */
  val canSwitchProject: StateFlow<Boolean> =
    combine(connectedProjectVm, projectsManager.knownRepositoriesState, accountManager.accountsState) { vm, repos, accounts ->
      vm != null && GiteaSelection.single(repos, accounts) == null
    }.distinctUntilChanged().stateIn(cs, SharingStarted.Eagerly, false)

  private val _activationRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val activationRequests: Flow<Unit> = _activationRequests.asSharedFlow()

  fun activate() {
    _activationRequests.tryEmit(Unit)
  }

  fun switchProject() {
    cs.launch {
      GiteaProjectSettings.getInstance(project).forgetSelection()
      connectionManager.closeConnection()
    }
  }

  /** Shows the tool window and runs [action] once connected (immediately when already connected). */
  fun activateAndAwaitProject(action: GiteaToolWindowConnectedProjectViewModel.() -> Unit) {
    activate()
    cs.launch(Dispatchers.EDT) {
      connectedProjectVm.filterNotNull().first().action()
    }
  }

  private var anonymousTried = false

  /**
   * First start without a saved selection: if the only repository's server has no account, try reading it anonymously.
   * Silent – a private repository or a server that requires sign-in just leaves the selector. Once per session,
   * so "Switch Repository or Account" doesn't reconnect right away.
   */
  private suspend fun tryAnonymousOnce() {
    if (anonymousTried) {
      return
    }
    anonymousTried = true
    val repo = projectsManager.knownRepositoriesState.value.singleOrNull() ?: return
    if (accountManager.accountsState.value.any { it.server == repo.repository.serverPath }) {
      return
    }
    connectAnonymously(repo)
  }

  /** Read-only connection to [repo]; `false` (and logged) if the server refuses it. */
  suspend fun connectAnonymously(repo: GiteaProjectMapping): Boolean =
    try {
      connectionManager.openAnonymousConnection(repo)
      true
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Anonymous access to ${repo.repository} refused: ${e.localizedMessage}")
      false
    }

  private suspend fun connect(mapping: GiteaProjectMapping, account: GiteaAccount) {
    val current = connectionManager.connectionState.value
    if (current != null && current.repo == mapping && current.account == account) {
      return
    }
    connectionManager.openConnection(mapping, account)
  }

  companion object {
    fun getInstance(project: Project): GiteaToolWindowProjectViewModel = project.service()
  }
}
