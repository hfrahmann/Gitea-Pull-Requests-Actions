package de.hendrikfrahmann.giteaplugin.repository

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaServerMetadata
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaRepositoryDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import git4idea.remote.hosting.HostedGitRepositoryConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import java.util.UUID

/**
 * An open connection to a Gitea repository with an account – what the PR and Actions tool windows work with.
 * Lives until [close] or until its repository mapping, account or credentials disappear.
 * An [isAnonymous] connection reads a public repository without a login: [currentUser] is `null`, nothing can be changed.
 */
class GiteaProjectConnection(
  /** Cancelled when the connection closes; feature models are created in it. */
  val scope: CoroutineScope,
  override val repo: GiteaProjectMapping,
  override val account: GiteaAccount,
  val api: GiteaApi,
  /** `null` for an [isAnonymous] connection. */
  val currentUser: GiteaUserDTO?,
  /** `GET /repos/{o}/{r}` at connect time: permissions, default branch, merge settings, `has_actions`, … */
  val repository: GiteaRepositoryDTO,
  val serverMetadata: GiteaServerMetadata,
) : HostedGitRepositoryConnection<GiteaProjectMapping, GiteaAccount> {
  val id: String = UUID.randomUUID().toString()

  /** Read-only: no account for the server, see [GiteaAccount.anonymous]. */
  val isAnonymous: Boolean get() = account.isAnonymous

  override suspend fun close() {
    runCatching { scopeJob().cancelAndJoin() }
  }

  override suspend fun awaitClose() {
    runCatching { scopeJob().join() }
  }

  private fun scopeJob(): Job = scope.coroutineContext[Job] ?: error("The connection scope has no job")
}
