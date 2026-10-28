package de.hendrikfrahmann.giteaplugin.actions.ui.details

import com.intellij.collaboration.ui.CollaborationToolsUIUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsDownloads
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaArtifact
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaRun
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaStep
import de.hendrikfrahmann.giteaplugin.actions.ui.GiteaActionsUiUtil
import de.hendrikfrahmann.giteaplugin.actions.ui.log.GiteaJobLogPanel
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.request.loadRunJobs
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import java.io.IOException
import java.net.URI
import java.util.Date
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlin.reflect.KMutableProperty0

/**
 * The right side of an Actions tab, like the Services tool window's Docker pages: a header (icon, name, id, status, the
 * main button) and label/value sections for what is selected in the runs tree.
 * - run: its details and its jobs (a click selects the job in the tree)
 * - job / step: "Log" and "Details" tabs – the log from [GiteaJobLogPanel], the details with the job's steps
 * - artifact: size and dates, a prominent "Download" button
 *
 * The toolbar above (refresh, follow, timestamps, soft wraps, cancel, run logs, browser) stays level with the tree's toolbar.
 * Lives as long as [parentCs] (the tab).
 */
class GiteaActionsDetailsPanel(
  private val project: Project,
  private val connection: GiteaProjectConnection,
  parentCs: CoroutineScope,
  /** Selects a job of a run in the tree (the job links of the run page). */
  private val selectJob: (GiteaRun, Long) -> Unit,
) {
  private val cs = parentCs.childScope(Dispatchers.EDT + ModalityState.any().asContextElement())
  private val logPanel = GiteaJobLogPanel(project, connection, cs)
  private val repositoryWeb: URI get() = connection.repo.repository.getWebURI()

  private sealed interface Page {
    val run: GiteaRun

    data class RunPage(override val run: GiteaRun, val workflowName: String?) : Page
    data class JobPage(override val run: GiteaRun, val workflowName: String?, val jobId: Long) : Page
    data class ArtifactPage(override val run: GiteaRun, val artifact: GiteaArtifact) : Page
  }

  private var page: Page? = null
  private var loadJob: Job? = null

  /** Newest state of the job page's job: from the tree first, then from the log panel's polling. */
  private var job: GiteaJob? = null

  private val content = Wrapper()

  /** Shared actions and load errors: above a run or artifact page, at the top of a job page's "Log" tab. */
  private val toolbarRow: JComponent = JPanel(BorderLayout()).also { row ->
    val toolbar = ActionManager.getInstance().createActionToolbar("Gitea.Actions.Log", createToolbarActions(), true)
    // the tab's data context (selected node, controller) for the shared actions like "Cancel Run"
    toolbar.targetComponent = content
    row.border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.headerBorderBackground())
    row.add(toolbar.component, BorderLayout.WEST)
    row.add(logPanel.errorComponent, BorderLayout.CENTER)
  }

  /** Icon, name, id and status of the job, between toolbar and log. */
  private val jobInfo = Wrapper().apply {
    border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.headerBorderBackground()),
                                   JBUI.Borders.empty(INFO_VERTICAL_PADDING, PAGE_PADDING))
  }
  private val jobDetails = Wrapper()

  /** Top of the "Log" tab: [toolbarRow] (added while the job page is shown) and [jobInfo]. */
  private val logTabTop = JPanel(BorderLayout()).apply { add(jobInfo, BorderLayout.SOUTH) }

  /** Disposes the job page's tabs together with [cs]. */
  private val tabsDisposable = Disposer.newDisposable("Gitea Actions job tabs")
  private val logTab = TabInfo(JPanel(BorderLayout()).apply {
    add(logTabTop, BorderLayout.NORTH)
    add(logPanel.component, BorderLayout.CENTER)
  }).setText(GiteaBundle.message("actions.details.tab.log"))
  private val detailsTab = TabInfo(ScrollPaneFactory.createScrollPane(jobDetails, true))
    .setText(GiteaBundle.message("actions.details.tab.details"))

  /** The job page: the platform's tabs "Log" (toolbar, job info, log) and "Details". */
  private val jobTabs = JBTabsFactory.createTabs(project, tabsDisposable).apply {
    addTab(logTab)
    addTab(detailsTab)
  }

  val component: JComponent = content

  init {
    cs.coroutineContext.job.invokeOnCompletion {
      ApplicationManager.getApplication().invokeLater({ Disposer.dispose(tabsDisposable) }, ModalityState.any())
    }
    setContent(placeholder())
    logPanel.onJobChanged = { loaded ->
      val current = page
      if (current is Page.JobPage && current.jobId == loaded.id) {
        job = loaded
        renderJob(current, loaded)
      }
    }
  }

  /** Job [job] of [run]: its log (at step [stepNumber] if given) and details. */
  fun showJob(run: GiteaRun, workflowName: String?, job: GiteaJob, stepNumber: Long? = null) {
    val newPage = Page.JobPage(run, workflowName, job.id)
    val sameJob = (page as? Page.JobPage)?.jobId == job.id
    loadJob?.cancel()
    page = newPage
    // the same job again: keep the state the log panel polled, it is newer than the tree's
    val shownJob = this.job?.takeIf { sameJob } ?: job
    this.job = shownJob
    renderJob(newPage, shownJob)
    logPanel.show(job.id, stepNumber)
    if (stepNumber != null) {
      jobTabs.select(logTab, false)
    }
    setContent(jobTabs.component)
  }

  /** Run [run]: its details, then its jobs once loaded. */
  fun showRun(run: GiteaRun, workflowName: String?) {
    val newPage = Page.RunPage(run, workflowName)
    leaveJobPage()
    page = newPage
    setContent(runPage(newPage, null, null))
    loadJob = cs.launch {
      val (jobs, error) = try {
        withContext(Dispatchers.Default) { connection.api.loadRunJobs(connection.repo.repository.path, run.id).map(GiteaJob::from) } to null
      }
      catch (e: IOException) {
        null to e.localizedMessage.orEmpty()
      }
      catch (e: GiteaHttpStatusError) {
        null to e.localizedMessage
      }
      if (page == newPage) {
        setContent(runPage(newPage, jobs, error))
      }
    }
  }

  /** Artifact [artifact] of [run]: details and a "Download" button. */
  fun showArtifact(run: GiteaRun, artifact: GiteaArtifact) {
    val newPage = Page.ArtifactPage(run, artifact)
    leaveJobPage()
    page = newPage
    setContent(artifactPage(newPage))
  }

  /** Nothing to show for the selection. */
  fun clear() {
    leaveJobPage()
    page = null
    setContent(placeholder())
  }

  private fun leaveJobPage() {
    loadJob?.cancel()
    if (page is Page.JobPage) {
      logPanel.clear()
    }
    job = null
  }

  /** Shows [page]; the toolbar goes into the job page's "Log" tab, above every other page. */
  private fun setContent(page: JComponent) {
    if (page === jobTabs.component) {
      logTabTop.add(toolbarRow, BorderLayout.NORTH)
      content.setContent(jobTabs.component)
    }
    else {
      content.setContent(JPanel(BorderLayout()).apply {
        add(toolbarRow, BorderLayout.NORTH)
        add(page, BorderLayout.CENTER)
      })
    }
    content.revalidate()
    content.repaint()
  }

  private fun placeholder(): JComponent = JBLabel(GiteaBundle.message("actions.details.select"), SwingConstants.CENTER).apply {
    foreground = UIUtil.getContextHelpForeground()
  }

  // ---- pages

  private fun renderJob(page: Page.JobPage, job: GiteaJob) {
    jobInfo.setContent(titleRow(GiteaActionsUiUtil.statusIcon(job.status, animated = true), job.name, "#${job.id}", job.status))
    jobDetails.setContent(panel {
      group(GiteaBundle.message("actions.details.job")) {
        statusRow(job.status)
        textRow("actions.details.duration", job.durationMillis()?.let(GiteaActionsUiUtil::formatDuration))
        textRow("actions.details.runner", job.runnerName)
        textRow("actions.details.started", job.startedAt?.let(DateFormatUtil::formatDateTime))
      }
      if (job.steps.isNotEmpty()) {
        group(GiteaBundle.message("actions.details.steps")) {
          for (step in job.steps) {
            stepRow(step)
          }
        }
      }
      group(GiteaBundle.message("actions.details.run")) {
        runRows(page.run, page.workflowName, withStatus = true)
      }
    }.withPageBorder())
    jobInfo.revalidate()
    jobDetails.revalidate()
    jobTabs.component.repaint()
  }

  private fun runPage(page: Page.RunPage, jobs: List<GiteaJob>?, error: String?): JComponent {
    val run = page.run
    val title = "${page.workflowName ?: run.workflowFile.orEmpty()} #${run.number}"
    val details = panel {
      group(GiteaBundle.message("actions.details.run")) {
        runRows(run, page.workflowName, withStatus = false)
      }
      group(GiteaBundle.message("actions.details.jobs")) {
        when {
          error != null -> row { label(GiteaBundle.message("actions.details.jobs.error", error)).applyToComponent { foreground = UIUtil.getErrorForeground() } }
          jobs == null -> row { label(GiteaBundle.message("actions.details.jobs.loading")).applyToComponent { foreground = UIUtil.getContextHelpForeground() } }
          jobs.isEmpty() -> row { label(GiteaBundle.message("actions.details.jobs.none")).applyToComponent { foreground = UIUtil.getContextHelpForeground() } }
          else -> for (job in jobs) {
            row {
              link(job.name) { selectJob(run, job.id) }.applyToComponent { icon = GiteaActionsUiUtil.statusIcon(job.status) }
              secondaryLabel(job.durationMillis()?.let(GiteaActionsUiUtil::formatDuration))
            }
          }
        }
      }
    }.withPageBorder()
    return JPanel(BorderLayout()).apply {
      add(header(GiteaActionsUiUtil.statusIcon(run.status, animated = true), title, run.headSha?.take(SHORT_SHA), run.status, emptyList()),
          BorderLayout.NORTH)
      add(ScrollPaneFactory.createScrollPane(details, true), BorderLayout.CENTER)
    }
  }

  private fun artifactPage(page: Page.ArtifactPage): JComponent {
    val artifact = page.artifact
    val download = with(CollaborationToolsUIUtil) {
      JButton(GiteaBundle.message("actions.details.download"), AllIcons.Actions.Download).defaultButton()
    }.apply {
      isEnabled = !artifact.expired
      addActionListener { GiteaActionsDownloads.getInstance(project).downloadArtifact(connection, artifact) }
    }
    val details = panel {
      group(GiteaBundle.message("actions.details.artifact")) {
        textRow("actions.details.size", StringUtil.formatFileSize(artifact.sizeInBytes))
        textRow("actions.details.created", artifact.createdAt?.let(DateFormatUtil::formatDateTime))
        if (artifact.expired) {
          row(GiteaBundle.message("actions.details.expires")) {
            label(GiteaBundle.message("actions.details.expired")).applyToComponent { foreground = UIUtil.getErrorForeground() }
          }
          row { comment(GiteaBundle.message("actions.details.artifact.expired")) }
        }
        else {
          textRow("actions.details.expires", artifact.expiresAt?.let(DateFormatUtil::formatDateTime))
        }
      }
      group(GiteaBundle.message("actions.details.run")) {
        runRows(page.run, null, withStatus = true)
      }
    }.withPageBorder()
    return JPanel(BorderLayout()).apply {
      add(header(AllIcons.FileTypes.Archive, artifact.name, StringUtil.formatFileSize(artifact.sizeInBytes), null, listOf(download)), BorderLayout.NORTH)
      add(ScrollPaneFactory.createScrollPane(details, true), BorderLayout.CENTER)
    }
  }

  // ---- building blocks

  /** Docker-page header: [titleRow] and [buttons] on the right. */
  private fun header(icon: Icon?, title: @Nls String, id: String?, status: GiteaActionStatus?, buttons: List<JComponent>): JComponent =
    JPanel(BorderLayout(JBUI.scale(HEADER_GAP), 0)).apply {
      border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.headerBorderBackground()),
                                     JBUI.Borders.empty(HEADER_VERTICAL_PADDING, PAGE_PADDING))
      add(titleRow(icon, title, id, status), BorderLayout.CENTER)
      if (buttons.isNotEmpty()) {
        add(JPanel(HorizontalLayout(JBUI.scale(ITEM_GAP), SwingConstants.CENTER)).apply {
          isOpaque = false
          buttons.forEach(::add)
        }, BorderLayout.EAST)
      }
    }

  /** Icon, bold name, grey id, status after a separator. */
  private fun titleRow(icon: Icon?, title: @Nls String, id: String?, status: GiteaActionStatus?): JComponent =
    JPanel(HorizontalLayout(JBUI.scale(ITEM_GAP), SwingConstants.CENTER)).apply {
      isOpaque = false
      add(JBLabel(title, icon, SwingConstants.LEADING).apply {
        font = JBFont.label().asBold()
        iconTextGap = JBUI.scale(ITEM_GAP)
      })
      if (!id.isNullOrBlank()) {
        add(JBLabel(id).apply { foreground = UIUtil.getContextHelpForeground() })
      }
      if (status != null) {
        add(JBLabel(GiteaActionsUiUtil.statusText(status)).apply {
          border = JBUI.Borders.compound(JBUI.Borders.customLineLeft(JBUI.CurrentTheme.ToolWindow.headerBorderBackground()),
                                         JBUI.Borders.emptyLeft(HEADER_GAP))
        })
      }
    }

  private fun JComponent.withPageBorder(): JComponent = apply { border = JBUI.Borders.empty(ITEM_GAP, PAGE_PADDING) }

  /** A label/value row; nothing for an empty value. [labelKey] is a bundle key. */
  private fun Panel.textRow(labelKey: String, value: String?) {
    if (value.isNullOrBlank()) {
      return
    }
    row(GiteaBundle.message(labelKey)) { cell(JBLabel(value).setCopyable(true)) }
  }

  private fun Panel.statusRow(status: GiteaActionStatus) {
    row(GiteaBundle.message("actions.details.status")) {
      cell(JBLabel(GiteaActionsUiUtil.statusText(status), GiteaActionsUiUtil.statusIcon(status), SwingConstants.LEADING))
    }
  }

  private fun Row.secondaryLabel(text: String?) {
    if (text != null) {
      label(text).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
    }
  }

  /** A step: opens the log at it. */
  private fun Panel.stepRow(step: GiteaStep) {
    row {
      link(step.name) {
        jobTabs.select(logTab, false)
        logPanel.showStep(step.number)
      }.applyToComponent { icon = GiteaActionsUiUtil.statusIcon(step.status) }
      secondaryLabel(durationOf(step.startedAt, step.completedAt))
    }
  }

  /** The run's rows: title, (status, duration,) pull request, branch, event, actor, attempt, commit, start. */
  private fun Panel.runRows(run: GiteaRun, workflowName: String?, withStatus: Boolean) {
    textRow("actions.details.workflow", workflowName ?: run.workflowFile)
    textRow("actions.details.run.title", "#${run.number}: ${run.title}")
    if (withStatus) {
      statusRow(run.status)
    }
    textRow("actions.details.duration", run.durationMillis()?.let(GiteaActionsUiUtil::formatDuration))
    run.pullRequestNumber?.let { number ->
      row(GiteaBundle.message("actions.details.pull.request")) {
        link("#$number") {
          GiteaToolWindowProjectViewModel.getInstance(project).activateAndAwaitProject { openPullRequest(number, focus = true) }
        }
      }
    }
    textRow("actions.details.branch", run.branch)
    textRow("actions.details.event", run.event)
    textRow("actions.details.actor", run.actor?.login)
    textRow("actions.details.attempt", run.attempt.takeIf { it > 1 }?.toString())
    run.headSha?.let { sha ->
      row(GiteaBundle.message("actions.details.commit")) {
        link(sha.take(SHORT_SHA)) { BrowserUtil.browse(GiteaWebUrls.commit(repositoryWeb, sha)) }
      }
    }
    textRow("actions.details.started", (run.startedAt ?: run.createdAt)?.let(DateFormatUtil::formatDateTime))
  }

  private fun durationOf(start: Date?, end: Date?): String? {
    if (start == null || end == null) {
      return null
    }
    return (end.time - start.time).takeIf { it >= 0 }?.let(GiteaActionsUiUtil::formatDuration)
  }

  // ---- toolbar

  private fun createToolbarActions() = DefaultActionGroup().apply {
    add(object : PageAction("actions.log.refresh", AllIcons.Actions.Refresh) {
      override fun isEnabled(page: Page) = page !is Page.ArtifactPage
      override fun perform(page: Page) {
        when (page) {
          is Page.JobPage -> logPanel.refresh()
          is Page.RunPage -> showRun(page.run, page.workflowName)
          is Page.ArtifactPage -> {}
        }
      }
    })
    add(JobToggleAction("actions.log.follow", AllIcons.RunConfigurations.Scroll_down, logPanel::follow))
    add(JobToggleAction("actions.log.timestamps", AllIcons.Scope.Scratches, logPanel::showTimestamps))
    add(JobToggleAction("actions.log.soft.wraps", AllIcons.Actions.ToggleSoftWrap, logPanel::softWraps))
    // the tab's action: it reads the selected node and the controller from the tab's data context
    ActionManager.getInstance().getAction("Gitea.Actions.Run.Cancel")?.let(::add)
    addSeparator()
    add(object : PageAction("actions.log.download.run", AllIcons.Actions.Download) {
      override fun isEnabled(page: Page) = page !is Page.ArtifactPage
      override fun perform(page: Page) = GiteaActionsDownloads.downloadRunLogs(project, connection, page.run.id)
    })
    add(object : PageAction("actions.open.in.browser", AllIcons.General.Web) {
      override fun perform(page: Page) {
        BrowserUtil.browse(when (page) {
          is Page.JobPage -> GiteaWebUrls.actionsJob(repositoryWeb, page.run.id, page.jobId)
          else -> GiteaWebUrls.actionsRun(repositoryWeb, page.run.id)
        })
      }
    })
  }

  /** Toolbar action for the shown page; hidden where it can't apply (e.g. run logs on an artifact page). */
  private abstract inner class PageAction(key: String, icon: Icon) : DumbAwareAction(GiteaBundle.messagePointer(key), icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      val current = page
      e.presentation.isVisible = current == null || isEnabled(current)
      e.presentation.isEnabled = current != null
    }

    override fun actionPerformed(e: AnActionEvent) {
      page?.let(::perform)
    }

    open fun isEnabled(page: Page): Boolean = true
    abstract fun perform(page: Page)
  }

  /** Log toggle, only on a job page (the toolbar sits in its "Log" tab); hidden on run and artifact pages. */
  private inner class JobToggleAction(key: String, icon: Icon, private val option: KMutableProperty0<Boolean>)
    : DumbAwareToggleAction(GiteaBundle.messagePointer(key), { null }, icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      super.update(e)
      val current = page
      e.presentation.isVisible = current == null || current is Page.JobPage
      e.presentation.isEnabled = current is Page.JobPage
    }

    override fun isSelected(e: AnActionEvent): Boolean = option.get()

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      option.set(state)
    }
  }

  private companion object {
    const val INFO_VERTICAL_PADDING = 6
    const val SHORT_SHA = 10
    const val PAGE_PADDING = 12
    const val HEADER_VERTICAL_PADDING = 10
    const val HEADER_GAP = 12
    const val ITEM_GAP = 8
  }
}
