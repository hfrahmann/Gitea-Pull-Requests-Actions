package de.hendrikfrahmann.giteaplugin.api.request

import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaResponse
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionArtifactDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionArtifactsDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionJobDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionJobsDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunnerDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunnersDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunsDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionWorkflowDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionWorkflowsDTO
import de.hendrikfrahmann.giteaplugin.api.getJson
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.loadText
import de.hendrikfrahmann.giteaplugin.api.nullOnHttpStatus
import de.hendrikfrahmann.giteaplugin.api.page
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.sendIgnoringBody
import de.hendrikfrahmann.giteaplugin.api.withQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.milliseconds

/*
 * Gitea Actions, verified on 28.1. List endpoints answer `{total_count, …}`
 * objects instead of plain lists. Job ids change with every attempt; re-running a job needs an id of the latest one.
 */

/** Filters of `GET …/actions/runs`; `null` = no filter. */
data class GiteaActionRunsFilter(
  val branch: String? = null,
  val headSha: String? = null,
  val event: String? = null,
  val status: String? = null,
  val actor: String? = null,
)

/** `GET …/actions/workflows` – one entry per workflow file (`id` = file name). */
suspend fun GiteaApi.loadWorkflows(repository: GiteaRepositoryPath): List<GiteaActionWorkflowDTO> =
  getJson<GiteaActionWorkflowsDTO>(server.repoApiUri(repository, "actions", "workflows")).workflows

/** `GET …/actions/runs` – newest first. */
suspend fun GiteaApi.loadRuns(repository: GiteaRepositoryPath, filter: GiteaActionRunsFilter, page: Int, limit: Int): GiteaActionRunsDTO =
  getJson(server.repoApiUri(repository, "actions", "runs").withQuery {
    "branch" eq filter.branch
    "head_sha" eq filter.headSha
    "event" eq filter.event
    "status" eq filter.status
    "actor" eq filter.actor
    page(page, limit)
  })

/** `GET …/actions/workflows/{file}/runs` – runs of one workflow, newest first. */
suspend fun GiteaApi.loadWorkflowRuns(repository: GiteaRepositoryPath, workflowId: String, page: Int, limit: Int): GiteaActionRunsDTO =
  getJson(server.repoApiUri(repository, "actions", "workflows", workflowId, "runs").withQuery { page(page, limit) })

/** `GET …/actions/runs/{run}` */
suspend fun GiteaApi.getRun(repository: GiteaRepositoryPath, runId: Long): GiteaActionRunDTO =
  getJson(server.repoApiUri(repository, "actions", "runs", runId.toString()))

/** `GET …/actions/runs/{run}/jobs` – jobs of the latest attempt, each with its steps. */
suspend fun GiteaApi.loadRunJobs(repository: GiteaRepositoryPath, runId: Long): List<GiteaActionJobDTO> =
  getJson<GiteaActionJobsDTO>(server.repoApiUri(repository, "actions", "runs", runId.toString(), "jobs")).jobs

/** `GET …/actions/runs/{run}/attempts/{attempt}/jobs` – jobs of an earlier attempt (other ids than the latest's). */
suspend fun GiteaApi.loadRunAttemptJobs(repository: GiteaRepositoryPath, runId: Long, attempt: Long): List<GiteaActionJobDTO> =
  getJson<GiteaActionJobsDTO>(server.repoApiUri(repository, "actions", "runs", runId.toString(), "attempts", attempt.toString(), "jobs")).jobs

/** `GET …/actions/jobs/{job}` */
suspend fun GiteaApi.getJob(repository: GiteaRepositoryPath, jobId: Long): GiteaActionJobDTO =
  getJson(server.repoApiUri(repository, "actions", "jobs", jobId.toString()))

/** `GET …/actions/jobs/{job}/logs` – plain text, every line starts with an RFC 3339 timestamp. Grows while the job runs. */
suspend fun GiteaApi.loadJobLog(repository: GiteaRepositoryPath, jobId: Long): String =
  loadText(server.repoApiUri(repository, "actions", "jobs", jobId.toString(), "logs"))

/** Part of a job log: [text] from the requested offset ([isWhole]: the whole log), [totalBytes] = the log's length so far. */
class GiteaJobLogChunk(val text: String, val totalBytes: Long, val isWhole: Boolean)

/**
 * The job log from byte [offset] on, with `Range: bytes=<offset>-` (verified on 28.1, also while the job runs: `206`
 * with the new bytes, `416` when there are none). `null` = nothing new; a server that ignores the range answers `200`
 * with the whole log ([GiteaJobLogChunk.isWhole]).
 */
