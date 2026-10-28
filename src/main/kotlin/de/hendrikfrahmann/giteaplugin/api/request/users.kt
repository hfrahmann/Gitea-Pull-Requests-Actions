package de.hendrikfrahmann.giteaplugin.api.request

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaPagination
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaAccessTokenDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaOrganizationDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.getJson
import de.hendrikfrahmann.giteaplugin.api.getJsonList
import de.hendrikfrahmann.giteaplugin.api.page
import de.hendrikfrahmann.giteaplugin.api.withQuery

/** `GET /user` – the token's owner; also the "test connection" call. */
suspend fun GiteaApi.getCurrentUser(): GiteaUserDTO =
  getJson(server.apiUri("user"))

/** `GET /user/orgs` – all pages. */
suspend fun GiteaApi.loadCurrentUserOrganizations(): List<GiteaOrganizationDTO> =
  GiteaPagination.loadAllPages { page, limit ->
    getJsonList(server.apiUri("user", "orgs").withQuery { page(page, limit) })
  }

/** `GET /token` – the calling token's scopes (Gitea 28+). Fails for OAuth tokens on some servers. */
suspend fun GiteaApi.getCurrentTokenScopes(): List<String> =
  getJson<GiteaAccessTokenDTO>(server.apiUri("token")).scopes
