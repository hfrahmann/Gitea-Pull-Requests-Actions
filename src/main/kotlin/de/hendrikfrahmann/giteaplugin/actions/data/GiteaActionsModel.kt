package de.hendrikfrahmann.giteaplugin.actions.data

import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionArtifactDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionJobDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionStepDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionWorkflowDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import java.util.Date

/**
 * One state for runs, jobs and steps, from the API's `status` + `conclusion` pair. Gitea reports `queued`, `waiting`,
 * `in_progress`, `completed` (… plus a few older spellings) and, once completed, a conclusion.
 */
enum class GiteaActionStatus {
  QUEUED, WAITING, RUNNING, SUCCESS, FAILURE, CANCELLED, SKIPPED, UNKNOWN;

  val isFinished: Boolean get() = this == SUCCESS || this == FAILURE || this == CANCELLED || this == SKIPPED
  val isActive: Boolean get() = this == QUEUED || this == WAITING || this == RUNNING

  companion object {
    fun of(status: String?, conclusion: String?): GiteaActionStatus {
      when (conclusion?.lowercase()) {
        "success" -> return SUCCESS
        "failure", "failed", "timed_out", "startup_failure", "action_required" -> return FAILURE
        "cancelled", "canceled" -> return CANCELLED
        "skipped", "neutral" -> return SKIPPED
      }
      return when (status?.lowercase()) {
        "queued", "pending", "requested" -> QUEUED
        "waiting", "blocked" -> WAITING
        "in_progress", "running" -> RUNNING
        "success" -> SUCCESS
        "failure", "failed" -> FAILURE
        "cancelled", "canceled" -> CANCELLED
        "skipped" -> SKIPPED
        else -> UNKNOWN
      }
    }

    /** Sort key that puts failures first, then running, then the rest. */
    fun attentionOrder(status: GiteaActionStatus): Int = when (status) {
      FAILURE -> 0
      RUNNING -> 1
      WAITING, QUEUED -> 2
      CANCELLED -> 3
      else -> 4
    }
  }
}

data class GiteaWorkflow(
  /** The file name, e.g. `ci.yml` – the workflow id in the API. */
  val id: String,
  val name: String,
  /** `.gitea/workflows/ci.yml` */
  val path: String?,
  val isDisabled: Boolean,
) {
  companion object {
    fun from(dto: GiteaActionWorkflowDTO): GiteaWorkflow =
      GiteaWorkflow(dto.id, dto.name?.takeIf { it.isNotBlank() } ?: dto.id, dto.path, dto.state?.startsWith("disabled") == true)
  }
}

data class GiteaRun(
  val id: Long,
  val number: Long,
  val attempt: Long,
  val title: String,
  /** Workflow file, from `path` (`ci.yml@refs/heads/main`). */
  val workflowFile: String?,
  /** Ref the run ran on, from `path`: `refs/heads/main`, `refs/pull/6/head`, … */
  val ref: String?,
  val event: String?,
  val status: GiteaActionStatus,
  /** `head_branch`, or the branch from [ref] for push runs. `null` for PR runs. */
  val branch: String?,
  val headSha: String?,
  val actor: GiteaUserDTO?,
  /** PR of a `pull_request` run: from `pull_requests`, else from a `refs/pull/N/head` ref. */
  val pullRequestNumber: Long?,
  val createdAt: Date?,
  val startedAt: Date?,
  val completedAt: Date?,
  val updatedAt: Date?,
) {
  /** Running time; up to now while the run is active. */
  fun durationMillis(now: Long = System.currentTimeMillis()): Long? {
    val start = startedAt ?: return null
    val end = completedAt ?: when {
      status.isActive -> Date(now)
      else -> return null
    }
    return (end.time - start.time).takeIf { it >= 0 }
  }

  val isPullRequestRun: Boolean get() = pullRequestNumber != null

  companion object {
    private val PULL_REF = Regex("refs/pull/(\\d+)/(head|merge)")

    fun from(dto: GiteaActionRunDTO): GiteaRun {
      val workflowFile = dto.path?.substringBefore('@')?.takeIf { it.isNotBlank() }
      val ref = dto.path?.substringAfter('@', "")?.takeIf { it.isNotBlank() }
      val branch = dto.headBranch?.takeIf { it.isNotBlank() } ?: ref?.removePrefix("refs/heads/")?.takeIf { it != ref }
      val prNumber = dto.pullRequests.firstOrNull()?.number ?: ref?.let { PULL_REF.matchEntire(it) }?.groupValues?.get(1)?.toLong()
      return GiteaRun(
        id = dto.id,
        number = dto.runNumber,
        attempt = dto.runAttempt,
        title = dto.displayTitle?.takeIf { it.isNotBlank() } ?: "#${dto.runNumber}",
        workflowFile = workflowFile,
        ref = ref,
        event = dto.event,
        status = GiteaActionStatus.of(dto.status, dto.conclusion),
        branch = branch,
        headSha = dto.headSha,
        actor = dto.triggerActor ?: dto.actor,
        pullRequestNumber = prNumber,
        createdAt = dto.createdAt.validDate(),
        startedAt = dto.startedAt.validDate(),
        completedAt = dto.completedAt.validDate(),
        updatedAt = dto.updatedAt.validDate(),
      )
    }
  }
}

