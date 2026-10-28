package de.hendrikfrahmann.giteaplugin.actions.workflow

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsNavigator
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaMiniYaml
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflow
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflowDispatch
import de.hendrikfrahmann.giteaplugin.actions.ui.GiteaWorkflowDispatchDialog
import de.hendrikfrahmann.giteaplugin.actions.workflow.run.GiteaLocalRunnerUi
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.request.dispatchWorkflow
import de.hendrikfrahmann.giteaplugin.api.request.loadBranches
import de.hendrikfrahmann.giteaplugin.api.request.loadRawFile
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnectionManager
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectMapping
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<GiteaWorkflowRemoteRun>()

/**
 * `Gitea.Workflow.Run.Remote` – "Run Workflow on Gitea…" for the workflow file in the editor, also in the gutter
 * menu of jobs (the API dispatches whole workflows only). Offered for active workflow files with `workflow_dispatch`.
 */
class GiteaRunWorkflowRemotelyAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = target(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val (workflow, text) = target(e) ?: return
    val mappings = GiteaWorkflowRemotes.mappings(project, workflow.repository)
    val run = { mapping: GiteaProjectMapping -> GiteaWorkflowRemoteRun.getInstance(project).run(workflow, text, mapping) }
    if (mappings.size == 1) {
      run(mappings.single())
      return
    }
    JBPopupFactory.getInstance().createPopupChooserBuilder(mappings)
      .setTitle(GiteaBundle.message("workflow.remote.choose.remote"))
      .setRenderer(GiteaLocalRunnerUi.renderer<GiteaProjectMapping> { it?.let { mapping -> "${mapping.gitRemote.name}: ${mapping.repository}" }.orEmpty() })
      .setItemChosenCallback { run(it) }
      .createPopup()
      .showInBestPositionFor(e.dataContext)
  }

  private fun target(e: AnActionEvent): Pair<GiteaWorkflowFiles.WorkflowFile, String>? {
    val project = e.project ?: return null
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    return dispatchTarget(project, file)
  }

  companion object {
    /** [file] as an active workflow file with a Gitea remote and a `workflow_dispatch` trigger, with its current text. */
    fun dispatchTarget(project: Project, file: VirtualFile): Pair<GiteaWorkflowFiles.WorkflowFile, String>? {
      val workflow = GiteaWorkflowFiles.find(project, file)?.takeIf { it.isActive } ?: return null
      if (GiteaWorkflowRemotes.mappings(project, workflow.repository).isEmpty()) {
        return null
      }
      val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: return null
      GiteaWorkflowDispatch.parse(text) ?: return null
      return workflow to text
    }
  }
}

/** Dispatches a workflow file of the project on one of its Gitea remotes. */
@Service(Service.Level.PROJECT)
class GiteaWorkflowRemoteRun(private val project: Project, private val cs: CoroutineScope) {
  fun run(workflow: GiteaWorkflowFiles.WorkflowFile, text: String, mapping: GiteaProjectMapping) {
    cs.launch(Dispatchers.EDT) {
      val title = GiteaBundle.message("workflow.remote.failed", workflow.workflowId, mapping.repository)
      try {
        val api = GiteaWorkflowRemotes.api(project, mapping)
                  ?: error(GiteaBundle.message("workflow.run.error.no.account", mapping.repository.serverPath))
        val dispatch = GiteaWorkflowDispatch.parse(text) ?: return@launch
        val gitWorkflow = GiteaWorkflow(workflow.workflowId, workflowName(text, workflow), workflow.relativePath, isDisabled = false)
        val dialog = askInputs(api, mapping, gitWorkflow, dispatch) ?: return@launch
        val ref = dialog.ref
        val inputs = dialog.inputs
        val repository = mapping.repository.path
        val remoteText = withContext(Dispatchers.Default) { api.loadRawFile(repository, workflow.relativePath, ref) }
        if (!confirmDifferences(workflow, ref, remoteText)) {
          return@launch
        }
        val runId = withContext(Dispatchers.Default) { api.dispatchWorkflow(repository, workflow.workflowId, ref, inputs) }
        showRun(mapping, runId, gitWorkflow.name, ref)
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        LOG.info(title, e)
        notifyFailed(title, e)
      }
    }
  }

  /** The workflow's `name:`, or its file name. */
  private fun workflowName(text: String, workflow: GiteaWorkflowFiles.WorkflowFile): String =
    (runCatching { GiteaMiniYaml.parse(text) }.getOrNull() as? Map<*, *>)?.get("name") as? String ?: workflow.workflowId

