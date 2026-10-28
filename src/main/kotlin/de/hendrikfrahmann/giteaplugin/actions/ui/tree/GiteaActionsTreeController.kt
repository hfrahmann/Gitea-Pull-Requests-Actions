package de.hendrikfrahmann.giteaplugin.actions.ui.tree

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.annotations.RequiresEdt
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsCurrentBranchService
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsNavigator
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaArtifact
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaRun
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaStep
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflow
import de.hendrikfrahmann.giteaplugin.actions.ui.toolwindow.GiteaActionsTab
import de.hendrikfrahmann.giteaplugin.actions.ui.toolwindow.GiteaActionsToolWindowViewModel
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunsDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaActionRunsFilter
import de.hendrikfrahmann.giteaplugin.api.request.getPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.getRun
import de.hendrikfrahmann.giteaplugin.api.request.loadRunArtifacts
import de.hendrikfrahmann.giteaplugin.api.request.loadRunAttemptJobs
import de.hendrikfrahmann.giteaplugin.api.request.loadRunJobs
import de.hendrikfrahmann.giteaplugin.api.request.loadRuns
import de.hendrikfrahmann.giteaplugin.api.request.loadWorkflowRuns
import de.hendrikfrahmann.giteaplugin.api.request.loadWorkflows
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.refresh.GiteaRefreshService
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import kotlin.time.Duration.Companion.seconds

private val LOG = logger<GiteaActionsTreeController>()

/**
 * Fills the tree of one Actions tab and keeps it current: top-level rows per [tab], children loaded on expand,
 * expanded runs reloaded while they change. Also runs the operations of the tab's actions and opens
 * logs. All tree changes happen on the EDT ([cs] runs there); requests go to a background dispatcher.
 */