suspend fun GiteaApi.loadJobLogFrom(repository: GiteaRepositoryPath, jobId: Long, offset: Long): GiteaJobLogChunk? {
  val builder = request(server.repoApiUri(repository, "actions", "jobs", jobId.toString(), "logs")).setHeader("Accept", "text/plain").GET()
  if (offset > 0) {
    builder.header("Range", "bytes=$offset-")
  }
  return nullOnHttpStatus(HTTP_RANGE_NOT_SATISFIABLE) {
    sendAndRead(builder.build()) { stream, info -> logChunk(stream.readBytes(), info, offset) }.body
  }
}

private fun logChunk(bytes: ByteArray, info: HttpResponse.ResponseInfo, offset: Long): GiteaJobLogChunk {
  val text = bytes.toString(Charsets.UTF_8)
  if (info.statusCode() != HTTP_PARTIAL_CONTENT) {
    return GiteaJobLogChunk(text, bytes.size.toLong(), isWhole = true)
  }
  // Content-Range: bytes 3542-3591/3592
  val total = info.headers().firstValue("Content-Range").orElse(null)?.substringAfterLast('/')?.trim()?.toLongOrNull()
  return GiteaJobLogChunk(text, total ?: (offset + bytes.size), isWhole = false)
}

private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_RANGE_NOT_SATISFIABLE = 416

/** `GET …/actions/runs/{run}/artifacts` – only `upload-artifact@v4` artifacts; v3 uploads are not listed. */
suspend fun GiteaApi.loadRunArtifacts(repository: GiteaRepositoryPath, runId: Long): List<GiteaActionArtifactDTO> =
  getJson<GiteaActionArtifactsDTO>(server.repoApiUri(repository, "actions", "runs", runId.toString(), "artifacts")).artifacts

private suspend fun GiteaApi.postRun(repository: GiteaRepositoryPath, runId: Long, vararg path: String) {
  val uri = server.repoApiUri(repository, "actions", "runs", runId.toString(), *path)
  sendIgnoringBody(request(uri).POST(HttpRequest.BodyPublishers.noBody()).build())
}

/** `POST …/runs/{run}/rerun` – a new attempt of all jobs. Needs write access. */
suspend fun GiteaApi.rerunRun(repository: GiteaRepositoryPath, runId: Long) = postRun(repository, runId, "rerun")

/** `POST …/runs/{run}/rerun-failed-jobs` → 201, a new attempt with new job ids. */
suspend fun GiteaApi.rerunFailedJobs(repository: GiteaRepositoryPath, runId: Long) = postRun(repository, runId, "rerun-failed-jobs")

/** `POST …/runs/{run}/jobs/{job}/rerun` – [jobId] must belong to the latest attempt (older ids → 404). */
suspend fun GiteaApi.rerunJob(repository: GiteaRepositoryPath, runId: Long, jobId: Long) = postRun(repository, runId, "jobs", jobId.toString(), "rerun")

/** `POST …/runs/{run}/cancel` → `conclusion: cancelled`. */
suspend fun GiteaApi.cancelRun(repository: GiteaRepositoryPath, runId: Long) = postRun(repository, runId, "cancel")

/** `POST …/runs/{run}/force-cancel` – for runs whose runner is gone. */
suspend fun GiteaApi.forceCancelRun(repository: GiteaRepositoryPath, runId: Long) = postRun(repository, runId, "force-cancel")

/** `POST …/runs/{run}/approve` – lets a run of a fork PR start. */
suspend fun GiteaApi.approveRun(repository: GiteaRepositoryPath, runId: Long) = postRun(repository, runId, "approve")

/** `DELETE …/runs/{run}` */
suspend fun GiteaApi.deleteRun(repository: GiteaRepositoryPath, runId: Long) {
  sendIgnoringBody(request(server.repoApiUri(repository, "actions", "runs", runId.toString())).DELETE().build())
}

private data class GiteaWorkflowDispatchRequest(val ref: String, val inputs: Map<String, String>)

private data class GiteaWorkflowDispatchResult(val workflowRunId: Long = 0)

/**
 * `POST …/actions/workflows/{file}/dispatches?return_run_details=true` `{ref, inputs}` → the new run's id (200, verified
 * on 28.1), or `null` when the server ignores the parameter and answers 204. [ref] is a branch, tag or `refs/…`.
 */
suspend fun GiteaApi.dispatchWorkflow(repository: GiteaRepositoryPath, workflowId: String, ref: String, inputs: Map<String, String>): Long? {
  val uri = server.repoApiUri(repository, "actions", "workflows", workflowId, "dispatches").withQuery { "return_run_details" eq true }
  return sendAndRead(request(uri).jsonBody("POST", GiteaWorkflowDispatchRequest(ref, inputs)).build()) { stream, _ ->
    val body = stream.readBytes()
    if (body.isEmpty()) {
      null
    }
    else {
      GiteaJson.mapper.readValue(body, GiteaWorkflowDispatchResult::class.java).workflowRunId.takeIf { it > 0 }
    }
  }.body
}

