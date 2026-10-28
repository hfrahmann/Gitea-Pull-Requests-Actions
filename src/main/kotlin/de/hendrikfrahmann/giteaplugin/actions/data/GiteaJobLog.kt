package de.hendrikfrahmann.giteaplugin.actions.data

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * A job log split into sections and groups. The API returns one text for the whole job; every line starts
 * with an RFC 3339 timestamp, and the runner marks blocks with `::group::title` / `::endgroup::`:
 *
 * ```
 * 2026-10-22T17:20:15.4284708Z ::group::Runner Information      ← "Set up job"
 * …
 * 2026-10-22T17:20:17.7894388Z ::group::Run echo "hello"        ← step 0 starts at its "Run …" group
 * 2026-10-22T17:20:17.8164805Z hello                            ← step output follows the group
 * 2026-10-22T17:20:18.4120114Z ::error::intentional failure
 * 2026-10-22T17:20:18.4122892Z Cleaning up container for job    ← "Complete job"
 * ```
 *
 * Sections: "Set up job", one per started step (the n-th `Run …` group is the n-th step that ran), "Complete job".
 */
class GiteaJobLog(
  val lines: List<GiteaLogLine>,
  val sections: List<GiteaLogSection>,
  /** `::group::` blocks: the header line and the last line inside (both indices into [lines]). */
  val groups: List<IntRange>,
) {
  companion object {
    private val TIMESTAMP = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2}))\s?(.*)$""")
    private val WORKFLOW_COMMAND = Regex("""^::(error|warning|notice|debug)(?:\s[^:]*)?::(.*)$""")
    private val AZURE_COMMAND = Regex("""^##\[(error|warning|notice|debug|section|command)](.*)$""")
    private val COMPLETE_JOB = Regex("""^(Cleaning up (container|network)|Job (succeeded|failed|cancelled)|::group::Post )""")
    private const val STEP_GROUP_PREFIX = "Run "

    fun parse(text: String, steps: List<GiteaStep> = emptyList()): GiteaJobLog {
      val parser = Parser()
      text.lineSequence().forEach(parser::addLine)
      return parser.finish(steps)
    }

    /** Collects lines, `::group::` ranges and where the steps and the "Complete job" part start. */
    private class Parser {
      private val lines = mutableListOf<GiteaLogLine>()
      private val groups = mutableListOf<IntRange>()
      private val stepStarts = mutableListOf<Int>()
      private var completeJobStart = -1
      private var openGroup = -1

      fun addLine(raw: String) {
        val match = TIMESTAMP.matchEntire(raw.trimEnd('\r'))
        val timestamp = match?.groupValues?.get(1)?.let(::parseInstant)
        val content = match?.groupValues?.get(2) ?: raw.trimEnd('\r')
        if (startsCompleteJob(content)) {
          completeJobStart = lines.size
        }
        when {
          content.startsWith("::group::") -> startGroup(timestamp, content.removePrefix("::group::"))
          content.startsWith("::endgroup::") -> endGroup()
          else -> lines += line(timestamp, content)
        }
      }

      fun finish(steps: List<GiteaStep>): GiteaJobLog {
        if (lines.lastOrNull()?.let { it.text.isEmpty() && it.timestamp == null } == true) {
          lines.removeAt(lines.lastIndex)
        }
        if (openGroup >= 0 && openGroup < lines.size) {
          groups += openGroup..lines.lastIndex
        }
        return GiteaJobLog(lines, sections(lines, stepStarts, completeJobStart, steps), groups.filter { it.last >= it.first })
      }

      /** The runner's clean-up after the last step, outside of any group. */
      private fun startsCompleteJob(content: String): Boolean {
        val afterSteps = stepStarts.isNotEmpty() && completeJobStart < 0
        return afterSteps && openGroup < 0 && COMPLETE_JOB.containsMatchIn(content)
      }

      /** A `Run …` group (before "Complete job") starts a step; a new group also ends an open one. */
      private fun startGroup(timestamp: Instant?, title: String) {
        closeOpenGroup()
        if (title.startsWith(STEP_GROUP_PREFIX) && completeJobStart < 0) {
          stepStarts += lines.size
        }
        openGroup = lines.size
        lines += GiteaLogLine(timestamp, title, GiteaLogLine.Kind.GROUP)
      }

      private fun endGroup() {
        closeOpenGroup()
        openGroup = -1
      }

      private fun closeOpenGroup() {
        if (openGroup >= 0) {
          groups += openGroup..(lines.size - 1)
        }
      }
    }

    private fun line(timestamp: Instant?, content: String): GiteaLogLine {
      WORKFLOW_COMMAND.matchEntire(content)?.let { m -> return GiteaLogLine(timestamp, m.groupValues[2], kindOf(m.groupValues[1])) }
      AZURE_COMMAND.matchEntire(content)?.let { m -> return GiteaLogLine(timestamp, m.groupValues[2], kindOf(m.groupValues[1])) }
      if (content.startsWith("[command]")) {
        return GiteaLogLine(timestamp, content.removePrefix("[command]"), GiteaLogLine.Kind.COMMAND)
      }
      return GiteaLogLine(timestamp, content, GiteaLogLine.Kind.NORMAL)
    }

    private fun kindOf(command: String): GiteaLogLine.Kind = when (command) {
      "error" -> GiteaLogLine.Kind.ERROR
      "warning" -> GiteaLogLine.Kind.WARNING
      "notice" -> GiteaLogLine.Kind.NOTICE
      "debug" -> GiteaLogLine.Kind.DEBUG
      else -> GiteaLogLine.Kind.COMMAND
    }

    private class Boundary(val start: Int, val type: GiteaLogSection.Type, val step: GiteaStep?)

    /** Steps that produced output, in order: the ones that started (skipped steps leave no `Run …` group). */
    private fun sections(lines: List<GiteaLogLine>, stepStarts: List<Int>, completeJobStart: Int, steps: List<GiteaStep>): List<GiteaLogSection> {
      if (lines.isEmpty()) {
        return emptyList()
      }
      val ranSteps = steps.filter { it.status != GiteaActionStatus.SKIPPED && it.status != GiteaActionStatus.QUEUED }
      val boundaries = buildList {
        add(Boundary(0, GiteaLogSection.Type.SET_UP, null))
        stepStarts.forEachIndexed { i, start -> add(Boundary(start, GiteaLogSection.Type.STEP, ranSteps.getOrNull(i))) }
        if (completeJobStart >= 0) {
          add(Boundary(completeJobStart, GiteaLogSection.Type.COMPLETE, null))
        }
      }
      return boundaries.mapIndexedNotNull { i, boundary ->
        val end = (boundaries.getOrNull(i + 1)?.start ?: lines.size) - 1
        if (end < boundary.start) {
          return@mapIndexedNotNull null
        }
        val title = when {
          boundary.type == GiteaLogSection.Type.STEP -> boundary.step?.name ?: lines[boundary.start].text
          else -> null
        }
        GiteaLogSection(title, boundary.step, boundary.start..end, boundary.type)
      }
    }

    private fun parseInstant(value: String): Instant? =
      try {
        Instant.parse(value)
      }
      catch (_: DateTimeParseException) {
        null
      }
  }
}

data class GiteaLogLine(val timestamp: Instant?, val text: String, val kind: Kind) {
  enum class Kind { NORMAL, GROUP, COMMAND, ERROR, WARNING, NOTICE, DEBUG }
}

/**
 * Lines of one part of the job. [title]: for [Type.STEP] the step's name, or its `Run …` header if the step couldn't be
 * matched; `null` for the other types (the UI names them).
 */
data class GiteaLogSection(val title: String?, val step: GiteaStep?, val lines: IntRange, val type: Type) {
  enum class Type { SET_UP, STEP, COMPLETE }
}
