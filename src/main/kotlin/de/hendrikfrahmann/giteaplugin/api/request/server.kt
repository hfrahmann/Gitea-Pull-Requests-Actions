package de.hendrikfrahmann.giteaplugin.api.request

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaServerMetadata
import de.hendrikfrahmann.giteaplugin.api.GiteaVersion
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaServerVersionDTO
import de.hendrikfrahmann.giteaplugin.api.getJson

/** `GET /version` – public unless the server requires sign-in for everything. */
suspend fun GiteaApi.getServerVersion(): GiteaServerVersionDTO =
  getJson(server.apiUri("version"))

/** Uncached – use [GiteaApi.getMetadata] instead. */
suspend fun GiteaApi.loadServerMetadata(): GiteaServerMetadata =
  GiteaServerMetadata(GiteaVersion.parse(getServerVersion().version))
