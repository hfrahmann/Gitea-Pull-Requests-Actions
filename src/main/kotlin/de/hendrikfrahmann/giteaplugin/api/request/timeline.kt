package de.hendrikfrahmann.giteaplugin.api.request

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.GiteaPagination
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCommentDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaTimelineEventDTO
import de.hendrikfrahmann.giteaplugin.api.getJsonList
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.loadJsonValue
import de.hendrikfrahmann.giteaplugin.api.page
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.withQuery

/*
 * Conversation of an issue/PR. Verified on 28.1:
 * - the timeline is in web UI order; inline review comments are not part of it
 * - one review can appear several times (type `review`, same `review_id`)
 * - paging is done before filtering: a page can be shorter than `limit` with more pages following, `X-Total-Count` is
 *   the size of the current page, there is no `Link` header, and the page after the last one is `null`
 */

/** `GET /repos/{o}/{r}/issues/{index}/timeline` – all pages (until an empty one, see above), oldest first. */
suspend fun GiteaApi.loadTimeline(repository: GiteaRepositoryPath, number: Long): List<GiteaTimelineEventDTO> {
  val events = mutableListOf<GiteaTimelineEventDTO>()
  var page = 1
  while (true) {
    val items = getJsonList<GiteaTimelineEventDTO>(server.repoApiUri(repository, "issues", number.toString(), "timeline").withQuery {
      page(page, GiteaPagination.DEFAULT_PAGE_SIZE)
    }).body
    if (items.isEmpty()) {
      return events
    }
    events += items
    page++
  }
}

/** Body of a `pull_push` timeline event; for a force push [commitIds] is `[old head, new head]`. */
data class GiteaPushData(val isForcePush: Boolean = false, val commitIds: List<String> = emptyList()) {
  companion object {
    fun parse(body: String?): GiteaPushData? =
      body?.takeIf { it.isNotBlank() }?.let { runCatching { GiteaJson.mapper.readValue(it, GiteaPushData::class.java) }.getOrNull() }
  }
}

private data class GiteaNewCommentBody(val body: String)

/** `POST /repos/{o}/{r}/issues/{index}/comments` – a conversation comment (PRs too). */
suspend fun GiteaApi.createIssueComment(repository: GiteaRepositoryPath, number: Long, body: String): GiteaCommentDTO =
  loadJsonValue<GiteaCommentDTO>(request(server.repoApiUri(repository, "issues", number.toString(), "comments"))
                                   .jsonBody("POST", GiteaNewCommentBody(body)).build()).body
