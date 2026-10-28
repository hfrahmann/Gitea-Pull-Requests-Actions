package de.hendrikfrahmann.giteaplugin.actions.ui.log

import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJobLog
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogLine
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogSection
import de.hendrikfrahmann.giteaplugin.actions.ui.GiteaActionsUiUtil
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.request.GiteaJobLogChunk
import de.hendrikfrahmann.giteaplugin.api.request.getJob
import de.hendrikfrahmann.giteaplugin.api.request.loadJobLogFrom
import de.hendrikfrahmann.giteaplugin.refresh.GiteaRefreshService
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Font
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.JComponent
import kotlin.time.Duration.Companion.seconds

/**
 * The log of a job, the "Log" tab of the job page next to the runs tree, like the Build and Services tool
 * windows: one folded section per step ("Set up job", the steps, "Complete job"), `::group::` blocks folded inside, ANSI
 * colours, `::error::`/`##[error]` lines highlighted, timestamps on demand. While the job runs, the log and the job are
 * polled and – in follow mode – the log is scrolled to the end; every new state of the job goes to [onJobChanged] (the
 * job page's header and details). The toolbar lives in [de.hendrikfrahmann.giteaplugin.actions.ui.details.GiteaActionsDetailsPanel].
 * Lives as long as [parentCs] (the tab).
 */
class GiteaJobLogPanel(private val project: Project, private val connection: GiteaProjectConnection, parentCs: CoroutineScope) {
  private val cs = parentCs.childScope(Dispatchers.EDT + ModalityState.any().asContextElement())

  /** The job whose log is shown. */
  private class Target(val jobId: Long)

  private var target: Target? = null
  private var pollJob: Job? = null
  private var pendingStep: Long? = null

  private val repository get() = connection.repo.repository

  private val document = EditorFactory.getInstance().createDocument("").also { UndoUtil.disableUndoFor(it) }
  private val editor = (EditorFactory.getInstance().createViewer(document, project, EditorKind.CONSOLE) as EditorEx).apply {
    settings.isLineNumbersShown = false
    settings.isFoldingOutlineShown = true
    settings.isLineMarkerAreaShown = true
    settings.isCaretRowShown = false
    settings.additionalLinesCount = 0
    settings.isUseSoftWraps = GiteaProjectSettings.getInstance(this@GiteaJobLogPanel.project).actionsLogSoftWraps
    setHorizontalScrollbarVisible(true)
    setVerticalScrollbarVisible(true)
  }

  /** Load errors, shown in the details panel's toolbar row. */
  val errorComponent: JComponent = JBLabel(AllIcons.General.Error).apply {
    foreground = JBColor.RED
    border = JBUI.Borders.empty(0, 8)
    minimumSize = Dimension(0, 0)
    isVisible = false
  }

  private var job: GiteaJob? = null
  private var logText: String? = null
  /** Gitea has deleted the log of the finished job (log retention); shown as a message, not as an error. */
  private var logRemoved = false
  private var log: GiteaJobLog? = null

  /** Every loaded state of the shown job that differs from the one before. */
  var onJobChanged: (GiteaJob) -> Unit = {}

  /** Keep the end of a running job's log in view. */
  var follow: Boolean = true
    set(value) {
      field = value
      if (value) {
        scrollToEnd()
      }
    }

  var showTimestamps: Boolean
    get() = GiteaProjectSettings.getInstance(project).actionsLogTimestamps
    set(value) {
      GiteaProjectSettings.getInstance(project).actionsLogTimestamps = value
      if (log != null) {
        render()
      }
    }
  /** Wrap long lines at the window's edge. */
  var softWraps: Boolean
    get() = editor.settings.isUseSoftWraps
    set(value) {
      GiteaProjectSettings.getInstance(project).actionsLogSoftWraps = value
      editor.settings.isUseSoftWraps = value
    }

  private val refreshRequests = MutableStateFlow(0)

  /** Fold regions of the current text by a stable key, so re-rendering keeps what the user expanded. */
  private val foldKeys = mutableMapOf<FoldRegion, String>()
  private val sectionOffsets = mutableMapOf<GiteaLogSection, Int>()

  val component: JComponent get() = editor.component

