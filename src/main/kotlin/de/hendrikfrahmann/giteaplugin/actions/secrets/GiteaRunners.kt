package de.hendrikfrahmann.giteaplugin.actions.secrets

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunnerDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.loadInstanceRunners
import de.hendrikfrahmann.giteaplugin.api.request.loadOrganizationRunners
import de.hendrikfrahmann.giteaplugin.api.request.loadRepositoryRunners
import de.hendrikfrahmann.giteaplugin.api.request.loadUserRunners
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection

/** Where a runner is registered; runners of a wider level serve this repository too. */
enum class GiteaRunnerLevel { REPOSITORY, USER, ORGANIZATION, INSTANCE }

/** What the runners table shows: online, busy with a job, offline, or disabled by an admin. */
enum class GiteaRunnerState { ONLINE, BUSY, OFFLINE, DISABLED }

data class GiteaRunner(val level: GiteaRunnerLevel, val id: Long, val name: String, val state: GiteaRunnerState, val ephemeral: Boolean,
                       val labels: List<String>) {
  companion object {
    fun from(level: GiteaRunnerLevel, dto: GiteaActionRunnerDTO) = GiteaRunner(
      level, dto.id, dto.name,
      when {
        dto.disabled -> GiteaRunnerState.DISABLED
        dto.status.equals("offline", ignoreCase = true) -> GiteaRunnerState.OFFLINE
        dto.busy || dto.status.equals("active", ignoreCase = true) -> GiteaRunnerState.BUSY
        else -> GiteaRunnerState.ONLINE
      },
      dto.ephemeral, dto.labels.map { it.name },
    )
  }
}

/**
 * Runners that can run this repository's jobs, from every level the user may read (display only): the
 * repository (repo admin), the owning user (the user themselves) or organization (org admin), and the instance
 * (site admin). Instance-wide runners stay invisible to everyone else – the API offers no other way to see them.
 */
object GiteaRunners {
  private val OPTIONAL_LEVEL_ERRORS = setOf(GiteaHttpStatusError.Kind.FORBIDDEN, GiteaHttpStatusError.Kind.NOT_FOUND)

  /** [repositoryAdmin]: the repository level is read (and required) only for repo admins (the dialog is open to all users). */
  suspend fun load(connection: GiteaProjectConnection, repositoryAdmin: Boolean): List<GiteaRunner> =
    load(connection.api, connection.repo.repository.path, connection.repository.owner, connection.currentUser, repositoryAdmin)

  internal suspend fun load(api: GiteaApi, repository: GiteaRepositoryPath, owner: GiteaUserDTO?, user: GiteaUserDTO?,
                            repositoryAdmin: Boolean = true): List<GiteaRunner> {
    val levels = buildList<Pair<GiteaRunnerLevel, suspend GiteaApi.() -> List<GiteaActionRunnerDTO>>> {
      if (repositoryAdmin) {
        add(GiteaRunnerLevel.REPOSITORY to { loadRepositoryRunners(repository) })
      }
      when {
        owner != null && owner.type.equals("Organization", ignoreCase = true) ->
          add(GiteaRunnerLevel.ORGANIZATION to { loadOrganizationRunners(owner.login) })
        owner != null && owner.login == user?.login -> add(GiteaRunnerLevel.USER to { loadUserRunners() })
      }
      if (user?.isAdmin == true) {
        add(GiteaRunnerLevel.INSTANCE to { loadInstanceRunners() })
      }
    }
    val runners = levels.flatMap { (level, load) ->
      loadLevel(level) { api.load() }.map { GiteaRunner.from(level, it) }
    }
    // the instance list contains every runner: keep the most specific level
    return runners.distinctBy { it.id }.sortedWith(compareBy({ it.level }, { it.name.lowercase() }))
  }

  /** The repository level is required for repo admins; the others are optional (e.g. not an org admin) and empty when forbidden. */
  private inline fun loadLevel(level: GiteaRunnerLevel, load: () -> List<GiteaActionRunnerDTO>): List<GiteaActionRunnerDTO> =
    try {
      load()
    }
    catch (e: GiteaHttpStatusError) {
      val optional = level != GiteaRunnerLevel.REPOSITORY && e.kind in OPTIONAL_LEVEL_ERRORS
      if (!optional) {
        throw e
      }
      emptyList()
    }
}
