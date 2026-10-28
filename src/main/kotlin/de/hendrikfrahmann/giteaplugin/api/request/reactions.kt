package de.hendrikfrahmann.giteaplugin.api.request

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReactionDTO
import de.hendrikfrahmann.giteaplugin.api.getJson
import de.hendrikfrahmann.giteaplugin.api.getJsonList
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.sendIgnoringBody

/*
 * Reactions. Verified on 28.1:
 * - review comments use the issue comment endpoints too
 * - adding an existing reaction again is a no-op (200), an empty list is `null`
 * - only the server's `allowed_reactions` (`/settings/ui`) are accepted, others give 403
 */

/** What reactions belong to: a conversation or review comment, or the issue/PR itself (its description). */
sealed interface GiteaReactionTarget {
  data class Comment(val id: Long) : GiteaReactionTarget
  data class Issue(val number: Long) : GiteaReactionTarget
}

private fun GiteaApi.reactionsUri(repository: GiteaRepositoryPath, target: GiteaReactionTarget) = when (target) {
  is GiteaReactionTarget.Comment -> server.repoApiUri(repository, "issues", "comments", target.id.toString(), "reactions")
  is GiteaReactionTarget.Issue -> server.repoApiUri(repository, "issues", target.number.toString(), "reactions")
}

suspend fun GiteaApi.loadReactions(repository: GiteaRepositoryPath, target: GiteaReactionTarget): List<GiteaReactionDTO> =
  getJsonList<GiteaReactionDTO>(reactionsUri(repository, target)).body

private data class GiteaReactionBody(val content: String)

suspend fun GiteaApi.addReaction(repository: GiteaRepositoryPath, target: GiteaReactionTarget, content: String) {
  sendIgnoringBody(request(reactionsUri(repository, target)).jsonBody("POST", GiteaReactionBody(content)).build())
}

suspend fun GiteaApi.removeReaction(repository: GiteaRepositoryPath, target: GiteaReactionTarget, content: String) {
  sendIgnoringBody(request(reactionsUri(repository, target)).jsonBody("DELETE", GiteaReactionBody(content)).build())
}

private data class GiteaUiSettings(val allowedReactions: List<String> = emptyList())

/** `GET /settings/ui` → `allowed_reactions` (server config `[ui] REACTIONS`), in the server's order. */
suspend fun GiteaApi.loadAllowedReactions(): List<String> = getJson<GiteaUiSettings>(server.apiUri("settings", "ui")).allowedReactions