  init {
    cs.coroutineContext.job.invokeOnCompletion {
      ApplicationManager.getApplication().invokeLater({ releaseEditor() }, ModalityState.any())
    }
  }

  /** Shows the log of job [jobId] (at step [stepNumber] if given); the same job again only moves to the step. */
  fun show(jobId: Long, stepNumber: Long? = null) {
    if (target?.jobId == jobId) {
      target = Target(jobId)
      stepNumber?.let(::showStep)
      return
    }
    reset(Target(jobId))
    pendingStep = stepNumber
    setText(GiteaBundle.message("actions.loading"))
    updateStatus(null)
    pollJob = cs.launch { pollLog(jobId) }
  }

  /** Stops polling and forgets the job (another kind of page is shown). */
  fun clear() {
    reset(null)
    setText("")
    updateStatus(null)
  }

  /** Loads job and log again now. */
  fun refresh() {
    refreshRequests.update { it + 1 }
  }

  /** The log of the shown job at step [stepNumber]. */
  fun showStep(stepNumber: Long) {
    if (log != null) {
      navigateToStep(stepNumber)
    }
    else {
      pendingStep = stepNumber
    }
  }

  /** Closing the project releases its editors itself, possibly before the scope ends: release only once. */
  private fun releaseEditor() {
    if (!editor.isDisposed) {
      EditorFactory.getInstance().releaseEditor(editor)
    }
  }

  private fun reset(newTarget: Target?) {
    pollJob?.cancel()
    pollJob = null
    target = newTarget
    job = null
    logText = null
    logRemoved = false
    log = null
    follow = true
    pendingStep = null
    foldKeys.clear()
    sectionOffsets.clear()
    editor.markupModel.removeAllHighlighters()
    editor.foldingModel.runBatchFoldingOperation { editor.foldingModel.allFoldRegions.forEach(editor.foldingModel::removeFoldRegion) }
  }

  private fun setText(text: CharSequence) {
    runWriteAction {
      document.setReadOnly(false)
      document.setText(text)
      document.setReadOnly(true)
    }
  }

  /**
   * Like Gitea's web view: while the job runs, the log is asked for every second, but only for the bytes after the
   * known end (`Range`), so a long log isn't downloaded again and again; the job (status, steps) every 3 seconds. New
   * lines still come in batches – the runner reports them every few seconds (`log_report_interval`).
   */
  private suspend fun pollLog(jobId: Long) {
    val poll = LogPoll(jobId)
    var seenRefresh = -1
    while (true) {
      val refreshRequested = refreshRequests.value != seenRefresh
      seenRefresh = refreshRequests.value
      try {
        poll.loadChanges(reloadJob = refreshRequested)
        updateStatus(null)
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        updateStatus(e.localizedMessage)
      }
      val interval = when {
        job?.status?.isActive != false -> POLL_INTERVAL
        else -> IDLE_INTERVAL
      }
      GiteaRefreshService.scheduler.awaitNextPoll("Gitea job log $jobId", interval) {
        refreshRequests.first { it != seenRefresh }
      }
    }
  }

  /** What one poll of job [jobId] knows about the previous ones: the log length and when the job was loaded. */
  private inner class LogPoll(private val jobId: Long) {
    private var logBytes = 0L
    private var jobLoadedAt = 0L

    /** Loads the job (when due) and new log lines, and renders if anything changed. */
    suspend fun loadChanges(reloadJob: Boolean) {
      val jobChanged = reloadJobIfDue(reloadJob)
      val logChanged = appendNewLog()
      if (jobChanged || logChanged || log == null) {
        render()
      }
    }

    /** `true` if the job changed. */
    private suspend fun reloadJobIfDue(force: Boolean): Boolean {
      val now = System.nanoTime()
      val current = job
      if (current != null && !force && !isJobReloadDue(current, now)) {
        return false
      }
      val loaded = withContext(Dispatchers.Default) { GiteaJob.from(connection.api.getJob(repository.path, jobId)) }
      jobLoadedAt = now
      if (loaded == current) {
        return false
      }
      job = loaded
      onJobChanged(loaded)
      return true
    }

    private fun isJobReloadDue(current: GiteaJob, now: Long): Boolean =
      current.status.isActive && now - jobLoadedAt >= JOB_INTERVAL.inWholeNanoseconds

    /** `true` if there are new log bytes. */
    private suspend fun appendNewLog(): Boolean {
      val chunk = loadLogFrom(jobId, logBytes) ?: return false
      logText = when {
        chunk.isWhole -> chunk.text
        else -> logText.orEmpty() + chunk.text
      }
      logBytes = chunk.totalBytes
      return true
    }
  }

