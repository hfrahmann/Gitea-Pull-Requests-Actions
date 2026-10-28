package de.hendrikfrahmann.giteaplugin.api.request

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaIssueDTO
import de.hendrikfrahmann.giteaplugin.api.getJsonList
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.page
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.sendIgnoringBody
import de.hendrikfrahmann.giteaplugin.api.withQuery

/**
 * `GET /repos/{o}/{r}/issues?type=issues` – issues only, newest first. [text] is Gitea's issue search (whole
 * words in title and body), [assignedTo] a login.
 */
suspend fun GiteaApi.loadIssuesPage(repository: GiteaRepositoryPath, withClosed: Boolean, text: String?, assignedTo: String?,
                                    page: Int, limit: Int): List<GiteaIssueDTO> {
  val state = when {
    withClosed -> "all"
    else -> "open"
  }
  return getJsonList<GiteaIssueDTO>(server.repoApiUri(repository, "issues").withQuery {
    "type" eq "issues"
    "state" eq state
    "q" eq text?.takeIf { it.isNotBlank() }
    "assigned_by" eq assignedTo
    page(page, limit)
  }).body
}

private data class GiteaIssueStateRequest(val state: String)

/** `PATCH /repos/{o}/{r}/issues/{index}` with only `state` (`open` / `closed`). */
suspend fun GiteaApi.setIssueState(repository: GiteaRepositoryPath, number: Long, open: Boolean) {
  val state = when {
    open -> "open"
    else -> "closed"
  }
  sendIgnoringBody(request(server.repoApiUri(repository, "issues", number.toString())).jsonBody("PATCH", GiteaIssueStateRequest(state)).build())
}
