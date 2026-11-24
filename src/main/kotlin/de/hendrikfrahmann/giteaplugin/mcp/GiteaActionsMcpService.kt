package de.hendrikfrahmann.giteaplugin.mcp

import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflowDispatch
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionJobDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaActionRunsFilter
import de.hendrikfrahmann.giteaplugin.api.request.cancelRun
import de.hendrikfrahmann.giteaplugin.api.request.dispatchWorkflow
import de.hendrikfrahmann.giteaplugin.api.request.getJob
import de.hendrikfrahmann.giteaplugin.api.request.getPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.getRepository
import de.hendrikfrahmann.giteaplugin.api.request.getRun
import de.hendrikfrahmann.giteaplugin.api.request.loadJobLog
import de.hendrikfrahmann.giteaplugin.api.request.loadRawFile
import de.hendrikfrahmann.giteaplugin.api.request.loadRunJobs
import de.hendrikfrahmann.giteaplugin.api.request.loadRuns
import de.hendrikfrahmann.giteaplugin.api.request.loadWorkflowRuns
import de.hendrikfrahmann.giteaplugin.api.request.loadWorkflows
import de.hendrikfrahmann.giteaplugin.api.request.rerunFailedJobs
import de.hendrikfrahmann.giteaplugin.api.request.rerunJob
import de.hendrikfrahmann.giteaplugin.api.request.rerunRun
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.checkNumber
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.checkPage
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.checkText
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.login
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.obj
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpJson.write
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The logic of the "Gitea Actions" MCP tools, without any MCP dependency: runs, jobs, logs and workflows; re-run,
 * cancel and dispatch behind the write switch. See [GiteaPullRequestsMcpService] for the conventions.
 */
class GiteaActionsMcpService(private val env: GiteaMcpEnvironment) {

  @Suppress("LongParameterList") // the tool's filters
  suspend fun listRuns(repository: String?, branch: String?, pullRequest: Long?, commit: String?, workflow: String?, status: String?,
                       event: String?, page: Int, limit: Int): String {
    checkPage(page, limit)
    val target = readTarget(repository)
    var headSha = commit?.trim()?.takeIf { it.isNotEmpty() }
    if (pullRequest != null) {
      checkNumber(pullRequest, "pullRequest")
      if (headSha != null) {
        throw GiteaMcpException("Pass either `pullRequest` or `commit`, not both.")
      }
      // runs of a PR are the runs of its head commit (pull_request and push events), like the Actions tool window shows them
      headSha = target.api.getPullRequest(target.repository, pullRequest).head?.sha
                ?: throw GiteaMcpException("Pull request #$pullRequest has no head commit.")
    }
    val filter = GiteaActionRunsFilter(branch = branch?.trim()?.takeIf { it.isNotEmpty() }, headSha = headSha,
                                       event = event?.trim()?.takeIf { it.isNotEmpty() }, status = status?.trim()?.takeIf { it.isNotEmpty() })
    val workflowFile = workflow?.trim()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    val result = when (workflowFile) {
      null -> target.api.loadRuns(target.repository, filter, page, limit)
      // the per-workflow endpoint has no filters: filter the page here
      else -> target.api.loadWorkflowRuns(target.repository, workflowFile, page, limit).let { runs ->
        runs.copy(workflowRuns = runs.workflowRuns.filter { it.matches(filter) })
      }
    }
    return write(obj("repository" to target.repository.toString(), "page" to page, "totalCount" to result.totalCount.takeIf { workflowFile == null },
                     "runs" to result.workflowRuns.map(::run), "mayHaveMore" to (result.workflowRuns.size >= limit)))
  }

  suspend fun getRun(repository: String?, runId: Long): String {
    checkNumber(runId, "runId")
    val target = readTarget(repository)
    val (run, jobs) = coroutineScope {
      val jobs = async { target.api.loadRunJobs(target.repository, runId) }
      target.api.getRun(target.repository, runId) to jobs.await()
    }
    return write(run(run) + obj("jobs" to jobs.map(::job)))
  }

  suspend fun getJobLog(repository: String?, jobId: Long, fromLine: Int?, maxLines: Int, timestamps: Boolean): String {
    checkNumber(jobId, "jobId")
    if (maxLines !in 1..MAX_LOG_LINES) {
      throw GiteaMcpException("`maxLines` must be between 1 and $MAX_LOG_LINES, got $maxLines.")
    }
    if (fromLine != null && fromLine < 1) {
      throw GiteaMcpException("`fromLine` is 1-based, got $fromLine.")
    }
    val target = readTarget(repository)
    val (job, text) = coroutineScope {
      val text = async { target.api.loadJobLog(target.repository, jobId) }
      target.api.getJob(target.repository, jobId) to text.await()
    }
    val lines = logLines(text, timestamps)
    // default: the end of the log, where failures are
    val start = (fromLine ?: (lines.size - maxLines + 1)).coerceAtLeast(1)
    val shown = lines.drop(start - 1).take(maxLines)
    return write(obj("jobId" to jobId, "name" to job.name, "status" to job.status, "conclusion" to job.conclusion, "totalLines" to lines.size,
                     "fromLine" to start.takeIf { shown.isNotEmpty() }, "toLine" to (start + shown.size - 1).takeIf { shown.isNotEmpty() },
                     "log" to shown.joinToString("\n")))
  }

