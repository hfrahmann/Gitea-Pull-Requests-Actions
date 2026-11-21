package de.hendrikfrahmann.giteaplugin.mcp

import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO

/**
 * Compact JSON for tool results: objects are ordered maps without `null` values (absent = unknown or not set), users
 * are their login, dates ISO-8601 with offset (as [GiteaJson] writes them).
 */
internal object GiteaMcpJson {
  /** A JSON object; `null` values are left out. */
  fun obj(vararg entries: Pair<String, Any?>): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>(entries.size)
    for ((key, value) in entries) {
      if (value != null) {
        map[key] = value
      }
    }
    return map
  }

  fun write(value: Any): String = GiteaJson.toJsonString(value)

  fun login(user: GiteaUserDTO?): String? = user?.login

  /** First line of a commit message. */
  fun subject(message: String?): String? = message?.lineSequence()?.firstOrNull()?.trim()

  fun checkPage(page: Int, limit: Int, maxLimit: Int = MAX_PAGE_SIZE) {
    if (page < 1) {
      throw GiteaMcpException("`page` starts at 1, got $page.")
    }
    if (limit !in 1..maxLimit) {
      throw GiteaMcpException("`limit` must be between 1 and $maxLimit, got $limit.")
    }
  }

  fun checkNumber(number: Long, what: String = "number") {
    if (number <= 0) {
      throw GiteaMcpException("`$what` must be a positive number, got $number.")
    }
  }

  fun checkText(text: String, what: String) {
    if (text.isBlank()) {
      throw GiteaMcpException("`$what` must not be empty.")
    }
  }

  /** Gitea caps list pages at 50. */
  const val MAX_PAGE_SIZE: Int = 50
}
