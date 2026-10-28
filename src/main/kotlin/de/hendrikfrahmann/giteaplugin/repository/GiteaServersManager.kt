package de.hendrikfrahmann.giteaplugin.repository

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private val LOG = logger<GiteaServersManager>()

/**
 * Finds out whether a host serves Gitea by probing `GET <candidate>/api/v1/version` (unauthenticated).
 * Results – positive and negative – are cached for the IDE session, so a host like github.com is asked at most once
 * per candidate URL; a few well-known non-Gitea hosts are never asked.
 */
@Service(Service.Level.APP)
class GiteaServersManager(private val cs: CoroutineScope) {
  private val defaultProbe: suspend (GiteaServerPath) -> Boolean = { server ->
    withTimeout(PROBE_TIMEOUT) { GiteaApiManager.getInstance().getUnauthenticatedClient(server).loadServerMetadata() }
    true
  }

  // Replaceable in tests – call resetProbe() afterwards
  internal var probe: suspend (GiteaServerPath) -> Boolean = defaultProbe

  internal fun resetProbe() {
    probe = defaultProbe
    cache.clear()
  }

  private val cache = ConcurrentHashMap<GiteaServerPath, Deferred<Boolean>>()

  suspend fun isGiteaServer(server: GiteaServerPath): Boolean =
    cache.computeIfAbsent(server) {
      cs.async(Dispatchers.IO) {
        try {
          probe(server).also { LOG.debug { "Probed $server: Gitea=$it" } }
        }
        catch (e: CancellationException) {
          throw e
        }
        catch (e: Exception) {
          LOG.debug { "Probed $server: not Gitea (${e.javaClass.simpleName}: ${e.message})" }
          false
        }
      }
    }.await()

  /** The first [GiteaServerResolver.candidates] of [remoteUrl] that serves Gitea. */
  suspend fun discoverServer(remoteUrl: String): GiteaServerPath? {
    val remote = GiteaRemoteUrl.parse(remoteUrl) ?: return null
    if (remote.host in WELL_KNOWN_NON_GITEA_HOSTS) {
      return null
    }
    return GiteaServerResolver.candidates(remote).firstOrNull { isGiteaServer(it) }
  }

  companion object {
    private val PROBE_TIMEOUT = 5.seconds

    val WELL_KNOWN_NON_GITEA_HOSTS: Set<String> = setOf(
      "github.com", "gitlab.com", "bitbucket.org", "dev.azure.com", "ssh.dev.azure.com", "vs-ssh.visualstudio.com",
      "codeberg.org", // Forgejo – older Gitea API than we support
    )

    fun getInstance(): GiteaServersManager = service()
  }
}

/** Pure matching logic between git remotes and Gitea servers. */
object GiteaServerResolver {
  /**
   * The Gitea server of [remoteUrl]: a [mappings] entry for its host wins, else the [knownServers] entry that serves it
   * (same host; for http(s) remotes also same port and path prefix). `null` if none.
   */
  fun resolve(remoteUrl: String, knownServers: Collection<GiteaServerPath>, mappings: GiteaHostMappings): GiteaServerPath? {
    val remote = GiteaRemoteUrl.parse(remoteUrl) ?: return null
    mappings.serverFor(remote.host)?.let { return it }
    return knownServers.firstOrNull { serves(it, remote) }
  }

  fun serves(server: GiteaServerPath, remote: GiteaRemoteUrl): Boolean {
    if (!server.host.equals(remote.host, ignoreCase = true)) {
      return false
    }
    if (!remote.isHttp) {
      return true // SSH: port and path say nothing about the web server
    }
    return effectivePort(server.scheme, server.port) == effectivePort(remote.scheme, remote.port) &&
           server.pathPrefix.trim('/') == remote.pathPrefix
  }

  /** Server URLs to probe for an unknown remote, most likely first. */
  fun candidates(remote: GiteaRemoteUrl): List<GiteaServerPath> {
    val prefix = remote.pathPrefix.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
    val urls = buildList {
      if (remote.isHttp) {
        val port = remote.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
        add("${remote.scheme}://${remote.host}$port$prefix")
      }
      add("https://${remote.host}$prefix")
      add("http://${remote.host}$prefix")
      // Gitea's default HTTP port, typical for SSH remotes of small self-hosted instances
      if (!remote.isHttp) {
        add("http://${remote.host}:$GITEA_DEFAULT_HTTP_PORT$prefix")
      }
    }
    return urls.distinct().mapNotNull { runCatching { GiteaServerPath(it) }.getOrNull() }.distinct()
  }

  /** Host of a git URL as git passes it to credential providers (`https://host[:port]` or a full remote URL). */
  fun hostOf(gitUrl: String): String? =
    GiteaRemoteUrl.parse(gitUrl)?.host
    ?: runCatching { URI(gitUrl.trim()).host?.lowercase() }.getOrNull()

  private const val GITEA_DEFAULT_HTTP_PORT = 3000

  private fun effectivePort(scheme: String, port: Int): Int = when {
    port != -1 -> port
    scheme == "https" -> 443
    scheme == "http" -> 80
    else -> -1
  }
}
