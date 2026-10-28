package de.hendrikfrahmann.giteaplugin.actions.ui.tree

import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaArtifact
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaRun
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaStep
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflow
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls

/**
 * What a row of the Actions tree shows. [key] identifies the row across refreshes (expansion and selection stay);
 * the rest is replaced by newer data.
 */
sealed interface GiteaActionsNode {
  @get:NonNls val key: String

  /** Has (or can load) children. */
  val isExpandable: Boolean get() = false

  /** The PR of the current branch, or of a "PR #N" tab. */
  data class PullRequest(val number: Long, val title: String) : GiteaActionsNode {
    override val key: String get() = "pr:$number"
  }

  /** A workflow file; its runs load on expand. */
  data class Workflow(val workflow: GiteaWorkflow) : GiteaActionsNode {
    override val key: String get() = "workflow:${workflow.id}"
    override val isExpandable: Boolean get() = true
  }

  /**
   * Jobs of the latest attempt, artifacts and older attempts load on expand. [workflowName]: the workflow's `name`
   * (the run only knows the file); [showBranch]: the Workflows tab shows where a run ran.
   */
  /** [isCheckedOut]: "Current Branch" run of the checked-out commit, unlike runs of commits not pulled yet. */
  data class Run(val run: GiteaRun, val workflowName: String?, val showBranch: Boolean, val isCheckedOut: Boolean = false) : GiteaActionsNode {
    override val key: String get() = "run:${run.id}"
    override val isExpandable: Boolean get() = true

    /** Children are reloaded when this changes. */
    val version: List<Any?> get() = listOf(run.attempt, run.status, run.updatedAt)
  }

  /** [latestAttempt]: job ids of older attempts can't be re-run (404). */
  data class Job(val run: GiteaRun, val job: GiteaJob, val latestAttempt: Boolean) : GiteaActionsNode {
    override val key: String get() = "job:${job.id}"
    override val isExpandable: Boolean get() = job.steps.isNotEmpty()
  }

  data class Step(val run: GiteaRun, val job: GiteaJob, val step: GiteaStep) : GiteaActionsNode {
    override val key: String get() = "step:${job.id}:${step.number}"
  }

  data class Artifacts(val run: GiteaRun, val artifacts: List<GiteaArtifact>) : GiteaActionsNode {
    override val key: String get() = "artifacts:${run.id}"
    override val isExpandable: Boolean get() = true
  }

  data class Artifact(val run: GiteaRun, val artifact: GiteaArtifact) : GiteaActionsNode {
    override val key: String get() = "artifact:${artifact.id}"
  }

  /** "Previous attempts" of a re-run run (13.3a). */
  data class Attempts(val run: GiteaRun) : GiteaActionsNode {
    override val key: String get() = "attempts:${run.id}"
    override val isExpandable: Boolean get() = true
  }

  data class Attempt(val run: GiteaRun, val attempt: Long) : GiteaActionsNode {
    override val key: String get() = "attempt:${run.id}:$attempt"
    override val isExpandable: Boolean get() = true
  }

  /** Loads the next page of runs into the parent. */
  data class LoadMore(val parentKey: String) : GiteaActionsNode {
    override val key: String get() = "more:$parentKey"
  }

  data class Message(val text: @Nls String, val isError: Boolean, val parentKey: String) : GiteaActionsNode {
    override val key: String get() = "message:$parentKey"
  }

  data object Loading : GiteaActionsNode {
    override val key: String get() = "loading"
  }
}

/** Status shown by a node's icon, if it has one. */
val GiteaActionsNode.status: GiteaActionStatus?
  get() = when (this) {
    is GiteaActionsNode.Run -> run.status
    is GiteaActionsNode.Job -> job.status
    is GiteaActionsNode.Step -> step.status
    else -> null
  }

/** The run a node belongs to. */
val GiteaActionsNode.run: GiteaRun?
  get() = when (this) {
    is GiteaActionsNode.Run -> run
    is GiteaActionsNode.Job -> run
    is GiteaActionsNode.Step -> run
    is GiteaActionsNode.Artifacts -> run
    is GiteaActionsNode.Artifact -> run
    is GiteaActionsNode.Attempts -> run
    is GiteaActionsNode.Attempt -> run
    else -> null
  }
