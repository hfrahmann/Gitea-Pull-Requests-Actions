package de.hendrikfrahmann.giteaplugin.actions.workflow.run

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.ui.GiteaActionsUiUtil
import de.hendrikfrahmann.giteaplugin.actions.ui.log.GiteaJobLogView
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import kotlin.reflect.KMutableProperty0
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Run tool window's view of a local workflow run, like the job log of a run on Gitea: on the left the runner's own
 * output and the jobs (each matrix combination on its own), on the right the selected one's log by step
 * ([GiteaJobLogView]). Reads `gitea-runner exec --json` from [processHandler] ([GiteaLocalRunOutput]). Until the user picks
 * an entry, the view follows the run: a running job, and at the end the first failed one.
 */
class GiteaLocalRunConsole(project: Project, processHandler: ProcessHandler) : ExecutionConsole {
  private val output = GiteaLocalRunOutput()
  private val view = GiteaJobLogView(project).also { Disposer.register(this, it) }
  private val cs = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
  private val changes = MutableStateFlow(0L)

  private val listModel = CollectionListModel<GiteaJob>()
  private val list = JBList(listModel).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = JobRenderer()
  }
  private val splitter = OnePixelSplitter(false, SPLITTER_KEY, DEFAULT_PROPORTION).apply {
    firstComponent = ScrollPaneFactory.createScrollPane(list, true)
    secondComponent = view.component
  }

  /** The user picked an entry: stop following the run. */
  private var userSelected = false
  private var selecting = false
  private var shownId: Long? = null
  private var renderedModification = -1L

  init {
    processHandler.addProcessListener(OutputListener(), this)
    list.addListSelectionListener { e ->
      if (!e.valueIsAdjusting && !selecting) {
        userSelected = true
        render()
      }
    }
    cs.launch {
      // at most a few renders a second, however fast the runner writes: StateFlow keeps only the latest change
      changes.collect {
        refresh()
        delay(REFRESH_INTERVAL)
      }
    }
  }

  override fun getComponent(): JComponent = splitter

  override fun getPreferredFocusableComponent(): JComponent = view.preferredFocusableComponent

  override fun dispose() {
    cs.cancel()
  }

  /** Follow, timestamps and soft wraps, for the Run tool window's toolbar. */
  fun createActions(): Array<AnAction> = arrayOf(
    ViewToggleAction("actions.log.follow", AllIcons.RunConfigurations.Scroll_down, view::follow),
    ViewToggleAction("actions.log.timestamps", AllIcons.Scope.Scratches, view::showTimestamps),
    ViewToggleAction("actions.log.soft.wraps", AllIcons.Actions.ToggleSoftWrap, view::softWraps),
  )

  /** Cuts the output into lines (it comes in chunks, stdout and stderr each on their own) for [output]. */
  private inner class OutputListener : ProcessListener {
    private val buffers = mutableMapOf<Key<*>, StringBuilder>()

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
      synchronized(buffers) {
        val buffer = buffers.getOrPut(outputType) { StringBuilder() }
        buffer.append(event.text)
        var end = buffer.indexOf("\n")
        while (end >= 0) {
          output.addLine(buffer.substring(0, end))
          buffer.delete(0, end + 1)
          end = buffer.indexOf("\n")
        }
      }
      changes.value = output.modificationCount
    }

    override fun processTerminated(event: ProcessEvent) {
      synchronized(buffers) {
        buffers.values.filter { it.isNotEmpty() }.forEach { output.addLine(it.toString()) }
        buffers.clear()
      }
      output.processTerminated(event.exitCode, event.processHandler.getUserData(ProcessHandler.TERMINATION_REQUESTED) == true)
      changes.value = output.modificationCount
    }
  }

  /** New jobs and states into the list, then the selected entry's log. */
  private fun refresh() {
    val entries = listOf(output.runnerJob(GiteaBundle.message("workflow.run.log.runner"))) + output.jobs()
    selecting = true
    try {
      entries.forEachIndexed { i, entry ->
        when {
          i >= listModel.size -> listModel.add(entry)
          listModel.getElementAt(i) != entry -> listModel.setElementAt(entry, i)
        }
      }
      if (!userSelected) {
        followRun(entries)
      }
    }
    finally {
      selecting = false
    }
    render()
  }

  /** Selects a running job; when none runs any more, the first failed one; else the selection stays. */
  private fun followRun(entries: List<GiteaJob>) {
    val jobs = entries.drop(1)
    val current = list.selectedValue
    if (current != null && current.id != GiteaLocalRunOutput.RUNNER_ID && current.status.isActive) {
      return
    }
    val next = jobs.firstOrNull { it.status.isActive } ?: jobs.firstOrNull { it.status == GiteaActionStatus.FAILURE } ?: current ?: entries.first()
    list.selectedIndex = entries.indexOfFirst { it.id == next.id }
  }

  private fun render() {
    val selected = list.selectedValue ?: return
    if (shownId == selected.id && renderedModification == output.modificationCount) {
      return
    }
    if (shownId != selected.id) {
      view.reset()
      shownId = selected.id
    }
    renderedModification = output.modificationCount
    val log = when (selected.id) {
      GiteaLocalRunOutput.RUNNER_ID -> output.runnerLog()
      else -> output.jobLog(selected.id)
    }
    view.show(log, selected, GiteaBundle.message("actions.log.waiting"))
  }

  private class JobRenderer : ColoredListCellRenderer<GiteaJob>() {
    override fun customizeCellRenderer(list: JList<out GiteaJob>, value: GiteaJob?, index: Int, selected: Boolean, hasFocus: Boolean) {
      value ?: return
      icon = GiteaActionsUiUtil.statusIcon(value.status, animated = true)
      append(value.name)
      if (value.id != GiteaLocalRunOutput.RUNNER_ID) {
        value.durationMillis()?.let { append("  " + GiteaActionsUiUtil.formatDuration(it), SimpleTextAttributes.GRAYED_ATTRIBUTES) }
      }
      toolTipText = GiteaActionsUiUtil.statusText(value.status)
    }
  }

  private class ViewToggleAction(key: String, icon: Icon, private val option: KMutableProperty0<Boolean>)
    : DumbAwareToggleAction(GiteaBundle.messagePointer(key), { null }, icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent): Boolean = option.get()
    override fun setSelected(e: AnActionEvent, state: Boolean) {
      option.set(state)
    }
  }

  private companion object {
    const val SPLITTER_KEY = "Gitea.LocalRun.Jobs"
    const val DEFAULT_PROPORTION = 0.22f
    val REFRESH_INTERVAL = 250.milliseconds
  }
}
