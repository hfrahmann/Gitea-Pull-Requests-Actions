package de.hendrikfrahmann.giteaplugin.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Date

data class GiteaLabelDTO(
  val id: Long,
  val name: String,
  /** Hex without `#`, e.g. `ee0701`. */
  val color: String? = null,
  val description: String? = null,
  val exclusive: Boolean = false,
  @param:JsonProperty("is_archived") val isArchived: Boolean = false,
)

data class GiteaMilestoneDTO(
  val id: Long,
  val title: String,
  val description: String? = null,
  /** `open` / `closed` */
  val state: String? = null,
  val openIssues: Long = 0,
  val closedIssues: Long = 0,
  val dueOn: Date? = null,
  val createdAt: Date? = null,
  val updatedAt: Date? = null,
  val closedAt: Date? = null,
)
