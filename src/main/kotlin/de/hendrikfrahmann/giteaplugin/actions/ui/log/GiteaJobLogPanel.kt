package de.hendrikfrahmann.giteaplugin.actions.ui.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJobLog
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.request.GiteaJobLogChunk
import de.hendrikfrahmann.giteaplugin.api.request.getJob
import de.hendrikfrahmann.giteaplugin.api.request.loadJobLogFrom
import de.hendrikfrahmann.giteaplugin.refresh.GiteaRefreshService
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
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
import javax.swing.JComponent
import kotlin.time.Duration.Companion.seconds

/**
 * The log of a job, the "Log" tab of the job page next to the runs tree, drawn by [GiteaJobLogView]. While the job runs,
 * the log and the job are polled and – in follow mode – the log is scrolled to the end; every new state of the job goes
 * to [onJobChanged] (the job page's header and details). The toolbar lives in
 * [de.hendrikfrahmann.giteaplugin.actions.ui.details.GiteaActionsDetailsPanel]. Lives as long as [parentCs] (the tab).
 */
class GiteaJobLogPanel(project: Project, private val connection: GiteaProjectConnection, parentCs: CoroutineScope) {
  private val cs = parentCs.childScope(Dispatchers.EDT + ModalityState.any().asContextElement())

  /** The job whose log is shown. */
  private class Target(val jobId: Long)

  private var target: Target? = null
  private var pollJob: Job? = null

  private val repository get() = connection.repo.repository

  private val view = GiteaJobLogView(project)

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

  /** Every loaded state of the shown job that differs from the one before. */
  var onJobChanged: (GiteaJob) -> Unit = {}

  /** Keep the end of a running job's log in view. */
  var follow: Boolean by view::follow
  var showTimestamps: Boolean by view::showTimestamps
  /** Wrap long lines at the window's edge. */
  var softWraps: Boolean by view::softWraps

  private val refreshRequests = MutableStateFlow(0)

  val component: JComponent get() = view.component

  init {
    cs.coroutineContext.job.invokeOnCompletion {
      ApplicationManager.getApplication().invokeLater({ Disposer.dispose(view) }, ModalityState.any())
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
    stepNumber?.let(view::showStep)
    view.showMessage(GiteaBundle.message("actions.loading"))
    updateStatus(null)
    pollJob = cs.launch { pollLog(jobId) }
  }

  /** Stops polling and forgets the job (another kind of page is shown). */
  fun clear() {
    reset(null)
    view.showMessage("")
    updateStatus(null)
  }

  /** Loads job and log again now. */
  fun refresh() {
    refreshRequests.update { it + 1 }
  }

  /** The log of the shown job at step [stepNumber]. */
  fun showStep(stepNumber: Long) {
    view.showStep(stepNumber)
  }

  private fun reset(newTarget: Target?) {
    pollJob?.cancel()
    pollJob = null
    target = newTarget
    job = null
    logText = null
    logRemoved = false
    view.reset()
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
      if (jobChanged || logChanged || !view.hasLog) {
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

  /** Shows the loaded log; the view keeps the user's fold states. */
  private fun render() {
    if (target == null) {
      return
    }
    val job = job ?: return
    view.show(GiteaJobLog.parse(logText.orEmpty(), job.steps), job, emptyLogText(job))
  }

  private fun emptyLogText(job: GiteaJob): String = when {
    logRemoved -> GiteaBundle.message("actions.log.removed")
    job.status.isActive -> GiteaBundle.message("actions.log.waiting")
    !job.hasRun -> GiteaBundle.message("actions.log.not.run")
    else -> GiteaBundle.message("actions.log.empty")
  }

  private companion object {
    val POLL_INTERVAL = 1.seconds
    val JOB_INTERVAL = 3.seconds
    val IDLE_INTERVAL = 600.seconds
  }
}
