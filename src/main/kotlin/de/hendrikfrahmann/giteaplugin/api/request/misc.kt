package de.hendrikfrahmann.giteaplugin.api.request

import com.fasterxml.jackson.annotation.JsonProperty
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaIssueDTO
import de.hendrikfrahmann.giteaplugin.api.getJson
import de.hendrikfrahmann.giteaplugin.api.getJsonList
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.loadText
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.withQuery

/** Gitea binds this body case-sensitively: `text`/`mode` are ignored, it needs `Text`/`Mode`/`Context` (verified on 28.1). */
private data class GiteaMarkdownRequest(
  @get:JsonProperty("Text") val text: String,
  @get:JsonProperty("Mode") val mode: String,
  @get:JsonProperty("Context") val context: String?,
)

/**
 * `POST /markdown` – Gitea's own rendering (GFM, `#123` and `@user` links resolved in [context]).
 * Links in the result are absolute and use the server's `ROOT_URL`.
 */
suspend fun GiteaApi.renderMarkdown(text: String, context: GiteaRepositoryPath?): String =
  loadText(request(server.apiUri("markdown")).jsonBody("POST", GiteaMarkdownRequest(text, "gfm", context?.toString())).build())

/** `GET /repos/{o}/{r}/issues?state=all&q=…` – issues and PRs for `#` completion (newest first; [text] `null` = latest). */
suspend fun GiteaApi.searchIssues(repository: GiteaRepositoryPath, text: String?, limit: Int = 20): List<GiteaIssueDTO> =
  getJsonList<GiteaIssueDTO>(server.repoApiUri(repository, "issues").withQuery {
    "state" eq "all"
    "q" eq text?.takeIf { it.isNotBlank() }
    "limit" eq limit
  }).body

/** `GET /repos/{o}/{r}/issues/{index}` – an issue or PR by number. */
suspend fun GiteaApi.getIssue(repository: GiteaRepositoryPath, number: Long): GiteaIssueDTO =
  getJson(server.repoApiUri(repository, "issues", number.toString()))
