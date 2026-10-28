package de.hendrikfrahmann.giteaplugin.actions.workflow

import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnectionManager
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectMapping
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import git4idea.repo.GitRepository

/** The Gitea repositories behind the remotes of a git repository, for runs started from a workflow file. */
object GiteaWorkflowRemotes {
  /** Gitea remotes of [repository]; `origin` first. */
  fun mappings(project: Project, repository: GitRepository): List<GiteaProjectMapping> =
    GiteaProjectsManager.getInstance(project).knownRepositoriesState.value
      .filter { it.gitRepository == repository }
      .sortedWith(compareBy({ it.gitRemote.name != "origin" }, { it.gitRemote.name }))

  /** An API client with credentials for [mapping]: the open connection's, else an account on its server; `null` without one. */
  fun api(project: Project, mapping: GiteaProjectMapping): GiteaApi? {
    val connection = GiteaProjectConnectionManager.getInstance(project).connectionState.value
    if (connection != null && connection.repo == mapping && !connection.isAnonymous) {
      return connection.api
    }
    val accounts = GiteaAccountManager.getInstance().accountsState.value.filter { it.server == mapping.repository.serverPath }
    val account = connection?.account?.takeIf { it in accounts } ?: accounts.firstOrNull() ?: return null
    return GiteaApiManager.getInstance().getClient(account)
  }
}
