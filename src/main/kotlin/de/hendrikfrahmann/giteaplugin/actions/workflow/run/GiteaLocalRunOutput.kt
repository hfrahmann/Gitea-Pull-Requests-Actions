package de.hendrikfrahmann.giteaplugin.actions.workflow.run

import com.fasterxml.jackson.databind.JsonNode
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJobLog
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogEntry
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogSectionRef
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaStep
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date

/**
 * The output of `gitea-runner exec --json`, sorted into jobs, steps and the sections of the CI log view. Every JSON line
 * names its job (`jobID` + `matrix`), and within the job its `stage` (`Pre`, `Main`, `Post`, `Complete`) and `stepNumber`:
 *
 * ```
 * {"job":"CI/Build-1","jobID":"build","matrix":{"v":"a"},"level":"info","msg":"::group::Starting job container","raw_output":true,…}
 * {…,"stage":"Main","step":"Say hi","stepNumber":1,"raw_output":true,"msg":"hello a\n"}
 * {…,"stage":"Main","step":"Say hi","stepNumber":1,"stepResult":"success","msg":"Success - Main Say hi"}
 * {…,"stage":"Complete","jobResult":"success","msg":"Job succeeded"}
 * ```
 *
 * Like Gitea's log: everything before the first step is "Set up job"; in a step only its output (`raw_output`) and
 * errors; `Post` and `Complete` lines are "Complete job". The runner's own lines (plain text, or JSON without a job)
 * form a log of their own. Lines can come from any thread; reads return copies.
 */
class GiteaLocalRunOutput {
  private val runner = mutableListOf<GiteaLogEntry>()
  private val jobs = linkedMapOf<String, LocalJob>()
  private var exitStatus: GiteaActionStatus? = null

  /** Grows with every change, to skip renders when nothing happened. */
  @Volatile
  var modificationCount: Long = 0L
    private set

  @Synchronized
  fun addLine(line: String) {
    val text = line.trimEnd('\r', '\n')
    val json = text.takeIf { it.startsWith("{") }?.let(::readJson)
    when {
      json == null -> addRunnerText(text)
      json.hasNonNull("jobID") -> addJobLine(json)
      else -> runner += entries(json.text("time")?.let(::parseInstant), message(json), GiteaLogSectionRef.SET_UP)
    }
    modificationCount++
  }

  /** The process has ended: jobs it didn't finish end with it, as cancelled if [stopped]. */
  @Synchronized
  fun processTerminated(exitCode: Int, stopped: Boolean) {
    val status = when {
      stopped -> GiteaActionStatus.CANCELLED
      exitCode == 0 -> GiteaActionStatus.SUCCESS
      else -> GiteaActionStatus.FAILURE
    }
    exitStatus = status
    val now = Instant.now()
    jobs.values.filter { !it.status.isFinished }.forEach { it.finish(status, now) }
    modificationCount++
  }

  /** The runner's own output, as a job (running until the process ends). */
  @Synchronized
  fun runnerJob(name: String): GiteaJob =
    GiteaJob(RUNNER_ID, 0, 1, name, exitStatus ?: GiteaActionStatus.RUNNING, null, emptyList(), null, null)

  @Synchronized
  fun runnerLog(): GiteaJobLog = GiteaJobLog.plain(runner.toList())

  /** The jobs in the order they started. */
  @Synchronized
  fun jobs(): List<GiteaJob> = jobs.values.map { it.toJob() }

  /** The log of job [id] (from [jobs]); empty if there is none. */
  @Synchronized
  fun jobLog(id: Long): GiteaJobLog {
    val job = jobs.values.find { it.id == id } ?: return GiteaJobLog.parse("")
    return GiteaJobLog.build(job.entries.toList(), job.toJob().steps)
  }

  private fun addRunnerText(text: String) {
    val match = LOGRUS_TEXT.matchEntire(text)
    if (match == null) {
      runner += GiteaLogEntry(null, text, GiteaLogSectionRef.SET_UP)
      return
    }
    val (time, level, msg) = match.destructured
    val rest = match.groupValues[REST_GROUP].trim()
    val message = unquote(msg) + rest.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
    runner += entries(parseInstant(time), command(level, message), GiteaLogSectionRef.SET_UP)
  }

