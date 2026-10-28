package de.hendrikfrahmann.giteaplugin.repository

import com.intellij.openapi.util.NlsSafe
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.hosting.HostedGitRepositoryMapping
import git4idea.remote.hosting.HostedRepositoryCoordinates
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.ui.branch.GitRepositoryMappingData
import java.net.URI

/** A repository on a Gitea server. */
data class GiteaRepositoryCoordinates(override val serverPath: GiteaServerPath, val path: GiteaRepositoryPath)
  : HostedRepositoryCoordinates {

  override fun getWebURI(): URI = serverPath.webUri(path.owner, path.name)

  @NlsSafe
  override fun toString(): String = "$serverPath/$path"

  companion object {
    fun create(server: GiteaServerPath, remoteUrl: String): GiteaRepositoryCoordinates? =
      GiteaRemoteUrl.parse(remoteUrl)?.repositoryPath?.let { GiteaRepositoryCoordinates(server, it) }
  }
}

/** A git remote of the project that points to a Gitea repository. */
data class GiteaProjectMapping(override val repository: GiteaRepositoryCoordinates, override val remote: GitRemoteUrlCoordinates)
  : HostedGitRepositoryMapping, GitRepositoryMappingData {
  override val gitRemote: GitRemote get() = remote.remote
  override val gitRepository: GitRepository get() = remote.repository
  /** `owner/name` – shown in git4idea's merge direction chooser (create PR). */
  override val repositoryPath: @NlsSafe String get() = repository.path.toString()

  @NlsSafe
  override fun toString(): String = "$repository (${remote.remote.name}: ${remote.url})"

  companion object {
    fun create(server: GiteaServerPath, remote: GitRemoteUrlCoordinates): GiteaProjectMapping? =
      GiteaRepositoryCoordinates.create(server, remote.url)?.let { GiteaProjectMapping(it, remote) }
  }
}

/**
 * The parts of a git remote URL we need: `https://host[:port]/[prefix/]owner/repo[.git]`,
 * `ssh://git@host[:port]/owner/repo.git` and scp-like `git@host:owner/repo.git`.
 *
 * Gitea repositories are always `owner/name`, so the last two path segments are the repository and anything before
 * them is the server's path prefix (subpath installs).
 */
data class GiteaRemoteUrl(
  /** `https`, `http`, `ssh` (also for scp-like URLs), … */
  val scheme: String,
  /** Lower case. */
  val host: String,
  val port: Int,
  val pathPrefix: String,
  val repositoryPath: GiteaRepositoryPath,
) {
  val isHttp: Boolean get() = scheme == "http" || scheme == "https"

  companion object {
    private val SCP_LIKE = Regex("""^(?:[^@/\s]+@)?([^:/\s]+):(?!//)(.+)$""")

    fun parse(url: String): GiteaRemoteUrl? {
      val trimmed = url.trim()
      val parts = when {
        trimmed.contains("://") -> uriParts(trimmed)
        else -> scpLikeParts(trimmed)
      } ?: return null
      val segments = parts.path.split('/').filter { it.isNotEmpty() }
      if (segments.size < 2) {
        return null
      }
      val name = segments.last().removeSuffix(".git")
      val owner = segments[segments.size - 2]
      if (name.isEmpty()) {
        return null
      }
      return GiteaRemoteUrl(parts.scheme, parts.host.lowercase(), parts.port, segments.dropLast(2).joinToString("/"),
                            GiteaRepositoryPath(owner, name))
    }

    private fun uriParts(url: String): Parts? {
      val uri = runCatching { URI(url) }.getOrNull() ?: return null
      return Parts(uri.scheme?.lowercase() ?: return null, uri.host ?: return null, uri.port, uri.path ?: return null)
    }

    /** `[user@]host:owner/repo.git` */
    private fun scpLikeParts(url: String): Parts? {
      val match = SCP_LIKE.matchEntire(url) ?: return null
      return Parts("ssh", match.groupValues[1], -1, match.groupValues[2])
    }

    private data class Parts(val scheme: String, val host: String, val port: Int, val path: String)
  }
}