data class GiteaJob(
  val id: Long,
  val runId: Long,
  val attempt: Long,
  val name: String,
  val status: GiteaActionStatus,
  val runnerName: String?,
  val steps: List<GiteaStep>,
  val startedAt: Date?,
  val completedAt: Date?,
) {
  /** It started at some point; skipped jobs and jobs cancelled while waiting never did (and have no log). */
  val hasRun: Boolean get() = startedAt != null && status != GiteaActionStatus.SKIPPED

  fun durationMillis(now: Long = System.currentTimeMillis()): Long? {
    val start = startedAt ?: return null
    val end = completedAt ?: when {
      status.isActive -> Date(now)
      else -> return null
    }
    return (end.time - start.time).takeIf { it >= 0 }
  }

  companion object {
    fun from(dto: GiteaActionJobDTO): GiteaJob = GiteaJob(
      id = dto.id,
      runId = dto.runId,
      attempt = dto.runAttempt,
      name = dto.name,
      status = GiteaActionStatus.of(dto.status, dto.conclusion),
      runnerName = dto.runnerName?.takeIf { it.isNotBlank() },
      steps = dto.steps.map(GiteaStep::from),
      startedAt = dto.startedAt.validDate(),
      completedAt = dto.completedAt.validDate(),
    )

    /** Failures first, otherwise the server's order. */
    fun sortForAttention(jobs: List<GiteaJob>): List<GiteaJob> = jobs.sortedBy { GiteaActionStatus.attentionOrder(it.status) }
  }
}

data class GiteaStep(
  /** 0-based on 28.1. */
  val number: Long,
  val name: String,
  val status: GiteaActionStatus,
  val startedAt: Date?,
  val completedAt: Date?,
) {
  companion object {
    fun from(dto: GiteaActionStepDTO): GiteaStep =
      GiteaStep(dto.number, dto.name, GiteaActionStatus.of(dto.status, dto.conclusion), dto.startedAt.validDate(), dto.completedAt.validDate())
  }
}

data class GiteaArtifact(
  val id: Long,
  val name: String,
  val sizeInBytes: Long,
  val expired: Boolean,
  val expiresAt: Date?,
  val createdAt: Date? = null,
) {
  companion object {
    fun from(dto: GiteaActionArtifactDTO): GiteaArtifact =
      GiteaArtifact(dto.id, dto.name, dto.sizeInBytes, dto.expired, dto.expiresAt.validDate(), dto.createdAt.validDate())
  }
}

/** Gitea sends `0001-01-01…` or `1970-01-01…` for times that never happened (e.g. a run cancelled before it started). */
private fun Date?.validDate(): Date? = this?.takeIf { it.time > 86_400_000L }
