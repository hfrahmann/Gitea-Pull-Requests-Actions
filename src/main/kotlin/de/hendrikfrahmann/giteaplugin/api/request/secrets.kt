package de.hendrikfrahmann.giteaplugin.api.request

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaPagination
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaOrganizationPermissionsDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaSecretDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaVariableDTO
import de.hendrikfrahmann.giteaplugin.api.getJson
import de.hendrikfrahmann.giteaplugin.api.getJsonList
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.page
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.sendIgnoringBody
import de.hendrikfrahmann.giteaplugin.api.withQuery
import java.net.URI

/*
 * Actions secrets and variables, verified on 28.1 for all three levels: names are stored upper-case and
 * matched case-insensitively; `GITEA_` and `GITHUB_` prefixes and invalid characters → 400 with a readable message;
 * empty values → 422; an existing variable name → 409; deleting a missing one → 404.
 */

/** Where secrets and variables are kept; workflows of the repository see all three levels. */
sealed interface GiteaActionsScope {
  /** Repository admin only: a `write` collaborator gets 403 "user should be the owner of the repo". */
  data class Repository(val path: GiteaRepositoryPath) : GiteaActionsScope

  /** Organization owners only: members of an admin team get 403 "Must be an organization owner" too. */
  data class Organization(val name: String) : GiteaActionsScope

  /**
   * The calling user's own account – variables only: 28.1 has `PUT`/`DELETE /user/actions/secrets/{name}` but no
   * list (404), so the plugin doesn't offer user secrets.
   */
  data object User : GiteaActionsScope
}

private fun GiteaServerPath.actionsApiUri(scope: GiteaActionsScope, vararg pathSegments: String): URI = when (scope) {
  is GiteaActionsScope.Repository -> repoApiUri(scope.path, "actions", *pathSegments)
  is GiteaActionsScope.Organization -> apiUri("orgs", scope.name, "actions", *pathSegments)
  GiteaActionsScope.User -> apiUri("user", "actions", *pathSegments)
}

/** `GET …/actions/secrets` – names and descriptions; values are never returned. Not for [GiteaActionsScope.User] (404). */
suspend fun GiteaApi.loadSecrets(scope: GiteaActionsScope): List<GiteaSecretDTO> =
  GiteaPagination.loadAllPages { page, limit -> getJsonList(server.actionsApiUri(scope, "secrets").withQuery { page(page, limit) }) }

private data class GiteaSecretRequest(val data: String, val description: String?)

/** `PUT …/actions/secrets/{name}` – creates (201) or overwrites (204); `true` if it was created. */
suspend fun GiteaApi.putSecret(scope: GiteaActionsScope, name: String, value: String, description: String?): Boolean =
  sendIgnoringBody(request(server.actionsApiUri(scope, "secrets", name)).jsonBody("PUT", GiteaSecretRequest(value, description)).build())
    .statusCode == 201

/** `DELETE …/actions/secrets/{name}` */
suspend fun GiteaApi.deleteSecret(scope: GiteaActionsScope, name: String) {
  sendIgnoringBody(request(server.actionsApiUri(scope, "secrets", name)).DELETE().build())
}

/** `GET …/actions/variables` – with values (`data`). */
suspend fun GiteaApi.loadVariables(scope: GiteaActionsScope): List<GiteaVariableDTO> =
  GiteaPagination.loadAllPages { page, limit -> getJsonList(server.actionsApiUri(scope, "variables").withQuery { page(page, limit) }) }

/** Body of create (`POST`) and update (`PUT`, [name] renames); the API takes `value` but lists `data`. */
private data class GiteaVariableRequest(val value: String, val description: String?, val name: String? = null)

/** `POST …/actions/variables/{name}` → 201; 409 if the name exists. */
suspend fun GiteaApi.createVariable(scope: GiteaActionsScope, name: String, value: String, description: String?) {
  sendIgnoringBody(request(server.actionsApiUri(scope, "variables", name)).jsonBody("POST", GiteaVariableRequest(value, description)).build())
}

/** `PUT …/actions/variables/{name}` → 204; [newName] renames the variable. */
suspend fun GiteaApi.updateVariable(scope: GiteaActionsScope, name: String, newName: String?, value: String, description: String?) {
  val body = GiteaVariableRequest(value, description, newName?.takeIf { !it.equals(name, ignoreCase = true) })
  sendIgnoringBody(request(server.actionsApiUri(scope, "variables", name)).jsonBody("PUT", body).build())
}

/** `DELETE …/actions/variables/{name}` */
suspend fun GiteaApi.deleteVariable(scope: GiteaActionsScope, name: String) {
  sendIgnoringBody(request(server.actionsApiUri(scope, "variables", name)).DELETE().build())
}

/** `GET /users/{user}/orgs/{org}/permissions` – all `false` for a non-member (and for a user instead of an org). */
suspend fun GiteaApi.loadOrganizationPermissions(user: String, organization: String): GiteaOrganizationPermissionsDTO =
  getJson(server.apiUri("users", user, "orgs", organization, "permissions"))