  private fun addJobLine(json: JsonNode) {
    val key = json.text("jobID") + "\u0000" + json.get("matrix")?.toString().orEmpty()
    val job = jobs.getOrPut(key) { LocalJob(jobs.size + 1L, jobName(json)) }
    val line = JobLine(json, json.text("time")?.let(::parseInstant) ?: Instant.now())
    job.start(line.time)
    if (line.mainStep != null) {
      job.step(line.mainStep, json.text("step") ?: "#${line.mainStep}")
    }
    val stepResult = json.text("stepResult")
    if (stepResult != null) {
      line.mainStep?.let { job.stepResult(it, GiteaActionStatus.of(null, stepResult), line.time) }
      return
    }
    sectionOf(job, line)?.let { section -> job.entries += entries(line.time, message(json), section) }
    json.text("jobResult")?.let { job.finish(GiteaActionStatus.of(null, it), line.time) }
  }

  /** What the CI view needs of a job's JSON line. */
  private class JobLine(json: JsonNode, val time: Instant) {
    val stage: String? = json.text("stage")
    val level: String = json.text("level").orEmpty()
    val isJobResult: Boolean = json.hasNonNull("jobResult")
    /** The step of a `Main` line; `Pre` and `Post` lines belong to the set-up and the clean-up. */
    val mainStep: Long? = json.get("stepNumber")?.takeIf { it.isIntegralNumber && stage == STAGE_MAIN }?.asLong()
    /** A step shows its output and errors; the runner's other messages there ("docker exec cmd=…") are noise. */
    val shown: Boolean = json.get("raw_output")?.asBoolean() == true || level in ERROR_LEVELS
  }

  /** The section [line] belongs to, or `null` if Gitea wouldn't show it. */
  private fun sectionOf(job: LocalJob, line: JobLine): GiteaLogSectionRef? {
    val step = line.mainStep
    return when {
      line.isJobResult -> GiteaLogSectionRef.COMPLETE
      line.level in DEBUG_LEVELS -> null
      step != null && line.shown -> job.stepStarted(step, line.time)
      step != null -> null
      line.stage == STAGE_COMPLETE || (line.stage == STAGE_POST && line.shown) -> GiteaLogSectionRef.COMPLETE
      !job.stepsStarted -> GiteaLogSectionRef.SET_UP
      line.shown || line.level == LEVEL_WARNING -> job.currentSection
      else -> null
    }
  }

  /** One job (one matrix combination) of the run. */
  private class LocalJob(val id: Long, val name: String) {
    val entries = mutableListOf<GiteaLogEntry>()
    var status = GiteaActionStatus.QUEUED
    private var startedAt: Instant? = null
    private var completedAt: Instant? = null
    private val steps = sortedMapOf<Long, LocalStep>()

    val stepsStarted: Boolean get() = steps.values.any { it.startedAt != null }
    val currentSection: GiteaLogSectionRef get() = entries.lastOrNull()?.section ?: GiteaLogSectionRef.SET_UP

    fun start(time: Instant) {
      if (startedAt == null) {
        startedAt = time
        status = GiteaActionStatus.RUNNING
      }
    }

    fun step(number: Long, name: String) {
      steps.getOrPut(number) { LocalStep(number, name) }
    }

    /** The step's section; its first shown line starts it. */
    fun stepStarted(number: Long, time: Instant): GiteaLogSectionRef {
      val step = steps[number]
      if (step != null && step.startedAt == null) {
        step.startedAt = time
        step.status = GiteaActionStatus.RUNNING
      }
      return GiteaLogSectionRef.step(number)
    }

    fun stepResult(number: Long, result: GiteaActionStatus, time: Instant) {
      val step = steps[number] ?: return
      step.startedAt = step.startedAt ?: time
      step.status = result
      step.completedAt = time
    }

    /** Steps that never started were skipped (by `if:`, or after a failure); running ones end with the job. */
    fun finish(result: GiteaActionStatus, time: Instant) {
      status = result
      completedAt = time
      for (step in steps.values) {
        when (step.status) {
          GiteaActionStatus.QUEUED -> step.status = GiteaActionStatus.SKIPPED
          GiteaActionStatus.RUNNING -> {
            step.status = result
            step.completedAt = time
          }
          else -> {}
        }
      }
    }

