package de.hendrikfrahmann.giteaplugin.pullrequest.data

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.request.loadRawFile

/** Prefilled title and description of a new PR and Gitea's draft prefix (also used to mark PRs as draft). */
object GiteaPullRequestCreateDefaults {
  /**
   * Markdown PR templates in the order Gitea's web UI looks for them (repository root, `.gitea/`, `.github/`, upper and
   * lower case). Gitea's YAML issue forms aren't supported – the API can't fill them in either.
   */
  val TEMPLATE_PATHS: List<String> = listOf("", ".gitea/", ".github/").flatMap { dir ->
    listOf("PULL_REQUEST_TEMPLATE.md", "pull_request_template.md").map { dir + it }
  }

  /** Gitea's default `WORK_IN_PROGRESS_PREFIXES` (`WIP:`, `[WIP]`) – Gitea marks drafts only by the title. */
  private val DRAFT_PREFIX = Regex("^\\s*(WIP:|\\[WIP])\\s*", RegexOption.IGNORE_CASE)

  /** One commit → its subject; else the branch name (like the GitHub/GitLab plugins). [messages]: full commit messages. */
  fun title(messages: List<String>, headBranch: String): String =
    messages.singleOrNull()?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: headBranch

  /** The template when there is one; else, for one commit, the rest of its message. */
  fun description(messages: List<String>, template: String?): String {
    if (!template.isNullOrBlank()) {
      return template
    }
    val message = messages.singleOrNull() ?: return ""
    return message.lines().drop(1).joinToString("\n").trim()
  }

  fun isDraft(title: String): Boolean = DRAFT_PREFIX.containsMatchIn(title)

  /** [title] with or without the `WIP:` prefix. */
  fun withDraft(title: String, draft: Boolean): String {
    val plain = title.replace(DRAFT_PREFIX, "")
    return when {
      draft -> "WIP: $plain"
      else -> plain
    }
  }

  /** First template of [TEMPLATE_PATHS] on [ref] of [repository], `null` if there is none. */
  suspend fun loadTemplate(api: GiteaApi, repository: GiteaRepositoryPath, ref: String): String? =
    TEMPLATE_PATHS.firstNotNullOfOrNull { path -> api.loadRawFile(repository, path, ref)?.takeIf { it.isNotBlank() } }
}
