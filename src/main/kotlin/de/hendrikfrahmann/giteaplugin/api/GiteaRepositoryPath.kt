package de.hendrikfrahmann.giteaplugin.api

import com.intellij.openapi.util.NlsSafe
import java.net.URI

/** `owner/name` of a repository on some Gitea server. */
data class GiteaRepositoryPath(val owner: String, val name: String) {
  @NlsSafe
  override fun toString(): String = "$owner/$name"

  companion object {
    /** Parses `owner/name`; `null` for anything else. */
    fun parse(fullName: String): GiteaRepositoryPath? {
      val parts = fullName.trim().trim('/').split('/')
      if (parts.size != 2 || parts.any { it.isBlank() }) {
        return null
      }
      return GiteaRepositoryPath(parts[0], parts[1])
    }
  }
}

/** `/api/v1/repos/{owner}/{repo}/<segments…>` */
fun GiteaServerPath.repoApiUri(repository: GiteaRepositoryPath, vararg pathSegments: String): URI =
  apiUri("repos", repository.owner, repository.name, *pathSegments)
