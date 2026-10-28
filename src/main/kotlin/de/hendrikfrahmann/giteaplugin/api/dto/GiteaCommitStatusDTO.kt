package de.hendrikfrahmann.giteaplugin.api.dto

import java.util.Date

/** `GET /repos/{o}/{r}/commits/{ref}/status` */
data class GiteaCombinedStatusDTO(
  /** `pending`, `success`, `error`, `failure`, `warning`, `skipped` */
  val state: String? = null,
  val sha: String? = null,
  val totalCount: Long = 0,
  val statuses: List<GiteaCommitStatusDTO> = emptyList(),
)

data class GiteaCommitStatusDTO(
  val id: Long,
  /** Same values as [GiteaCombinedStatusDTO.state]. */
  val status: String? = null,
  val context: String? = null,
  val description: String? = null,
  val targetUrl: String? = null,
  val creator: GiteaUserDTO? = null,
  val createdAt: Date? = null,
  val updatedAt: Date? = null,
)