  suspend fun listWorkflows(repository: String?, ref: String?): String {
    val target = readTarget(repository)
    val gitRef = ref?.trim()?.takeIf { it.isNotEmpty() } ?: defaultBranch(target)
    val workflows = target.api.loadWorkflows(target.repository)
    val items = coroutineScope {
      workflows.map { workflow ->
        async {
          val dispatch = workflow.path?.let { path ->
            target.api.loadRawFile(target.repository, path, gitRef)?.let(GiteaWorkflowDispatch::parse)
          }
          obj("id" to workflow.id, "name" to workflow.name, "path" to workflow.path, "state" to workflow.state,
              "dispatchable" to (dispatch != null), "inputs" to dispatch?.inputs?.map(::input))
        }
      }.awaitAll()
    }
    return write(obj("ref" to gitRef, "workflows" to items))
  }

  suspend fun rerun(repository: String?, runId: Long, jobId: Long?, failedOnly: Boolean): String {
    checkNumber(runId, "runId")
    if (jobId != null && failedOnly) {
      throw GiteaMcpException("Pass either `jobId` or `failedOnly`, not both.")
    }
    val target = writeTarget(repository)
    val what = when {
      jobId != null -> {
        checkNumber(jobId, "jobId")
        target.api.rerunJob(target.repository, runId, jobId)
        "job"
      }
      failedOnly -> {
        target.api.rerunFailedJobs(target.repository, runId)
        "failed jobs"
      }
      else -> {
        target.api.rerunRun(target.repository, runId)
        "run"
      }
    }
    return write(obj("runId" to runId, "jobId" to jobId, "rerun" to what))
  }

  suspend fun cancel(repository: String?, runId: Long): String {
    checkNumber(runId, "runId")
    val target = writeTarget(repository)
    target.api.cancelRun(target.repository, runId)
    return write(obj("runId" to runId, "cancelled" to true))
  }

  suspend fun dispatch(repository: String?, workflow: String, ref: String?, inputs: Map<String, String>): String {
    checkText(workflow, "workflow")
    val target = writeTarget(repository)
    val gitRef = ref?.trim()?.takeIf { it.isNotEmpty() } ?: defaultBranch(target)
    val runId = target.api.dispatchWorkflow(target.repository, workflow.trim().substringAfterLast('/'), gitRef, inputs)
    return write(obj("workflow" to workflow.trim().substringAfterLast('/'), "ref" to gitRef, "runId" to runId))
  }

  private suspend fun readTarget(repository: String?): GiteaMcpTarget {
    GiteaMcpGates.checkRead(env)
    return env.target(repository)
  }

  private suspend fun writeTarget(repository: String?): GiteaMcpTarget {
    GiteaMcpGates.checkRead(env)
    return env.target(repository).also { GiteaMcpGates.checkWrite(env, it) }
  }

  private suspend fun defaultBranch(target: GiteaMcpTarget): String =
    target.api.getRepository(target.repository).defaultBranch
    ?: throw GiteaMcpException("${target.repository} has no default branch, pass `ref`.")

  companion object {
    internal const val MAX_LOG_LINES: Int = 2000

    private val TIMESTAMP = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})\s?""")
    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

    /** Log lines without colour codes and, unless [timestamps], without the runner's timestamp prefix. */
    internal fun logLines(text: String, timestamps: Boolean): List<String> {
      val lines = text.removeSuffix("\n").lines().map { line ->
        val plain = line.replace(ANSI, "").removeSuffix("\r")
        when {
          timestamps -> plain
          else -> plain.replaceFirst(TIMESTAMP, "")
        }
      }
      return when {
        lines.singleOrNull()?.isEmpty() == true -> emptyList()
        else -> lines
      }
    }

    private fun GiteaActionRunDTO.matches(filter: GiteaActionRunsFilter): Boolean =
      (filter.branch == null || headBranch == filter.branch) && (filter.headSha == null || headSha == filter.headSha) &&
      (filter.event == null || event == filter.event) && (filter.status == null || status == filter.status || conclusion == filter.status)

    internal fun run(run: GiteaActionRunDTO): Map<String, Any?> =
      obj("id" to run.id, "runNumber" to run.runNumber, "attempt" to run.runAttempt, "title" to run.displayTitle,
          "workflow" to run.path?.substringBefore('@'), "event" to run.event, "status" to run.status, "conclusion" to run.conclusion,
          "branch" to run.headBranch, "sha" to run.headSha, "actor" to login(run.triggerActor ?: run.actor),
          "pullRequests" to run.pullRequests.map { it.number }.ifEmpty { null }, "url" to run.htmlUrl, "createdAt" to run.createdAt,
          "startedAt" to run.startedAt, "completedAt" to run.completedAt)

    internal fun job(job: GiteaActionJobDTO): Map<String, Any?> =
      obj("id" to job.id, "name" to job.name, "status" to job.status, "conclusion" to job.conclusion, "runner" to job.runnerName,
          "labels" to job.labels.ifEmpty { null }, "startedAt" to job.startedAt, "completedAt" to job.completedAt,
          "steps" to job.steps.map { obj("number" to it.number, "name" to it.name, "status" to it.status, "conclusion" to it.conclusion) })

    private fun input(input: GiteaWorkflowDispatch.Input): Map<String, Any?> =
      obj("name" to input.name, "description" to input.description, "required" to input.required, "type" to input.type.name.lowercase(),
          "default" to input.default, "options" to input.options.ifEmpty { null })
  }
}
