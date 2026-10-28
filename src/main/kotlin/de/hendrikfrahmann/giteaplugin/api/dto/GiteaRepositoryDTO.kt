package de.hendrikfrahmann.giteaplugin.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Date

data class GiteaRepositoryDTO(
  val id: Long,
  val name: String,
  /** `owner/name` */
  val fullName: String,
  val owner: GiteaUserDTO? = null,
  val description: String? = null,
  @param:JsonProperty("private") val isPrivate: Boolean = false,
  @param:JsonProperty("internal") val isInternal: Boolean = false,
  @param:JsonProperty("fork") val isFork: Boolean = false,
  @param:JsonProperty("mirror") val isMirror: Boolean = false,
  @param:JsonProperty("template") val isTemplate: Boolean = false,
  @param:JsonProperty("archived") val isArchived: Boolean = false,
  @param:JsonProperty("empty") val isEmpty: Boolean = false,
  val parent: GiteaRepositoryDTO? = null,
  val defaultBranch: String? = null,
  /** ⚠ Built from the server's `ROOT_URL` – rebase with [de.hendrikfrahmann.giteaplugin.api.GiteaServerPath.rebase]. */
  val htmlUrl: String? = null,
  val cloneUrl: String? = null,
  val sshUrl: String? = null,
  val website: String? = null,
  val permissions: GiteaPermissionDTO? = null,
  val hasIssues: Boolean = false,
  val hasPullRequests: Boolean = false,
  val hasActions: Boolean = false,
  val hasWiki: Boolean = false,
  val hasReleases: Boolean = false,
  val hasPackages: Boolean = false,
  val hasProjects: Boolean = false,
  // Merge settings
  val allowMergeCommits: Boolean = false,
  val allowRebase: Boolean = false,
  val allowRebaseExplicit: Boolean = false,
  val allowSquashMerge: Boolean = false,
  val allowFastForwardOnlyMerge: Boolean = false,
  val allowManualMerge: Boolean = false,
  val allowMergeUpdate: Boolean = false,
  val allowRebaseUpdate: Boolean = false,
  val autodetectManualMerge: Boolean = false,
  val defaultMergeStyle: String? = null,
  val defaultUpdateStyle: String? = null,
  val defaultDeleteBranchAfterMerge: Boolean = false,
  val defaultAllowMaintainerEdit: Boolean = false,
  val ignoreWhitespaceConflicts: Boolean = false,
  val defaultTargetBranch: String? = null,
  val objectFormatName: String? = null,
  val openIssuesCount: Long = 0,
  val openPrCounter: Long = 0,
  val starsCount: Long = 0,
  val forksCount: Long = 0,
  val size: Long = 0,
  val topics: List<String> = emptyList(),
  val createdAt: Date? = null,
  val updatedAt: Date? = null,
)

/** The calling user's permissions on a repository. */
data class GiteaPermissionDTO(
  val admin: Boolean = false,
  val push: Boolean = false,
  val pull: Boolean = false,
)
