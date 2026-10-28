package de.hendrikfrahmann.giteaplugin.api

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.PlatformHttpClient
import com.intellij.util.net.ssl.CertificateManager
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates [GiteaApi] clients. All clients share one [HttpClient] that uses the IDE's proxy settings and
 * trusted certificates (`Settings → Tools → Server Certificates`), so self-hosted Gitea with a custom CA works.
 */
@Service(Service.Level.APP)
class GiteaApiManager {
  @Volatile
  private var client: Pair<Int, HttpClient>? = null

  /** Rebuilt when the connection timeout setting changes; existing [GiteaApi]s keep theirs until they reconnect. */
  private val httpClient: HttpClient
    get() {
      val timeout = GiteaSettings.getInstance().connectionTimeoutSeconds
      client?.takeIf { it.first == timeout }?.let { return it.second }
      return createPlatformHttpClient(timeout).also { client = timeout to it }
    }
  private val metadataCache = GiteaServerMetadataCache()
  private val userAgent: String by lazy { buildUserAgent() }

  /**
   * Client that asks [credentialsSupplier] before each request, so refreshed tokens are picked up.
   * On `401`, [onUnauthorized] may renew the credentials (returns `true`) and the request is retried once.
   */
  fun getClient(
    server: GiteaServerPath,
    onUnauthorized: (suspend () -> Boolean)? = null,
    credentialsSupplier: suspend () -> GiteaCredentials,
  ): GiteaApi =
    GiteaApiImpl(server, httpClient, credentialsSupplier, userAgent, metadataCache::getMetadata, unauthorizedHandler = onUnauthorized)

  fun getClient(server: GiteaServerPath, credentials: GiteaCredentials): GiteaApi = getClient(server) { credentials }

  /** For requests that need no login (`/version`, OAuth token exchange). */
  fun getUnauthenticatedClient(server: GiteaServerPath): GiteaApi =
    GiteaApiImpl(server, httpClient, null, userAgent, metadataCache::getMetadata)

  /** Forget cached metadata, e.g. after the user re-logs in to an upgraded server. */
  fun invalidateMetadata(server: GiteaServerPath) = metadataCache.invalidate(server)

  companion object {
    fun getInstance(): GiteaApiManager = service()

    private fun createPlatformHttpClient(connectTimeoutSeconds: Int): HttpClient =
      PlatformHttpClient.clientBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        // GiteaApiImpl follows redirects itself, so credentials never go to another host
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds.toLong()))
        .proxy(JdkProxyProvider.getInstance().proxySelector)
        .authenticator(JdkProxyProvider.getInstance().authenticator)
        .sslContext(CertificateManager.getInstance().sslContext)
        .build()

    private fun buildUserAgent(): String {
      // PluginManagerCore.getPlugin() is internal; our own class loader knows the descriptor
      val pluginVersion = (GiteaApiManager::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.version ?: "dev"
      val ide = ApplicationInfo.getInstance().build.asStringWithoutProductCode()
      return "Gitea-IntelliJ/$pluginVersion (IntelliJ/$ide)"
    }
  }
}

/** `GET /version` results per server; a server is asked once per IDE session unless [invalidate]d. */
internal class GiteaServerMetadataCache(
  private val loader: suspend (GiteaApi) -> GiteaServerMetadata = { it.loadServerMetadata() },
) {
  private val cache = ConcurrentHashMap<GiteaServerPath, GiteaServerMetadata>()
  private val guard = Mutex()

  suspend fun getMetadata(api: GiteaApi): GiteaServerMetadata {
    cache[api.server]?.let { return it }
    return guard.withLock {
      cache[api.server] ?: loader(api).also { cache[api.server] = it }
    }
  }

  fun invalidate(server: GiteaServerPath) {
    cache.remove(server)
  }
}
