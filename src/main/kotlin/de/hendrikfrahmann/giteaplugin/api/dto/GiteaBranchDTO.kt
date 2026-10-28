package de.hendrikfrahmann.giteaplugin.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Date

data class GiteaBranchDTO(
  val name: String,
  val commit: GiteaPayloadCommitDTO? = null,
  @param:JsonProperty("protected") val isProtected: Boolean = false,
  val requiredApprovals: Long = 0,
  val enableStatusCheck: Boolean = false,
  val statusCheckContexts: List<String> = emptyList(),
  val userCanPush: Boolean = false,
  val userCanMerge: Boolean = false,
  val effectiveBranchProtectionName: String? = null,
)

/** Commit as embedded in branches and push payloads. */
data class GiteaPayloadCommitDTO(
  /** SHA */
  val id: String,
  val message: String? = null,
  val url: String? = null,
  val author: GiteaPayloadUserDTO? = null,
  val committer: GiteaPayloadUserDTO? = null,
  val timestamp: Date? = null,
)

data class GiteaPayloadUserDTO(
  val name: String? = null,
  val email: String? = null,
  val username: String? = null,
)
