package de.hendrikfrahmann.giteaplugin.api.dto

import java.util.Date

/** `GET /repos/{o}/{r}/commits`, `…/pulls/{index}/commits` */
data class GiteaCommitDTO(
  val sha: String,
  val htmlUrl: String? = null,
  val commit: GiteaRepoCommitDTO? = null,
  /** Gitea user of the author, `null` if the email is not linked to an account. */
  val author: GiteaUserDTO? = null,
  val committer: GiteaUserDTO? = null,
  val parents: List<GiteaCommitMetaDTO> = emptyList(),
  val files: List<GiteaCommitAffectedFileDTO> = emptyList(),
  val stats: GiteaCommitStatsDTO? = null,
  val created: Date? = null,
)

data class GiteaRepoCommitDTO(
  val message: String = "",
  val author: GiteaCommitUserDTO? = null,
  val committer: GiteaCommitUserDTO? = null,
  val tree: GiteaCommitMetaDTO? = null,
)

data class GiteaCommitUserDTO(
  val name: String? = null,
  val email: String? = null,
  /** ISO-8601 string in the API (not typed as date-time in swagger). */
  val date: String? = null,
)

data class GiteaCommitMetaDTO(
  val sha: String,
  val url: String? = null,
  val created: Date? = null,
)

data class GiteaCommitAffectedFileDTO(
  val filename: String,
  val status: String? = null,
)

data class GiteaCommitStatsDTO(
  val additions: Long = 0,
  val deletions: Long = 0,
  val total: Long = 0,
)
