package de.hendrikfrahmann.giteaplugin.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Date

data class GiteaUserDTO(
  val id: Long,
  val login: String,
  val fullName: String? = null,
  val email: String? = null,
  val avatarUrl: String? = null,
  val htmlUrl: String? = null,
  @param:JsonProperty("is_admin") val isAdmin: Boolean = false,
  val active: Boolean = true,
  val restricted: Boolean = false,
  val prohibitLogin: Boolean = false,
  /** `"user"`, `"organization"`, `"bot"` … */
  val type: String? = null,
  val visibility: String? = null,
  val created: Date? = null,
  val lastLogin: Date? = null,
)

data class GiteaOrganizationDTO(
  val id: Long,
  /** Login name of the organization (`name` is an alias in the API). */
  val username: String? = null,
  val name: String? = null,
  val fullName: String? = null,
  val avatarUrl: String? = null,
  val description: String? = null,
  val visibility: String? = null,
)

data class GiteaTeamDTO(
  val id: Long,
  val name: String,
  val description: String? = null,
  val permission: String? = null,
  val organization: GiteaOrganizationDTO? = null,
)

/** `GET /token` */
data class GiteaAccessTokenDTO(
  val id: Long = 0,
  val name: String? = null,
  /** e.g. `all`, `write:repository`, `read:user` */
  val scopes: List<String> = emptyList(),
)
