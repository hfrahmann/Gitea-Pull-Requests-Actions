package de.hendrikfrahmann.giteaplugin.repository

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import git4idea.remote.hosting.HostedGitRepositoriesManager
import git4idea.remote.hosting.discoverServers
import git4idea.remote.hosting.gitRemotesFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

private val LOG = logger<GiteaProjectsManager>()

/**
 * Git remotes of the project that point to Gitea. A remote is Gitea if its host is in the host mappings, belongs to a
 * server with an account, or answers the `/api/v1/version` probe ([GiteaServersManager]).
 */
@Service(Service.Level.PROJECT)
class GiteaProjectsManager(private val project: Project, private val cs: CoroutineScope)
  : HostedGitRepositoriesManager<GiteaProjectMapping> {

  override val knownRepositoriesState: StateFlow<Set<GiteaProjectMapping>> by lazy {
    val remotesFlow = gitRemotesFlow(project).distinctUntilChanged()
    val mappingsFlow = GiteaSettings.getInstance().hostMappings
    val accountServersFlow = GiteaAccountManager.getInstance().accountsState
      .map { accounts -> accounts.mapTo(mutableSetOf()) { it.server } }
      .distinctUntilChanged()

    val unmappedRemotes = remotesFlow.combine(mappingsFlow) { remotes, mappings ->
      remotes.filter { mappings.serverFor(GiteaRemoteUrl.parse(it.url)?.host) == null }
    }
    val discoveredServersFlow = unmappedRemotes
      .discoverServers(accountServersFlow) { remote -> GiteaServersManager.getInstance().discoverServer(remote.url) }
      .runningFold(emptySet<GiteaServerPath>()) { servers, server -> servers + server }
      .distinctUntilChanged()

    combine(remotesFlow, accountServersFlow, discoveredServersFlow, mappingsFlow) { remotes, accountServers, discovered, mappings ->
      remotes.mapNotNullTo(mutableSetOf()) { remote ->
        GiteaServerResolver.resolve(remote.url, accountServers + discovered, mappings)?.let { GiteaProjectMapping.create(it, remote) }
      }
    }
      .onEach { LOG.debug { "Gitea repositories: $it" } }
      .stateIn(cs, SharingStarted.Eagerly, emptySet())
  }

  companion object {
    fun getInstance(project: Project): GiteaProjectsManager = project.service()
  }
}