  /**
   * The log after [offset]; `null` when there is nothing new – and when there is no log: before the job has started,
   * because it never ran (skipped, cancelled while waiting), or because Gitea has deleted it ([logRemoved]). None of
   * these is an error.
   */
  private suspend fun loadLogFrom(jobId: Long, offset: Long): GiteaJobLogChunk? {
    try {
      return withContext(Dispatchers.Default) { connection.api.loadJobLogFrom(repository.path, jobId, offset) }
    }
    catch (e: GiteaHttpStatusError) {
      val current = job
      if (e.kind != GiteaHttpStatusError.Kind.NOT_FOUND || current == null) {
        throw e
      }
      // a finished job that ran had a log: Gitea answers 404 ("logs have been cleaned up") once it is expired
      logRemoved = current.status.isFinished && current.hasRun
      return null
    }
  }

  private fun updateStatus(error: String?) {
    val label = errorComponent as JBLabel
    label.text = error?.let { GiteaBundle.message("actions.log.error", it) }
    label.toolTipText = label.text
    label.isVisible = error != null
  }

  /** Rebuilds the editor from the parsed log; the user's fold states survive by key. */
  private fun render() {
    if (target == null) {
      return
    }
    val job = job ?: return
    val parsed = GiteaJobLog.parse(logText.orEmpty(), job.steps)
    val previousFolds = foldKeys.mapNotNull { (region, key) -> (key to region.isExpanded).takeIf { region.isValid } }.toMap()
    val firstRender = log == null
    log = parsed

    val rendered = buildText(parsed, job)
    setText(rendered.text)
    applyHighlights(rendered.highlights)
    applyFolds(parsed, rendered, previousFolds, job)

    val step = pendingStep
    pendingStep = null
    when {
      step != null -> navigateToStep(step)
      follow && (job.status.isActive || firstRender) -> scrollToEnd()
    }
  }

  /** The editor text of a parsed log: per section a title line, then its lines. */
  private class RenderedLog {
    val text = StringBuilder()
    val highlights = mutableListOf<Highlight>()
    val headers = mutableListOf<Pair<GiteaLogSection, IntRange>>()
    /** End offset of each log line in [text]. */
    lateinit var lineEnds: IntArray
  }

  private fun buildText(parsed: GiteaJobLog, job: GiteaJob): RenderedLog {
    val rendered = RenderedLog()
    rendered.lineEnds = IntArray(parsed.lines.size)
    val decoder = AnsiEscapeDecoder()
    if (parsed.lines.isEmpty()) {
      rendered.text.append(emptyLogText(job))
    }
    for (section in parsed.sections) {
      appendSectionTitle(rendered, section, parsed, job)
      for (index in section.lines) {
        appendLine(rendered, parsed.lines[index], decoder)
        rendered.lineEnds[index] = rendered.text.length
        rendered.text.append('\n')
      }
    }
    if (rendered.text.endsWith('\n')) {
      rendered.text.setLength(rendered.text.length - 1)
    }
    return rendered
  }

  private fun emptyLogText(job: GiteaJob): String = when {
    logRemoved -> GiteaBundle.message("actions.log.removed")
    job.status.isActive -> GiteaBundle.message("actions.log.waiting")
    !job.hasRun -> GiteaBundle.message("actions.log.not.run")
    else -> GiteaBundle.message("actions.log.empty")
  }

  /** Bold, with the section's result icon in the gutter and a bar over the whole width (like the Build tool window). */
  private fun appendSectionTitle(rendered: RenderedLog, section: GiteaLogSection, parsed: GiteaJobLog, job: GiteaJob) {
    val text = rendered.text
    val headerStart = text.length
    text.append(sectionTitle(section, parsed))
    rendered.headers += section to (headerStart until text.length)
    rendered.highlights += Highlight(headerStart, text.length, BOLD, sectionIcon(section, section === parsed.sections.last(), job))
    rendered.highlights += Highlight(headerStart, text.length, STEP_HEADER, layerBelow = true, wholeLines = true)
    text.append('\n')
  }

