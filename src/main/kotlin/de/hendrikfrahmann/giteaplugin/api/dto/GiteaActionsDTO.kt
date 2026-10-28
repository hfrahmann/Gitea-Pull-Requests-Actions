package de.hendrikfrahmann.giteaplugin.api.dto

import java.util.Date

/** `GET …/actions/workflows` */
data class GiteaActionWorkflowsDTO(
  val totalCount: Long = 0,
  val workflows: List<GiteaActionWorkflowDTO> = emptyList(),
)

data class GiteaActionWorkflowDTO(
  /** The file name, e.g. `ci.yml`. */
  val id: String,
  val name: String? = null,
  val path: String? = null,
  /** `active`, `disabled_manually`, … */
  val state: String? = null,
  val htmlUrl: String? = null,
  val badgeUrl: String? = null,
  val createdAt: Date? = null,
  val updatedAt: Date? = null,
)

/** `GET …/actions/runs`, `…/actions/workflows/{id}/runs` */
data class GiteaActionRunsDTO(
  val totalCount: Long = 0,
  val workflowRuns: List<GiteaActionRunDTO> = emptyList(),
)

data class GiteaActionRunDTO(
  val id: Long,
  val runNumber: Long = 0,
  val runAttempt: Long = 0,
  val displayTitle: String? = null,
  /** `<workflow file>@<ref>`, e.g. `ci.yml@refs/heads/main` */
  val path: String? = null,
  /** `push`, `pull_request`, `workflow_dispatch`, `schedule`, … */
  val event: String? = null,
  /** `queued`, `in_progress`, `completed`, `waiting`, `requested`, `pending` */
  val status: String? = null,
  /** `success`, `failure`, `cancelled`, `skipped`, … – `null` until completed. */
  val conclusion: String? = null,
  /** Branch of `push` runs; missing for `pull_request` runs (verified on 28.1), their [path] has `refs/pull/N/head`. */
  val headBranch: String? = null,
  /** For `pull_request` runs the PR head (not a merge commit, verified on 28.1). */
  val headSha: String? = null,
  val actor: GiteaUserDTO? = null,
  val triggerActor: GiteaUserDTO? = null,
  /** PRs of a `pull_request` run (number and head); empty otherwise. */
  val pullRequests: List<GiteaActionRunPullRequestDTO> = emptyList(),
  /** Set from the second attempt on: `…/runs/{run}/attempts/{n-1}`. */
  val previousAttemptUrl: String? = null,
  val repositoryId: Long = 0,
  val htmlUrl: String? = null,
  val createdAt: Date? = null,
  val startedAt: Date? = null,
  val completedAt: Date? = null,
  val updatedAt: Date? = null,
)

data class GiteaActionRunPullRequestDTO(
  val id: Long = 0,
  val number: Long,
)

/** `GET …/actions/runs/{run}/jobs` */
data class GiteaActionJobsDTO(
  val totalCount: Long = 0,
  val jobs: List<GiteaActionJobDTO> = emptyList(),
)

data class GiteaActionJobDTO(
  val id: Long,
  val runId: Long = 0,
  val runAttempt: Long = 0,
  val name: String,
  val status: String? = null,
  val conclusion: String? = null,
  val headBranch: String? = null,
  val headSha: String? = null,
  val labels: List<String> = emptyList(),
  val runnerId: Long = 0,
  val runnerName: String? = null,
  val steps: List<GiteaActionStepDTO> = emptyList(),
  val htmlUrl: String? = null,
  val createdAt: Date? = null,
  val startedAt: Date? = null,
  val completedAt: Date? = null,
)

data class GiteaActionStepDTO(
  val number: Long,
  val name: String,
  val status: String? = null,
  val conclusion: String? = null,
  val startedAt: Date? = null,
  val completedAt: Date? = null,
)

/** `GET …/actions/runs/{run}/artifacts` */
data class GiteaActionArtifactsDTO(
  val totalCount: Long = 0,
  val artifacts: List<GiteaActionArtifactDTO> = emptyList(),
)

data class GiteaActionArtifactDTO(
  val id: Long,
  val name: String,
  val sizeInBytes: Long = 0,
  val expired: Boolean = false,
  /** ⚠ `ROOT_URL`-based – rebase before downloading. */
  val archiveDownloadUrl: String? = null,
  val createdAt: Date? = null,
  val updatedAt: Date? = null,
  val expiresAt: Date? = null,
)

/** `GET …/actions/secrets` – values are never returned. */
data class GiteaSecretDTO(
  val name: String,
  val description: String? = null,
  val createdAt: Date? = null,
)

/** `GET /users/{user}/orgs/{org}/permissions` (org secrets and variables need [isOwner]). */
data class GiteaOrganizationPermissionsDTO(
  val isOwner: Boolean = false,
  val isAdmin: Boolean = false,
  val canWrite: Boolean = false,
  val canRead: Boolean = false,
)

/** `GET …/actions/variables` */
data class GiteaVariableDTO(
  val name: String,
  val data: String = "",
  val description: String? = null,
  val ownerId: Long = 0,
  val repoId: Long = 0,
)

/** `GET …/actions/runners` (repository, user, organization or instance level) */
data class GiteaActionRunnersDTO(
  val totalCount: Long = 0,
  val runners: List<GiteaActionRunnerDTO> = emptyList(),
)

data class GiteaActionRunnerDTO(
  val id: Long,
  val name: String,
  /** `online`/`idle`, `active`, `offline` */
  val status: String? = null,
  val busy: Boolean = false,
  val disabled: Boolean = false,
  val ephemeral: Boolean = false,
  val labels: List<GiteaActionRunnerLabelDTO> = emptyList(),
)

data class GiteaActionRunnerLabelDTO(
  val id: Long = 0,
  val name: String,
  /** `system` or `custom` */
  val type: String? = null,
)
