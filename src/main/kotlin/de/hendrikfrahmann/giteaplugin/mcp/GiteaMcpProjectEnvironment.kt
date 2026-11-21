package de.hendrikfrahmann.giteaplugin.mcp

import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaProjectDefaultAccountHolder
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnectionManager
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectMapping
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * [GiteaMcpEnvironment] of an open project: the Gitea settings, the project's Gitea remotes and its connection.
 *
 * The connected repository (the one the PR and Actions tool windows show) is used with its connection – review mode puts
 * comments into the same pending review the diff and editors show. Other repositories of the project get a client of an
 * account for their server (the project's default account first), or an anonymous one.
 */
class GiteaMcpProjectEnvironment(private val project: Project) : GiteaMcpEnvironment {
  private val settings get() = GiteaSettings.getInstance()

  override val toolsEnabled: Boolean get() = settings.mcpToolsEnabled

  override val writeToolsEnabled: Boolean get() = settings.mcpToolsEnabled && settings.mcpWriteToolsEnabled

  override suspend fun repositories(): List<GiteaMcpRepository> {
    val connection = awaitConnection()
    return mappings().map { mapping ->
      val connected = connection?.repo == mapping
      val account = when {
        connected -> connection.account.takeUnless { connection.isAnonymous }
        else -> accountFor(mapping)
      }
      GiteaMcpRepository(mapping.repository.path, mapping.repository.serverPath.toString(), mapping.remote.remote.name, mapping.remote.url,
                         mapping.gitRepository.root.path, connected, account?.name)
    }
  }

  override suspend fun target(repository: String?): GiteaMcpTarget {
    val mappings = mappings()
    if (mappings.isEmpty()) {
      throw GiteaMcpException("The project has no Git remote on a Gitea server.")
    }
    val connection = awaitConnection()
    val mapping = when (repository?.takeIf { it.isNotBlank() }) {
      null -> connection?.repo ?: mappings.singleOrNull()
              ?: throw GiteaMcpException("The project has several Gitea repositories, pass `repository`: ${names(mappings)}")
      else -> findMapping(mappings, repository.trim(), connection)
    }
    if (connection != null && connection.repo == mapping) {
      return connectedTarget(connection)
    }
    val account = accountFor(mapping)
    val apiManager = GiteaApiManager.getInstance()
    val api = when (account) {
      null -> apiManager.getUnauthenticatedClient(mapping.repository.serverPath)
      else -> apiManager.getClient(account)
    }
    return GiteaMcpTarget(api, mapping.repository.path, isAnonymous = account == null)
  }

  /** [repository] (`owner/name`) among [mappings], the connected remote first when several remotes point to it. */
  private fun findMapping(mappings: List<GiteaProjectMapping>, repository: String, connection: GiteaProjectConnection?): GiteaProjectMapping {
    val path = GiteaRepositoryPath.parse(repository) ?: throw GiteaMcpException("`repository` must be `owner/name`, got \"$repository\".")
    val matching = mappings.filter { it.repository.path.toString().equals(path.toString(), ignoreCase = true) }
    return matching.firstOrNull { it == connection?.repo } ?: matching.firstOrNull()
           ?: throw GiteaMcpException("$path is not a Gitea repository of this project. Known: ${names(mappings)}")
  }

  private fun connectedTarget(connection: GiteaProjectConnection): GiteaMcpTarget {
    val vm = { GiteaToolWindowProjectViewModel.getInstance(project).connectedProjectVm.value?.takeIf { it.connection == connection } }
    return GiteaMcpTarget(
      connection.api, connection.repo.repository.path, connection.isAnonymous,
      reviewData = { number ->
        val connectedVm = vm() ?: throw GiteaMcpException("The connection to ${connection.repo.repository.path} was just closed, try again.")
        connectedVm.dataProvider(number).reviewData
      },
      onPullRequestChanged = { number -> vm()?.refreshPullRequest(number) },
    )
  }

  private fun mappings(): List<GiteaProjectMapping> =
    GiteaProjectsManager.getInstance(project).knownRepositoriesState.value.sortedBy { it.repository.path.toString() }

  /** The project's connection; right after the IDE started it may still be opening (the PR tool window's model opens it). */
  private suspend fun awaitConnection(): GiteaProjectConnection? {
    val manager = GiteaProjectConnectionManager.getInstance(project)
    manager.connectionState.value?.let { return it }
    GiteaToolWindowProjectViewModel.getInstance(project)
    return withTimeoutOrNull(CONNECT_TIMEOUT) { manager.connectionState.filterNotNull().first() }
  }

  private fun accountFor(mapping: GiteaProjectMapping): GiteaAccount? {
    val server = mapping.repository.serverPath
    val default = GiteaProjectDefaultAccountHolder.getInstance(project).account
    return default?.takeIf { it.server == server }
           ?: GiteaAccountManager.getInstance().accountsState.value.firstOrNull { it.server == server }
  }

  private fun names(mappings: List<GiteaProjectMapping>) = mappings.map { it.repository.path.toString() }.distinct().joinToString()

  private companion object {
    val CONNECT_TIMEOUT = 5.seconds
  }
}