  private fun appendLine(rendered: RenderedLog, line: GiteaLogLine, decoder: AnsiEscapeDecoder) {
    if (showTimestamps && line.timestamp != null) {
      val start = rendered.text.length
      rendered.text.append(TIMESTAMP_FORMAT.format(line.timestamp)).append("  ")
      rendered.highlights += Highlight(start, rendered.text.length, TIMESTAMP)
    }
    appendContent(rendered.text, line, decoder, rendered.highlights)
  }

  private fun applyHighlights(highlights: List<Highlight>) {
    val markup = editor.markupModel
    markup.removeAllHighlighters()
    for (highlight in highlights.filter { it.end > it.start }) {
      val layer = when {
        highlight.layerBelow -> HighlighterLayer.SYNTAX - 1
        else -> HighlighterLayer.SYNTAX
      }
      val area = when {
        highlight.wholeLines -> HighlighterTargetArea.LINES_IN_RANGE
        else -> HighlighterTargetArea.EXACT_RANGE
      }
      val highlighter = markup.addRangeHighlighter(highlight.start, highlight.end, layer, highlight.attributes, area)
      highlight.icon?.let { icon -> highlighter.gutterIconRenderer = StatusGutterIcon(icon) }
    }
  }

  /** One fold per section (below its title) and per `::group::`; expanded as the user left them, else by default. */
  private fun applyFolds(parsed: GiteaJobLog, rendered: RenderedLog, previousFolds: Map<String, Boolean>, job: GiteaJob) {
    sectionOffsets.clear()
    foldKeys.clear()
    val folding = editor.foldingModel
    val lineEnds = rendered.lineEnds
    folding.runBatchFoldingOperation {
      folding.allFoldRegions.forEach(folding::removeFoldRegion)
      for ((section, header) in rendered.headers) {
        sectionOffsets[section] = header.first
        if (section.lines.isEmpty()) {
          continue
        }
        val region = folding.addFoldRegion(header.last + 1, lineEnds[section.lines.last], "") ?: continue
        val key = sectionKey(section)
        foldKeys[region] = key
        region.isExpanded = previousFolds[key] ?: expandedByDefault(section, job)
      }
      for (group in parsed.groups.filter { it.last > it.first }) {
        val region = folding.addFoldRegion(lineEnds[group.first], lineEnds[group.last], " …") ?: continue
        val key = "group:${group.first}"
        foldKeys[region] = key
        region.isExpanded = previousFolds[key] ?: false
      }
    }
  }

  /** The line text with its ANSI colours, a prefix and colour for errors and warnings, workflow commands greyed. */
  private fun appendContent(text: StringBuilder, line: GiteaLogLine, decoder: AnsiEscapeDecoder, highlights: MutableList<Highlight>) {
    val base = when (line.kind) {
      GiteaLogLine.Kind.ERROR -> ProcessOutputTypes.STDERR
      else -> ProcessOutputTypes.STDOUT
    }
    val kindAttributes = kindAttributes(line.kind)
    val start = text.length
    text.append(prefix(line.kind))
    decoder.escapeText(line.text, base) { chunk, key ->
      val chunkStart = text.length
      text.append(chunk)
      if (key != base && key != ProcessOutputTypes.STDOUT) {
        highlights += Highlight(chunkStart, text.length, ConsoleViewContentType.getConsoleViewType(key).attributes)
      }
    }
    if (kindAttributes != null) {
      highlights += Highlight(start, text.length, kindAttributes, layerBelow = true)
    }
  }

  private fun kindAttributes(kind: GiteaLogLine.Kind): TextAttributes? = when (kind) {
    GiteaLogLine.Kind.ERROR -> ConsoleViewContentType.ERROR_OUTPUT.attributes
    GiteaLogLine.Kind.WARNING -> ConsoleViewContentType.LOG_WARNING_OUTPUT.attributes
    GiteaLogLine.Kind.NOTICE -> ConsoleViewContentType.LOG_INFO_OUTPUT.attributes
    GiteaLogLine.Kind.DEBUG -> ConsoleViewContentType.LOG_DEBUG_OUTPUT.attributes
    GiteaLogLine.Kind.GROUP, GiteaLogLine.Kind.COMMAND -> ConsoleViewContentType.SYSTEM_OUTPUT.attributes
    GiteaLogLine.Kind.NORMAL -> null
  }

