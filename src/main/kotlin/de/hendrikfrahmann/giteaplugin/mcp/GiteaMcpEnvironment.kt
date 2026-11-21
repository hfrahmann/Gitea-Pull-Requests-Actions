package de.hendrikfrahmann.giteaplugin.mcp

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestReviewData

/** A refused tool call; [message] goes to the assistant as the tool's error text. */
class GiteaMcpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A repository of the project, as `giteapr_list_repositories` reports it. */
data class GiteaMcpRepository(
  val repository: GiteaRepositoryPath,
  val server: String,
  val remoteName: String,
  val remoteUrl: String,
  val root: String,
  /** The repository the Gitea tool windows are connected to (review mode works only there). */
  val connected: Boolean,
  /** Login of the account used for it; `null` = read anonymously. */
  val account: String?,
)

/**
 * Where a tool call goes: a repository and the API client of its account.
 * Only the connected repository has [reviewData] – the IDE's own pending review, shown in diffs and editors.
 */
class GiteaMcpTarget(
  val api: GiteaApi,
  val repository: GiteaRepositoryPath,
  /** No account for the server: read-only. */
  val isAnonymous: Boolean,
  /** The IDE's review data of a PR of this repository; `null` when it isn't the connected one. */
  val reviewData: ((number: Long) -> GiteaPullRequestReviewData)? = null,
  /** Reloads the PR's open views after a change; does nothing when it isn't shown. */
  val onPullRequestChanged: (number: Long) -> Unit = {},
)

/** What the MCP tools need from the IDE: the two settings and the project's repositories. Tests provide their own. */
interface GiteaMcpEnvironment {
  /** "Offer Gitea tools to the IDE's MCP server". */
  val toolsEnabled: Boolean

  /** "Also allow changing pull requests and runs". */
  val writeToolsEnabled: Boolean

  suspend fun repositories(): List<GiteaMcpRepository>

  /**
   * The target for [repository] (`owner/name`; `null` = the connected repository, or the only one of the project).
   * Throws [GiteaMcpException] when there is no such repository.
   */
  suspend fun target(repository: String?): GiteaMcpTarget
}

/** The setting gates, checked on every call: the IDE builds its tool list once per client session. */
internal object GiteaMcpGates {
  const val SETTINGS_PATH: String = "Settings | Version Control | Gitea | Settings"

  fun checkRead(env: GiteaMcpEnvironment) {
    if (!env.toolsEnabled) {
      throw GiteaMcpException("The Gitea tools are turned off. The user can turn them on in $SETTINGS_PATH: " +
                              "\"Offer Gitea tools to the IDE's MCP server\".")
    }
  }

  fun checkWrite(env: GiteaMcpEnvironment, target: GiteaMcpTarget) {
    checkRead(env)
    if (!env.writeToolsEnabled) {
      throw GiteaMcpException("Changing pull requests and runs is turned off – this tool only works when the user turns on " +
                              "\"Also allow changing pull requests and runs\" in $SETTINGS_PATH.")
    }
    if (target.isAnonymous) {
      throw GiteaMcpException("${target.repository} is read without a Gitea account (anonymous connection), so nothing can be changed. " +
                              "The user has to log in to the Gitea server first.")
    }
  }
}