class GiteaActionsTreeController(
  private val cs: CoroutineScope,
  val project: Project,
  val connection: GiteaProjectConnection,
  val tab: GiteaActionsTab,
  val tree: Tree,
  private val model: GiteaActionsTreeModel,
  /** Tab title once known (a run tab shows the run number, which the run id doesn't tell). */
  private val setTitle: (String) -> Unit = {},
) {
  private val api: GiteaApi get() = connection.api
  private val repository: GiteaRepositoryPath get() = connection.repo.repository.path

  /** Pages of runs to show per parent key (root = ""), raised by "Load more". */
  private val pages = mutableMapOf<String, Int>()
  private val loadedVersions = mutableMapOf<String, Any>()
  private val refreshRequests = MutableStateFlow(0)
  private var firstLoad = true

  /** The user may change things: logged in with write access. */
  val canWrite: Boolean get() = !connection.isAnonymous && connection.repository.permissions?.push == true

  fun start() {
    tree.addTreeWillExpandListener(object : TreeWillExpandListener {
      override fun treeWillExpand(event: TreeExpansionEvent) {
        val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
        if (model.isLoadPending(node)) {
          cs.launch { loadChildren(node) }
        }
      }

      override fun treeWillCollapse(event: TreeExpansionEvent) {}
    })
    cs.launch { GiteaActionsToolWindowViewModel.getInstance(project).refreshRequests.collect { refresh() } }
    when (tab) {
      GiteaActionsTab.CurrentBranch -> cs.launch { followCurrentBranchService() }
      else -> cs.launch { pollRoot() }
    }
  }

  fun refresh() {
    if (tab == GiteaActionsTab.CurrentBranch) {
      GiteaActionsCurrentBranchService.getInstance(project).refresh()
    }
    else {
      refreshRequests.update { it + 1 }
    }
  }

  /** The service polls (it also feeds the status bar); the Current Branch tree follows its state. */
  private suspend fun followCurrentBranchService() {
    GiteaActionsCurrentBranchService.getInstance(project).state.collect { state ->
      if (state == null || state.connection.id != connection.id) {
        return@collect
      }
      loadWorkflowNames()
      showRoot(currentBranchRoot(state))
      refreshExpanded()
    }
  }

  /** The other tabs poll themselves: faster while a visible run is active, and only while the tree is shown. */
  private suspend fun pollRoot() {
    while (true) {
      val seen = refreshRequests.value
      if (firstLoad || tree.isShowing) {
        showRoot(loadRoot())
        refreshExpanded()
      }
      val active = visibleRuns().any { it.status.isActive }
      GiteaRefreshService.scheduler.awaitNextPoll("Gitea Actions $tab", active) { refreshRequests.first { it != seen } }
    }
  }

  private fun currentBranchRoot(state: GiteaActionsCurrentBranchService.State): List<GiteaActionsNode> {
    val header = listOfNotNull(state.pullRequest?.let { GiteaActionsNode.PullRequest(it.number, it.title) })
    val runs = state.runs ?: return header + GiteaActionsNode.Loading
    runs.exceptionOrNull()?.let { return header + error(it, "") }
    val list = runs.getOrThrow()
    if (list.isEmpty()) {
      val text = state.branch?.let { GiteaBundle.message("actions.empty.branch", it) } ?: GiteaBundle.message("actions.empty.detached")
      return header + GiteaActionsNode.Message(text, false, "")
    }
    return header + list.map { GiteaActionsNode.Run(it, workflowName(it), showBranch = false, isCheckedOut = state.isCheckedOut(it)) }
  }

  /** Workflow names by file; loaded once, empty (file names are shown) until then or if loading fails. */
  @Volatile
  private var workflowNames: Map<String, String>? = null

  private suspend fun loadWorkflowNames() {
    if (workflowNames != null) {
      return
    }
    workflowNames = try {
      withContext(Dispatchers.Default) { api.loadWorkflows(repository).associate { it.id to (it.name?.takeIf(String::isNotBlank) ?: it.id) } }
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load the workflows of $repository", e)
      emptyMap()
    }
  }

  /** "CI" for `ci.yml`, or the file name. */
  fun workflowName(run: GiteaRun): String? = run.workflowFile?.let { workflowNames?.get(it) ?: it }

  private fun runNode(run: GiteaRun, showBranch: Boolean) = GiteaActionsNode.Run(run, workflowName(run), showBranch)

  private suspend fun loadRoot(): List<GiteaActionsNode> = try {
    loadWorkflowNames()
    withContext(Dispatchers.Default) {
      when (tab) {
        GiteaActionsTab.CurrentBranch -> emptyList()
        GiteaActionsTab.Workflows -> {
          val workflows = api.loadWorkflows(repository).map(GiteaWorkflow::from)
          // fallback: no workflow files on the default branch, but maybe runs
          if (workflows.isEmpty()) {
            runsWithPaging("", showBranch = true) { page, limit -> api.loadRuns(repository, GiteaActionRunsFilter(), page, limit) }
          }
          else {
            workflows.map { GiteaActionsNode.Workflow(it) }
          }
        }
        is GiteaActionsTab.PullRequest -> {
          val pr = api.getPullRequest(repository, tab.number)
          val header = GiteaActionsNode.PullRequest(pr.number, pr.title)
          val runs = pr.head?.sha?.let { sha -> api.loadRuns(repository, GiteaActionRunsFilter(headSha = sha), 1, GiteaSettings.getInstance().runsPageSize).workflowRuns }.orEmpty()
          listOf(header) + runs.map { runNode(GiteaRun.from(it), showBranch = false) }
            .ifEmpty { listOf(GiteaActionsNode.Message(GiteaBundle.message("actions.empty.pull.request"), false, "")) }
        }
        is GiteaActionsTab.Run -> listOf(runNode(GiteaRun.from(api.getRun(repository, tab.runId)), showBranch = false))
      }
    }
  }
  catch (e: CancellationException) {
    throw e
  }
  catch (e: Exception) {
    LOG.info("Could not load Gitea Actions for $tab", e)
    listOf(error(e, ""))
  }

  private suspend fun runsWithPaging(parentKey: String, showBranch: Boolean,
                                     load: suspend (page: Int, limit: Int) -> GiteaActionRunsDTO)
    : List<GiteaActionsNode> {
    val (loaded, hasMore) = loadRunPages(pages[parentKey] ?: 1, GiteaSettings.getInstance().runsPageSize, load)
    val runs = loaded.map { runNode(GiteaRun.from(it), showBranch) }
    if (runs.isEmpty()) {
      return listOf(GiteaActionsNode.Message(GiteaBundle.message("actions.empty.runs"), false, parentKey))
    }
    return when {
      hasMore -> runs + GiteaActionsNode.LoadMore(parentKey)
      else -> runs
    }
  }

  @RequiresEdt
  private fun showRoot(items: List<GiteaActionsNode>) {
    updateChildren(model.rootNode, items)
    GiteaActionsTreeModel.ensureRootExpanded(tree, model)
    if (tab is GiteaActionsTab.Run) {
      (items.singleOrNull() as? GiteaActionsNode.Run)?.let { setTitle(GiteaBundle.message("actions.tab.run.number", it.run.number)) }
    }
    if (firstLoad) {
      firstLoad = false
      // a single run tab opens it; elsewhere the newest run, so failures are visible right away
      val firstRun = model.rootNode.childNodes().firstOrNull { it.data is GiteaActionsNode.Run }
      if (firstRun != null && tab !is GiteaActionsTab.Workflows) {
        tree.expandPath(TreePath(firstRun.path))
      }
    }
    applyFocus()
  }

  /** Replaces the children of [parent], keeping expanded rows expanded, and fills rows whose children are known. */
  @RequiresEdt
  private fun updateChildren(parent: DefaultMutableTreeNode, items: List<GiteaActionsNode>) {
    val expanded = expandedKeys(parent)
    model.setChildren(parent, items)
    for (child in parent.childNodes()) {
      val data = child.data ?: continue
      knownChildren(data)?.let { updateChildren(child, it); continue }
      if (data is GiteaActionsNode.Run && loadedVersions[data.key] != null && loadedVersions[data.key] != data.version) {
        // changed while collapsed: load again on the next expand
        if (!tree.isExpanded(TreePath(child.path))) {
          model.resetChildren(child)
          loadedVersions.remove(data.key)
        }
      }
    }
    restoreExpanded(parent, expanded)
  }

  /** Children that come with the node's data (steps, artifacts, attempts) – no request needed. */
  private fun knownChildren(data: GiteaActionsNode): List<GiteaActionsNode>? = when (data) {
    // in the order they ran, like the log next to the tree (failed jobs still come first, steps don't – user decision)
    is GiteaActionsNode.Job -> data.job.steps.map { GiteaActionsNode.Step(data.run, data.job, it) }
    is GiteaActionsNode.Artifacts -> data.artifacts.map { GiteaActionsNode.Artifact(data.run, it) }
    is GiteaActionsNode.Attempts -> (data.run.attempt - 1 downTo 1).map { GiteaActionsNode.Attempt(data.run, it) }
    else -> null
  }

  private suspend fun loadChildren(node: DefaultMutableTreeNode) {
    val data = node.data ?: return
    val items = try {
      withContext(Dispatchers.Default) { fetchChildren(data) }
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info("Could not load children of ${data.key}", e)
      listOf(error(e, data.key))
    }
    if (node.parent == null) {
      return // removed meanwhile
    }
    updateChildren(node, items)
    if (data is GiteaActionsNode.Run) {
      loadedVersions[data.key] = data.version
    }
    applyFocus()
  }

  private suspend fun fetchChildren(data: GiteaActionsNode): List<GiteaActionsNode> = when (data) {
    is GiteaActionsNode.Run -> coroutineScope {
      val run = data.run
      val jobs = async { api.loadRunJobs(repository, run.id).map(GiteaJob::from) }
      // artifacts are optional: a failure there shouldn't hide the jobs
      val artifacts = async { runCatching { api.loadRunArtifacts(repository, run.id).map(GiteaArtifact::from) }.getOrDefault(emptyList()) }
      val jobNodes = GiteaJob.sortForAttention(jobs.await()).map { GiteaActionsNode.Job(run, it, latestAttempt = true) }
      val artifactNodes = artifacts.await().takeIf { it.isNotEmpty() }?.let { listOf(GiteaActionsNode.Artifacts(run, it)) }.orEmpty()
      val attempts = when {
        run.attempt > 1 -> listOf(GiteaActionsNode.Attempts(run))
        else -> emptyList()
      }
      (jobNodes + artifactNodes + attempts).ifEmpty { listOf(GiteaActionsNode.Message(GiteaBundle.message("actions.empty.jobs"), false, data.key)) }
    }
    is GiteaActionsNode.Workflow -> runsWithPaging(data.key, showBranch = true) { page, limit ->
      api.loadWorkflowRuns(repository, data.workflow.id, page, limit)
    }
    is GiteaActionsNode.Attempt -> api.loadRunAttemptJobs(repository, data.run.id, data.attempt).map(GiteaJob::from)
      .let(GiteaJob::sortForAttention).map { GiteaActionsNode.Job(data.run, it, latestAttempt = false) }
    else -> knownChildren(data).orEmpty()
  }

  /** Reloads expanded workflows and expanded runs that are still running or changed since their jobs were loaded. */
  @RequiresEdt
  private suspend fun refreshExpanded() {
    val stale = mutableListOf<DefaultMutableTreeNode>()
    fun visit(node: DefaultMutableTreeNode) {
      for (child in node.childNodes()) {
        if (!tree.isExpanded(TreePath(child.path)) || model.isLoadPending(child)) {
          continue
        }
        when (val data = child.data) {
          is GiteaActionsNode.Workflow -> stale += child
          is GiteaActionsNode.Run -> if (data.run.status.isActive || loadedVersions[data.key] != data.version) {
            stale += child
          }
          else -> {}
        }
        visit(child)
      }
    }
    visit(model.rootNode)
    for (node in stale) {
      loadChildren(node)
    }
  }

  private fun visibleRuns(): List<GiteaRun> {
    val runs = mutableListOf<GiteaRun>()
    fun visit(node: DefaultMutableTreeNode) {
      for (child in node.childNodes()) {
        (child.data as? GiteaActionsNode.Run)?.let { runs += it.run }
        if (tree.isExpanded(TreePath(child.path))) {
          visit(child)
        }
      }
    }
    visit(model.rootNode)
    return runs
  }

  /** "Load more" row: show another page of runs in its parent. */
  @RequiresEdt
  fun loadMore(node: DefaultMutableTreeNode) {
    val data = node.data as? GiteaActionsNode.LoadMore ?: return
    pages[data.parentKey] = (pages[data.parentKey] ?: 1) + 1
    val parent = node.parent as? DefaultMutableTreeNode ?: return
    if (parent == model.rootNode) {
      refresh()
    }
    else {
      cs.launch { loadChildren(parent) }
    }
  }

  /** Runs [block] on the EDT for as long as the tab exists (dialogs and their preparatory requests). */
  fun launch(block: suspend CoroutineScope.() -> Unit): Job = cs.launch(block = block)

  /** Selects the row of [job] (or [step]); the selection shows its log next to the tree. */
  @RequiresEdt
  fun openLog(run: GiteaRun, job: GiteaJob, step: GiteaStep? = null) {
    val key = when {
      step != null -> GiteaActionsNode.Step(run, job, step).key
      else -> GiteaActionsNode.Job(run, job, true).key
    }
    findNode(key)?.let { select(it) }
  }

  /**
   * A run to show once it is loaded: its row is expanded and a job selected (which shows the log) – job [jobId], else
   * the job named [jobName], else the first one. Used after a re-run ([minAttempt] = the new attempt), a dispatch and
   * for job links from a PR or notification.
   */
  private data class Focus(val runId: Long, val minAttempt: Long = 0, val jobId: Long? = null, val jobName: String? = null)

  private var focus: Focus? = null

  @RequiresEdt
  fun focusRun(runId: Long, minAttempt: Long = 0, jobId: Long? = null, jobName: String? = null) {
    focus = Focus(runId, minAttempt, jobId, jobName)
    applyFocus()
  }

  /** A job link opened in this run tab. */
  @RequiresEdt
  fun selectJob(jobId: Long?) {
    if (tab is GiteaActionsTab.Run) {
      focusRun(tab.runId, jobId = jobId)
    }
  }

  private fun applyFocus() {
    val focus = focus ?: return
    val runNode = findNode("run:${focus.runId}") ?: return
    val data = runNode.data as? GiteaActionsNode.Run ?: return
    if (data.run.attempt < focus.minAttempt) {
      return
    }
    val path = TreePath(runNode.path)
    // expanding loads the jobs; loadChildren comes back here
    if (!tree.isExpanded(path)) {
      tree.expandPath(path)
    }
    if (model.isLoadPending(runNode) || loadedVersions[data.key] != data.version) {
      return
    }
    val jobs = runNode.childNodes().filter { it.data is GiteaActionsNode.Job }
    fun job(node: DefaultMutableTreeNode) = (node.data as GiteaActionsNode.Job).job
    val target = focus.jobId?.let { id -> jobs.find { job(it).id == id } }
                 ?: focus.jobName?.let { name -> jobs.find { job(it).name == name } }
                 ?: jobs.firstOrNull()
                 ?: return
    this.focus = null
    select(target)
  }

  /**
   * After a dispatch: show run [runId] here if it turns up (in the Workflows tab under its expanded workflow),
   * otherwise in its own run tab.
   */
  @RequiresEdt
  fun focusNewRun(runId: Long, workflowId: String) {
    findNode("workflow:$workflowId")?.let { node -> tree.expandPath(TreePath(node.path)) }
    focusRun(runId)
    cs.launch {
      delay(5.seconds)
      if (focus?.runId == runId && findNode("run:$runId") == null) {
        focus = null
        GiteaActionsNavigator.getInstance(project).showRun(runId, selectJob = true)
      }
    }
  }

  private fun select(node: DefaultMutableTreeNode) {
    val path = TreePath(node.path)
    tree.selectionPath = path
    tree.scrollPathToVisible(path)
  }

  private fun findNode(key: String): DefaultMutableTreeNode? {
    fun visit(node: DefaultMutableTreeNode): DefaultMutableTreeNode? {
      for (child in node.childNodes()) {
        if (child.data?.key == key) {
          return child
        }
        visit(child)?.let { return it }
      }
      return null
    }
    return visit(model.rootNode)
  }

  /**
   * Runs an operation (re-run, cancel, …), calls [onSuccess] with its result (on the EDT, e.g. to focus the new
   * attempt), then refreshes – after a short delay, since Gitea creates attempts and jobs asynchronously. Failures end
   * up in a notification.
   */
  fun perform(failureMessage: @Nls String, operation: suspend GiteaApi.(GiteaRepositoryPath) -> Unit): Job =
    perform(failureMessage, operation) {}

  fun <T> perform(failureMessage: @Nls String, operation: suspend GiteaApi.(GiteaRepositoryPath) -> T, onSuccess: (T) -> Unit): Job = cs.launch {
    try {
      val result = withContext(Dispatchers.Default) { api.operation(repository) }
      onSuccess(result)
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      LOG.info(failureMessage, e)
      NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.ACTIONS_GROUP)
        .createNotification(failureMessage, e.localizedMessage ?: e.toString(), NotificationType.ERROR)
        .setDisplayId(GiteaNotificationIds.ACTIONS_OPERATION_FAILED)
        .notify(project)
    }
    delay(1.seconds)
    refresh()
    if (tab != GiteaActionsTab.CurrentBranch) {
      GiteaActionsCurrentBranchService.getInstance(project).refresh()
    }
  }

  private fun expandedKeys(parent: DefaultMutableTreeNode): Set<String> {
    val keys = mutableSetOf<String>()
    fun visit(node: DefaultMutableTreeNode) {
      for (child in node.childNodes()) {
        if (tree.isExpanded(TreePath(child.path))) {
          child.data?.key?.let(keys::add)
          visit(child)
        }
      }
    }
    visit(parent)
    return keys
  }

  /** Moving a node makes the tree forget its expansion and that of its descendants. */
  private fun restoreExpanded(parent: DefaultMutableTreeNode, keys: Set<String>) {
    if (keys.isEmpty()) {
      return
    }
    for (child in parent.childNodes()) {
      if (child.data?.key !in keys) {
        continue
      }
      if (!tree.isExpanded(TreePath(child.path)) && !model.isLoadPending(child)) {
        tree.expandPath(TreePath(child.path))
      }
      restoreExpanded(child, keys)
    }
  }

  private fun error(e: Throwable, parentKey: String) =
    GiteaActionsNode.Message(GiteaBundle.message("actions.load.error", e.localizedMessage ?: e.toString()), true, parentKey)

  companion object {
    /**
     * Pages 1..[pageCount] of [pageSize] runs, newest first, and whether there are more. A fixed page size, because
     * Gitea caps `limit` at 50 (`MAX_RESPONSE_ITEMS`) – a growing limit would stop there. A run started meanwhile
     * shifts the pages; its duplicate is dropped.
     */
    internal suspend fun loadRunPages(pageCount: Int, pageSize: Int, load: suspend (page: Int, limit: Int) -> GiteaActionRunsDTO)
      : Pair<List<GiteaActionRunDTO>, Boolean> {
      val loaded = mutableListOf<GiteaActionRunDTO>()
      var total = 0L
      var lastPageFull = false
      for (page in 1..pageCount) {
        val result = load(page, pageSize)
        total = result.totalCount
        loaded += result.workflowRuns
        lastPageFull = result.workflowRuns.size >= pageSize
        if (!lastPageFull) {
          break
        }
      }
      val runs = loaded.distinctBy { it.id }
      return runs to (lastPageFull && total > runs.size)
    }
  }
}
