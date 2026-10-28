package de.hendrikfrahmann.giteaplugin.repository

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentUser
import de.hendrikfrahmann.giteaplugin.api.request.getRepository
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import git4idea.remote.hosting.HostedGitRepositoryConnectionFactory
import git4idea.remote.hosting.SingleHostedGitRepositoryConnectionManager
import git4idea.remote.hosting.SingleHostedGitRepositoryConnectionManagerImpl
import git4idea.remote.hosting.ValidatingHostedGitRepositoryConnectionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The project's single repository connection (like GitLab's): opening another one closes the previous.
 * Remembers the last selection in [GiteaProjectSettings] for the next start.
 *
 * With [GiteaAccount.anonymous] the connection reads a public repository without credentials; as soon as an
 * account for that server exists, it reconnects with it.
 */
@Service(Service.Level.PROJECT)
class GiteaProjectConnectionManager(private val project: Project, cs: CoroutineScope)
  : SingleHostedGitRepositoryConnectionManager<GiteaProjectMapping, GiteaAccount, GiteaProjectConnection> {

  private val accountManager get() = GiteaAccountManager.getInstance()
  private val projectsManager get() = GiteaProjectsManager.getInstance(project)

  private val accountConnectionFactory = ValidatingHostedGitRepositoryConnectionFactory<GiteaProjectMapping, GiteaAccount, GiteaProjectConnection, GiteaAccountCredentials>(
    { projectsManager },
    { accountManager },
  ) { mapping, account, _ ->
    val api = GiteaApiManager.getInstance().getClient(account)
    val metadata = api.getMetadata().checkSupported(GiteaSettings.getInstance().acceptPreReleases)
    val user = api.getCurrentUser()
    val repository = api.getRepository(mapping.repository.path)
    GiteaProjectConnection(this, mapping, account, api, user, repository, metadata)
  }

  private val connectionFactory = object : HostedGitRepositoryConnectionFactory<GiteaProjectMapping, GiteaAccount, GiteaProjectConnection> {
    override suspend fun connect(parentScope: CoroutineScope, repo: GiteaProjectMapping, account: GiteaAccount): GiteaProjectConnection =
      when {
        account.isAnonymous -> connectAnonymously(parentScope, repo, account)
        else -> accountConnectionFactory.connect(parentScope, repo, account)
      }
  }

  private val delegate = SingleHostedGitRepositoryConnectionManagerImpl(cs, connectionFactory)

  override val connectionState: StateFlow<GiteaProjectConnection?> get() = delegate.connectionState

  init {
    cs.launch {
      accountManager.accountsState.collect(::onAccountsChanged)
    }
  }

  override suspend fun openConnection(repo: GiteaProjectMapping, account: GiteaAccount): GiteaProjectConnection? {
    val connection = delegate.openConnection(repo, account)
    GiteaProjectSettings.getInstance(project).rememberSelection(repo, account)
    return connection
  }

  /** Read-only connection without credentials; fails (`404`/`401`) unless the repository is public and the server allows it. */
  suspend fun openAnonymousConnection(repo: GiteaProjectMapping): GiteaProjectConnection? =
    openConnection(repo, GiteaAccount.anonymous(repo.repository.serverPath))

  override suspend fun closeConnection() = delegate.closeConnection()

  /** An anonymous connection switches to an account of its server once there is one; a removed account disconnects. */
  private suspend fun onAccountsChanged(accounts: Set<GiteaAccount>) {
    val current = connectionState.value ?: return
    if (!current.isAnonymous) {
      if (current.account !in accounts) {
        closeConnection()
      }
      return
    }
    // logged in meanwhile (e.g. via "Log in to …"): continue with that account
    val account = accounts.firstOrNull { it.server == current.account.server } ?: return
    try {
      openConnection(current.repo, account)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      // the anonymous connection is closed by now; the selector shows the account and can retry
      LOG.info("Reconnecting with $account failed: ${e.localizedMessage}")
      closeConnection()
    }
  }

  /** Like [ValidatingHostedGitRepositoryConnectionFactory], minus credentials: closes when the mapping disappears. */
  private suspend fun connectAnonymously(parentScope: CoroutineScope, repo: GiteaProjectMapping, account: GiteaAccount): GiteaProjectConnection {
    val handler = CoroutineExceptionHandler { _, e -> LOG.info(e.localizedMessage) }
    // not a supervisor: a failing loader or the check below closes the connection
    val scope = CoroutineScope(parentScope.coroutineContext + handler + Job(parentScope.coroutineContext[Job]))
    scope.launch {
      projectsManager.knownRepositoriesState.collect {
        check(repo in it) { "Repository mapping $repo is missing" }
      }
    }
    try {
      return withContext(scope.coroutineContext) {
        val api = GiteaApiManager.getInstance().getUnauthenticatedClient(repo.repository.serverPath)
        val metadata = api.getMetadata().checkSupported(GiteaSettings.getInstance().acceptPreReleases)
        val repository = api.getRepository(repo.repository.path)
        GiteaProjectConnection(scope, repo, account, api, null, repository, metadata)
      }
    }
    catch (e: Throwable) {
      scope.cancel()
      throw e
    }
  }

  companion object {
    private val LOG = logger<GiteaProjectConnectionManager>()

    fun getInstance(project: Project): GiteaProjectConnectionManager = project.service()
  }
}