  /** The ref and inputs dialog, preselecting the checked-out branch; `null` when cancelled. */
  private suspend fun askInputs(api: GiteaApi, mapping: GiteaProjectMapping, workflow: GiteaWorkflow,
                                dispatch: GiteaWorkflowDispatch): GiteaWorkflowDispatchDialog? {
    val branches = withContext(Dispatchers.Default) { api.loadBranches(mapping.repository.path).map { it.name } }
    val initialRef = currentBranch(mapping)?.takeIf { it in branches }
    val dialog = GiteaWorkflowDispatchDialog(project, workflow, branches, initialRef ?: branches.firstOrNull(), dispatch)
    return dialog.takeIf { it.showAndGet() }
  }

  private fun notifyFailed(title: String, error: Exception) {
    NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
      .createNotification(title, error.localizedMessage ?: error.toString(), NotificationType.ERROR)
      .setDisplayId(GiteaNotificationIds.ACTIONS_OPERATION_FAILED)
      .notify(project)
  }

  /**
   * Gitea runs the file as it is on [ref]: when the local file (with unsaved changes) differs from it, or [ref] doesn't
   * have it, ask before running; "Show Differences" opens a diff and doesn't run.
   */
  private fun confirmDifferences(workflow: GiteaWorkflowFiles.WorkflowFile, ref: String, remoteText: String?): Boolean {
    val document = FileDocumentManager.getInstance().getDocument(workflow.file) ?: return true
    if (remoteText != null && normalize(remoteText) == normalize(document.text)) {
      return true
    }
    val title = GiteaBundle.message("workflow.remote.modified.title")
    if (remoteText == null) {
      return Messages.showOkCancelDialog(project, GiteaBundle.message("workflow.remote.missing", workflow.relativePath, ref), title,
                                         GiteaBundle.message("workflow.remote.run.anyway"), Messages.getCancelButton(),
                                         Messages.getWarningIcon()) == Messages.OK
    }
    val choice = Messages.showDialog(project, GiteaBundle.message("workflow.remote.modified", workflow.relativePath, ref), title,
                                     arrayOf(GiteaBundle.message("workflow.remote.run.anyway"), GiteaBundle.message("workflow.remote.show.diff"),
                                             Messages.getCancelButton()), 0, Messages.getWarningIcon())
    if (choice == 1) {
      val contents = DiffContentFactory.getInstance()
      val request = SimpleDiffRequest(GiteaBundle.message("workflow.remote.diff.title", workflow.relativePath),
                                      contents.create(project, remoteText, workflow.file.fileType), contents.create(project, document),
                                      GiteaBundle.message("workflow.remote.diff.remote", ref), GiteaBundle.message("workflow.remote.diff.local"))
      DiffManager.getInstance().showDiff(project, request)
    }
    return choice == 0
  }

  private fun normalize(text: String): String = text.replace("\r\n", "\n").trimEnd()

  /** The branch the current branch tracks on [mapping]'s remote, else the local branch name. */
  private fun currentBranch(mapping: GiteaProjectMapping): String? {
    val repository = mapping.gitRepository
    val branch = repository.currentBranch ?: return null
    val tracked = repository.getBranchTrackInfo(branch.name)?.remoteBranch
    return tracked?.takeIf { it.remote == mapping.gitRemote }?.nameForRemoteOperations ?: branch.name
  }

  /** The connected repository shows the run in the Actions tool window, others get a notification with a link. */
  private fun showRun(mapping: GiteaProjectMapping, runId: Long?, name: String, ref: String) {
    val connection = GiteaProjectConnectionManager.getInstance(project).connectionState.value
    val navigator = GiteaActionsNavigator.getInstance(project)
    if (runId != null && connection?.repo == mapping && navigator.isAvailable(connection)) {
      navigator.showRun(runId, selectJob = true)
      return
    }
    val notification = NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
      .createNotification(GiteaBundle.message("workflow.remote.started", name, ref, mapping.repository), NotificationType.INFORMATION)
      .setDisplayId(GiteaNotificationIds.ACTIONS_WORKFLOW_DISPATCHED)
    if (runId != null) {
      val url = GiteaWebUrls.actionsRun(mapping.repository.getWebURI(), runId)
      notification.addAction(NotificationAction.createSimpleExpiring(GiteaBundle.message("workflow.remote.open.browser")) { BrowserUtil.browse(url) })
    }
    notification.notify(project)
  }

  companion object {
    fun getInstance(project: Project): GiteaWorkflowRemoteRun = project.service()
  }
}
