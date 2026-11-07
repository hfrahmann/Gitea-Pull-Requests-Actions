package de.hendrikfrahmann.giteaplugin.actions.ui.log

import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
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
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJobLog
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogLine
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogSection
import de.hendrikfrahmann.giteaplugin.actions.ui.GiteaActionsUiUtil
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import java.awt.Font
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Shows a job log like the Build and Services tool windows: one folded section per step ("Set up job", the steps,
 * "Complete job"), `::group::` blocks folded inside, ANSI colours, `::error::`/`##[error]` lines highlighted, timestamps
 * on demand, and – in follow mode – the end of a running job in view. A log without sections (the local runner's own
 * output) is shown as plain lines. Where the log comes from is up to the caller: [GiteaJobLogPanel] polls Gitea, the
 * local run console reads the runner's output. EDT only.
 */
class GiteaJobLogView(private val project: Project) : Disposable {
  private val document = EditorFactory.getInstance().createDocument("").also { UndoUtil.disableUndoFor(it) }
  private val editor = (EditorFactory.getInstance().createViewer(document, project, EditorKind.CONSOLE) as EditorEx).apply {
    settings.isLineNumbersShown = false
    settings.isFoldingOutlineShown = true
    settings.isLineMarkerAreaShown = true
    settings.isCaretRowShown = false
    settings.additionalLinesCount = 0
    settings.isUseSoftWraps = GiteaProjectSettings.getInstance(this@GiteaJobLogView.project).actionsLogSoftWraps
    setHorizontalScrollbarVisible(true)
    setVerticalScrollbarVisible(true)
  }

  val component: JComponent get() = editor.component
  val preferredFocusableComponent: JComponent get() = editor.contentComponent

  /** The log, job and empty-log text last shown, to render again when an option changes. */
  private class Shown(val log: GiteaJobLog, val job: GiteaJob, val emptyText: String)

  private var shown: Shown? = null
  private var pendingStep: Long? = null

  /** A log is shown (not a message, not nothing). */
  val hasLog: Boolean get() = shown != null

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
      shown?.let { render(it, firstRender = false) }
    }

  /** Wrap long lines at the window's edge. */
  var softWraps: Boolean
    get() = editor.settings.isUseSoftWraps
    set(value) {
      GiteaProjectSettings.getInstance(project).actionsLogSoftWraps = value
      editor.settings.isUseSoftWraps = value
    }

  /** Fold regions of the current text by a stable key, so re-rendering keeps what the user expanded. */
  private val foldKeys = mutableMapOf<FoldRegion, String>()
  private val sectionOffsets = mutableMapOf<GiteaLogSection, Int>()

  /** Forgets the shown log, its folds and the scroll position: another job comes next. */
  fun reset() {
    shown = null
    follow = true
    pendingStep = null
    foldKeys.clear()
    sectionOffsets.clear()
    editor.markupModel.removeAllHighlighters()
    editor.foldingModel.runBatchFoldingOperation { editor.foldingModel.allFoldRegions.forEach(editor.foldingModel::removeFoldRegion) }
  }

  /** A message instead of a log ("Loading…"); [reset] first when it replaces another job's log. */
  fun showMessage(text: String) {
    setText(text)
  }

  /** Shows [log] of [job] (again); the user's fold states survive by key, [emptyText] stands in for an empty log. */
  fun show(log: GiteaJobLog, job: GiteaJob, emptyText: String) {
    val firstRender = shown == null
    val next = Shown(log, job, emptyText)
    shown = next
    render(next, firstRender)
  }

  /** The shown log at step [stepNumber]; before the first [show], when it comes. */
  fun showStep(stepNumber: Long) {
    if (shown != null) {
      navigateToStep(stepNumber)
    }
    else {
      pendingStep = stepNumber
    }
  }

  /** Closing the project releases its editors itself, possibly before this view is disposed: release only once. */
  override fun dispose() {
    if (!editor.isDisposed) {
      EditorFactory.getInstance().releaseEditor(editor)
    }
  }

  private fun setText(text: CharSequence) {
    runWriteAction {
      document.setReadOnly(false)
      document.setText(text)
      document.setReadOnly(true)
    }
  }

  private fun render(shown: Shown, firstRender: Boolean) {
    if (editor.isDisposed) {
      return
    }
    val previousFolds = foldKeys.mapNotNull { (region, key) -> (key to region.isExpanded).takeIf { region.isValid } }.toMap()
    val rendered = buildText(shown)
    setText(rendered.text)
    applyHighlights(rendered.highlights)
    applyFolds(shown.log, rendered, previousFolds, shown.job)

    val step = pendingStep
    pendingStep = null
    when {
      step != null -> navigateToStep(step)
      follow && (shown.job.status.isActive || firstRender) -> scrollToEnd()
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

  private fun buildText(shown: Shown): RenderedLog {
    val parsed = shown.log
    val rendered = RenderedLog()
    rendered.lineEnds = IntArray(parsed.lines.size)
    val decoder = AnsiEscapeDecoder()
    if (parsed.lines.isEmpty()) {
      rendered.text.append(shown.emptyText)
    }
    if (parsed.sections.isEmpty()) {
      parsed.lines.indices.forEach { appendLine(rendered, parsed, it, decoder) }
    }
    for (section in parsed.sections) {
      appendSectionTitle(rendered, section, parsed, shown.job)
      section.lines.forEach { appendLine(rendered, parsed, it, decoder) }
    }
    if (rendered.text.endsWith('\n')) {
      rendered.text.setLength(rendered.text.length - 1)
    }
    return rendered
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

  private fun appendLine(rendered: RenderedLog, parsed: GiteaJobLog, index: Int, decoder: AnsiEscapeDecoder) {
    val line = parsed.lines[index]
    if (showTimestamps && line.timestamp != null) {
      val start = rendered.text.length
      rendered.text.append(TIMESTAMP_FORMAT.format(line.timestamp)).append("  ")
      rendered.highlights += Highlight(start, rendered.text.length, TIMESTAMP)
    }
    appendContent(rendered.text, line, decoder, rendered.highlights)
    rendered.lineEnds[index] = rendered.text.length
    rendered.text.append('\n')
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
    if (editor.isDisposed) {
      return
    }
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

  private companion object {
    val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    val BOLD = TextAttributes(null, null, null, null, Font.BOLD)
    val STEP_HEADER = TextAttributes().apply {
      backgroundColor = JBColor.namedColor("Gitea.Actions.Log.stepHeaderBackground", JBColor(0xEBECF0, 0x393B40))
    }
    val TIMESTAMP = TextAttributes().apply { foregroundColor = UIUtil.getContextHelpForeground() }
  }
}
