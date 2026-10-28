package de.hendrikfrahmann.giteaplugin.api

import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import org.jetbrains.annotations.Nls

/** What we know about a server, from `GET /version`. Cached per server by [GiteaApiManager]. */
data class GiteaServerMetadata(val version: GiteaVersion) {

  val isSupported: Boolean get() = version >= MINIMUM_VERSION

  /** Gate for features added after [MINIMUM_VERSION]; everything in 28.0 needs no check. */
  fun supports(feature: GiteaFeature): Boolean = version >= feature.since

  /**
   * @throws GiteaUnsupportedServerException when the server is older than [MINIMUM_VERSION], or a pre-release while
   * [acceptPreReleases] is off (setting "Accept Gitea pre-release versions").
   */
  fun checkSupported(acceptPreReleases: Boolean): GiteaServerMetadata {
    if (!isSupported) {
      throw GiteaUnsupportedServerException(version)
    }
    if (version.isPreRelease && !acceptPreReleases) {
      throw GiteaUnsupportedServerException(version, preRelease = true)
    }
    return this
  }

  companion object {
    val MINIMUM_VERSION: GiteaVersion = GiteaVersion(28, 0, 0)
  }
}

/**
 * Features newer than [GiteaServerMetadata.MINIMUM_VERSION]. Add an entry when a feature needs an endpoint
 * that 28.0 lacks, and check it with [GiteaServerMetadata.supports].
 */
enum class GiteaFeature(val since: GiteaVersion)

class GiteaUnsupportedServerException(val version: GiteaVersion, val preRelease: Boolean = false)
  : RuntimeException("Unsupported Gitea version $version") {
  override fun getLocalizedMessage(): @Nls String = when {
    preRelease -> GiteaBundle.message("api.server.pre.release", version)
    version.isForgejo -> GiteaBundle.message("api.server.unsupported.forgejo", version, GiteaServerMetadata.MINIMUM_VERSION)
    else -> GiteaBundle.message("api.server.unsupported", version, GiteaServerMetadata.MINIMUM_VERSION)
  }
}