    fun toJob(): GiteaJob = GiteaJob(id, 0, 1, name, status, null, steps.values.map { it.toStep() }, startedAt?.toDate(), completedAt?.toDate())
  }

  private class LocalStep(val number: Long, val name: String) {
    var status = GiteaActionStatus.QUEUED
    var startedAt: Instant? = null
    var completedAt: Instant? = null

    fun toStep() = GiteaStep(number, name, status, startedAt?.toDate(), completedAt?.toDate())
  }

  companion object {
    const val RUNNER_ID: Long = 0L

    private const val STAGE_MAIN = "Main"
    private const val STAGE_POST = "Post"
    private const val STAGE_COMPLETE = "Complete"
    private const val LEVEL_WARNING = "warning"
    private val ERROR_LEVELS = setOf("error", "fatal", "panic")
    private val DEBUG_LEVELS = setOf("debug", "trace")

    /** logrus' text format, used before the JSON logger is set up: `time="…" level=info msg="…" error="…"`. */
    private val LOGRUS_TEXT = Regex("""^time="([^"]*)" level=(\w+) msg="((?:[^"\\]|\\.)*)"(.*)$""")
    private const val REST_GROUP = 4
    private val MATRIX_SUFFIX = Regex("""-\d+$""")

    private fun readJson(text: String): JsonNode? =
      try {
        GiteaJson.mapper.readTree(text)?.takeIf { it.isObject && it.has("msg") }
      }
      catch (_: Exception) {
        null
      }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { !it.isNull }?.asText()

    /**
     * `CI/Build & test-1` with matrix `{"v":"a"}` → `Build & test (a)`: without the workflow's name, and with the matrix
     * values instead of the runner's numbering (as Gitea names matrix jobs).
     */
    private fun jobName(json: JsonNode): String {
      val job = json.text("job")?.trim().orEmpty()
      val name = job.substringAfter('/').takeIf { it.isNotEmpty() } ?: json.text("jobID").orEmpty()
      val matrix = json.get("matrix")?.takeIf { it.isObject && !it.isEmpty } ?: return name
      val values = matrix.properties().joinToString(", ") { (_, value) ->
        when {
          value.isValueNode -> value.asText()
          else -> value.toString()
        }
      }
      return "${name.replace(MATRIX_SUFFIX, "")} ($values)"
    }

    /** The line as Gitea would show it: output as it is, the runner's warnings and errors as workflow commands. */
    private fun message(json: JsonNode): String {
      val msg = json.text("msg").orEmpty()
      return when {
        json.get("raw_output")?.asBoolean() == true -> msg
        else -> command(json.text("level").orEmpty(), msg)
      }
    }

    private fun command(level: String, msg: String): String {
      if (msg.startsWith("::") || msg.startsWith("##[")) {
        return msg
      }
      return when (level) {
        in ERROR_LEVELS -> "::error::$msg"
        LEVEL_WARNING -> "::warning::$msg"
        in DEBUG_LEVELS -> "::debug::$msg"
        else -> msg
      }
    }

    /** One entry per line of [message] (output comes with its line break, sometimes several lines at once). */
    private fun entries(time: Instant?, message: String, section: GiteaLogSectionRef): List<GiteaLogEntry> {
      val text = message.removeSuffix("\n").removeSuffix("\r")
      val prefix = listOf("::error::", "::warning::", "::debug::").firstOrNull { text.startsWith(it) }
      return text.split('\n').mapIndexed { i, line ->
        val content = line.removeSuffix("\r")
        val continued = i > 0 && prefix != null
        GiteaLogEntry(time, prefix?.takeIf { continued }.orEmpty() + content, section)
      }
    }

    private fun unquote(value: String): String = buildString {
      var i = 0
      while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
          i++
          append(when (val next = value[i]) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            else -> next
          })
        }
        else {
          append(c)
        }
        i++
      }
    }

    private fun parseInstant(value: String): Instant? =
      try {
        Instant.parse(value)
      }
      catch (_: DateTimeParseException) {
        null
      }

    private fun Instant.toDate(): Date = Date.from(this)
  }
}