/** Waits (up to ~10 s) until run [runId] has reached [attempt] – Gitea creates the attempt of a re-run asynchronously. */
suspend fun GiteaApi.awaitRunAttempt(repository: GiteaRepositoryPath, runId: Long, attempt: Long) {
  repeat(RUN_ATTEMPT_POLLS) {
    if (getRun(repository, runId).runAttempt >= attempt) {
      return
    }
    delay(RUN_ATTEMPT_POLL_INTERVAL)
  }
}

private const val RUN_ATTEMPT_POLLS = 20
private val RUN_ATTEMPT_POLL_INTERVAL = 500.milliseconds

/** `GET …/actions/artifacts/{id}/zip` – redirects to a signed URL on the same host; `archive_download_url` uses `ROOT_URL`. */
fun GiteaApi.artifactZipUri(repository: GiteaRepositoryPath, artifactId: Long): URI =
  server.repoApiUri(repository, "actions", "artifacts", artifactId.toString(), "zip")

/** `GET …/actions/runs/{run}/logs` – logs of all jobs as one zip. */
fun GiteaApi.runLogsZipUri(repository: GiteaRepositoryPath, runId: Long): URI =
  server.repoApiUri(repository, "actions", "runs", runId.toString(), "logs")

/**
 * Downloads [uri] to [target] (via a temporary file next to it, so a cancelled download leaves nothing behind).
 * Redirects are followed by [GiteaApi.sendAndRead], which only sends credentials to the configured server.
 * [progress] gets the bytes read so far and the total (`-1` if unknown). Cancelling interrupts the reading thread.
 */
suspend fun GiteaApi.download(uri: URI, target: Path, progress: (read: Long, total: Long) -> Unit = { _, _ -> }): GiteaResponse<Path> {
  val temp = withContext(Dispatchers.IO) {
    Files.createDirectories(target.parent)
    Files.createTempFile(target.parent, target.fileName.toString(), ".part")
  }
  try {
    return sendAndRead(request(uri).setHeader("Accept", "*/*").GET().build()) { stream, info ->
      val total = info.headers().firstValueAsLong("Content-Length").orElse(-1)
      Files.newOutputStream(temp).use { out -> copyWithProgress(stream, out) { read -> progress(read, total) } }
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
      target
    }
  }
  finally {
    withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(temp) }
  }
}

/** Copies [input] to [output], reporting the bytes copied so far; stops with an exception when the thread is interrupted. */
private fun copyWithProgress(input: InputStream, output: OutputStream, progress: (read: Long) -> Unit) {
  val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
  var read = 0L
  while (true) {
    if (Thread.currentThread().isInterrupted) {
      throw InterruptedIOException("Download cancelled")
    }
    val count = input.read(buffer)
    if (count < 0) {
      return
    }
    output.write(buffer, 0, count)
    read += count
    progress(read)
  }
}

private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

/*
 * Runners (display only). Each level needs its own rights (28.1): repository → repo admin, user → the user
 * themselves, organization → org admin, instance → site admin. Instance-wide runners, the common setup, are therefore
 * invisible to everyone else although they run the jobs.
 */

/** `GET /repos/{o}/{r}/actions/runners` – runners registered for this repository. */
suspend fun GiteaApi.loadRepositoryRunners(repository: GiteaRepositoryPath): List<GiteaActionRunnerDTO> =
  getJson<GiteaActionRunnersDTO>(server.repoApiUri(repository, "actions", "runners")).runners

/** `GET /user/actions/runners` – runners of the current user (serve their own repositories). */
suspend fun GiteaApi.loadUserRunners(): List<GiteaActionRunnerDTO> =
  getJson<GiteaActionRunnersDTO>(server.apiUri("user", "actions", "runners")).runners

/** `GET /orgs/{org}/actions/runners` – org admins only. */
suspend fun GiteaApi.loadOrganizationRunners(organization: String): List<GiteaActionRunnerDTO> =
  getJson<GiteaActionRunnersDTO>(server.apiUri("orgs", organization, "actions", "runners")).runners

/** `GET /admin/actions/runners` – all runners of the instance; site admins only. */
suspend fun GiteaApi.loadInstanceRunners(): List<GiteaActionRunnerDTO> =
  getJson<GiteaActionRunnersDTO>(server.apiUri("admin", "actions", "runners")).runners