  private fun prefix(kind: GiteaLogLine.Kind): String = when (kind) {
    GiteaLogLine.Kind.ERROR -> GiteaBundle.message("actions.log.prefix.error")
    GiteaLogLine.Kind.WARNING -> GiteaBundle.message("actions.log.prefix.warning")
    else -> ""
  }

  private fun sectionTitle(section: GiteaLogSection, log: GiteaJobLog): String = when (section.type) {
    GiteaLogSection.Type.SET_UP -> GiteaBundle.message("actions.log.section.setup")
    GiteaLogSection.Type.COMPLETE -> GiteaBundle.message("actions.log.section.complete")
    GiteaLogSection.Type.STEP -> section.title ?: log.lines[section.lines.first].text
  }

  /**
   * Result icon of a section in the gutter: a matched step's own status; otherwise derived – a section followed by
   * another one is done (success), the last one shows the job's state (running, or its result when finished).
   */
  private fun sectionIcon(section: GiteaLogSection, isLast: Boolean, job: GiteaJob): Icon =
    GiteaActionsUiUtil.statusIcon(sectionStatus(section, isLast, job), animated = true)

  private fun sectionStatus(section: GiteaLogSection, isLast: Boolean, job: GiteaJob): GiteaActionStatus {
    section.step?.status?.let { return it }
    return when {
      !isLast -> GiteaActionStatus.SUCCESS
      job.status.isFinished -> job.status
      else -> GiteaActionStatus.RUNNING
    }
  }

  /** Failed and running steps are open, everything else folded (like Gitea's and GitHub's web UI). */
  private fun expandedByDefault(section: GiteaLogSection, job: GiteaJob): Boolean = when (section.type) {
    GiteaLogSection.Type.STEP -> section.step?.status == GiteaActionStatus.FAILURE || section.step?.status == GiteaActionStatus.RUNNING ||
                                 section.step == null && job.status.isActive
    else -> false
  }

  private fun sectionKey(section: GiteaLogSection): String = when (section.type) {
    GiteaLogSection.Type.STEP -> "step:${section.step?.number ?: section.lines.first}"
    else -> section.type.name
  }

  private fun navigateToStep(stepNumber: Long) {
    val (section, offset) = sectionOffsets.entries.find { it.key.step?.number == stepNumber }?.toPair() ?: return
    follow = false
    val folding = editor.foldingModel
    folding.runBatchFoldingOperation {
      foldKeys.entries.find { it.value == sectionKey(section) }?.key?.isExpanded = true
    }
    editor.caretModel.moveToOffset(offset)
    editor.scrollingModel.scrollToCaret(ScrollType.CENTER_DOWN)
  }

  private fun scrollToEnd() {
    val lastLine = (document.lineCount - 1).coerceAtLeast(0)
    editor.caretModel.moveToLogicalPosition(LogicalPosition(lastLine, 0))
    editor.scrollingModel.scrollTo(LogicalPosition(lastLine, 0), ScrollType.MAKE_VISIBLE)
  }

  private class Highlight(val start: Int, val end: Int, val attributes: TextAttributes?, val icon: Icon? = null, val layerBelow: Boolean = false,
                          val wholeLines: Boolean = false)

  private class StatusGutterIcon(private val icon: Icon) : GutterIconRenderer() {
    override fun getIcon(): Icon = icon
    override fun equals(other: Any?): Boolean = other is StatusGutterIcon && other.icon == icon
    override fun hashCode(): Int = icon.hashCode()
  }

  companion object {
    private val POLL_INTERVAL = 1.seconds
    private val JOB_INTERVAL = 3.seconds
    private val IDLE_INTERVAL = 600.seconds
    private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val BOLD = TextAttributes(null, null, null, null, Font.BOLD)
    private val STEP_HEADER = TextAttributes().apply {
      backgroundColor = JBColor.namedColor("Gitea.Actions.Log.stepHeaderBackground", JBColor(0xEBECF0, 0x393B40))
    }
    private val TIMESTAMP = TextAttributes().apply { foregroundColor = UIUtil.getContextHelpForeground() }
  }
}
