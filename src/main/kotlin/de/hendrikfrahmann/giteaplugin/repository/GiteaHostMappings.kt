package de.hendrikfrahmann.giteaplugin.repository

import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath

/**
 * Remote hosts that are Gitea without probing, and where their API lives.
 *
 * One entry per line (or comma-separated):
 * - `git.example.com` – Gitea at `https://git.example.com`
 * - `example.org=http://127.0.0.1:3000` – remotes on `example.org` (clone URLs from `ROOT_URL`, or an SSH host)
 *   belong to the Gitea reached at `http://127.0.0.1:3000`
 *
 * Hosts are matched case-insensitively and without port (an SSH port differs from the web port anyway).
 */
class GiteaHostMappings private constructor(private val servers: Map<String, GiteaServerPath>) {
  val hosts: Set<String> get() = servers.keys

  fun serverFor(host: String?): GiteaServerPath? = host?.let { servers[it.lowercase()] }

  override fun equals(other: Any?): Boolean = other is GiteaHostMappings && servers == other.servers
  override fun hashCode(): Int = servers.hashCode()
  override fun toString(): String = servers.toString()

  companion object {
    val EMPTY: GiteaHostMappings = GiteaHostMappings(emptyMap())

    fun parse(text: String): GiteaHostMappings = GiteaHostMappings(
      parseEntries(text).mapNotNull { it.getOrNull() }.toMap()
    )

    /** Each entry → `host to server`, or a failure carrying the invalid entry (for validation in the settings page). */
    fun parseEntries(text: String): List<Result<Pair<String, GiteaServerPath>>> =
      text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { entry ->
        runCatching {
          val host = entry.substringBefore('=').trim().lowercase()
          require(host.isNotEmpty() && host.none { it.isWhitespace() || it == '/' || it == ':' }) { entry }
          val server = entry.substringAfter('=', "").trim().ifEmpty { host }
          host to GiteaServerPath.fromUserInput(server)
        }.recoverCatching { throw IllegalArgumentException(entry, it) }
      }
  }
}
