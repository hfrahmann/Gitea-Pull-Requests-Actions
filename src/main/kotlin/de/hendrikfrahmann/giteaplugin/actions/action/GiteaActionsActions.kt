package de.hendrikfrahmann.giteaplugin.actions.action

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsCurrentBranchService
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsDownloads
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsNavigator
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaRun
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflow
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflowDispatch
import de.hendrikfrahmann.giteaplugin.actions.secrets.GiteaSecretsComponentFactory
import de.hendrikfrahmann.giteaplugin.actions.secrets.GiteaSecretsDialog
import de.hendrikfrahmann.giteaplugin.actions.ui.GiteaWorkflowDispatchDialog
import de.hendrikfrahmann.giteaplugin.actions.ui.toolwindow.GiteaActionsTab
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.GiteaActionsDataKeys
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.GiteaActionsNode
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.GiteaActionsTreeController
import de.hendrikfrahmann.giteaplugin.actions.ui.tree.run
import de.hendrikfrahmann.giteaplugin.actions.workflow.GiteaWorkflowFiles
import de.hendrikfrahmann.giteaplugin.api.request.approveRun
import de.hendrikfrahmann.giteaplugin.api.request.awaitRunAttempt
import de.hendrikfrahmann.giteaplugin.api.request.cancelRun
import de.hendrikfrahmann.giteaplugin.api.request.deleteRun
import de.hendrikfrahmann.giteaplugin.api.request.dispatchWorkflow
import de.hendrikfrahmann.giteaplugin.api.request.forceCancelRun
import de.hendrikfrahmann.giteaplugin.api.request.loadBranches
import de.hendrikfrahmann.giteaplugin.api.request.loadRawFile
import de.hendrikfrahmann.giteaplugin.api.request.loadWorkflows
import de.hendrikfrahmann.giteaplugin.api.request.rerunFailedJobs
import de.hendrikfrahmann.giteaplugin.api.request.rerunJob
import de.hendrikfrahmann.giteaplugin.api.request.rerunRun
import de.hendrikfrahmann.giteaplugin.pullrequest.action.GiteaPullRequestsActionKeys
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaRefreshAction
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import java.net.URI

private val LOG = logger<GiteaActionsTreeAction>()

/** Base of the Actions tree actions: enabled by the selected row and the tab's controller. */
abstract class GiteaActionsTreeAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  final override fun update(e: AnActionEvent) {
    val controller = e.getData(GiteaActionsDataKeys.CONTROLLER)
    val node = e.getData(GiteaActionsDataKeys.SELECTED_NODE)
    e.presentation.isEnabledAndVisible = controller != null && node != null && isEnabled(controller, node)
  }

  final override fun actionPerformed(e: AnActionEvent) {
    val controller = e.getData(GiteaActionsDataKeys.CONTROLLER) ?: return
    val node = e.getData(GiteaActionsDataKeys.SELECTED_NODE) ?: return
    perform(e, controller, node)
  }

  abstract fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean
  abstract fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode)
}

/** `Gitea.Actions.Show` – Git | Gitea | Show Gitea Actions. */
class GiteaShowActionsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.let { GiteaToolWindowProjectViewModel.getInstance(it).isAvailable.value } == true
  }

  override fun actionPerformed(e: AnActionEvent) {
    GiteaActionsNavigator.getInstance(e.project ?: return).showCurrentBranch()
  }
}

/** `Gitea.Actions.Refresh` */
class GiteaActionsRefreshAction : GiteaRefreshAction() {
  override fun refresher(context: DataContext): (() -> Unit)? = context.getData(GiteaActionsDataKeys.CONTROLLER)?.let { controller -> controller::refresh }
}

/** `Gitea.Actions.Open.Log` – the log of a job, at the step for a step row. */
class GiteaActionsOpenLogAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    node is GiteaActionsNode.Job || node is GiteaActionsNode.Step

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    when (node) {
      is GiteaActionsNode.Job -> controller.openLog(node.run, node.job)
      is GiteaActionsNode.Step -> controller.openLog(node.run, node.job, node.step)
      else -> {}
    }
  }
}

/** `Gitea.Actions.Open.PullRequest` – the PR of a `pull_request` run in the PR tool window. */
class GiteaActionsOpenPullRequestAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean = number(node) != null

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val number = number(node) ?: return
    GiteaToolWindowProjectViewModel.getInstance(controller.project).activateAndAwaitProject { openPullRequest(number, focus = true) }
  }

  private fun number(node: GiteaActionsNode): Long? =
    (node as? GiteaActionsNode.PullRequest)?.number ?: node.run?.pullRequestNumber
}

/** `Gitea.Actions.Run.Rerun` – a new attempt of all jobs. */
class GiteaActionsRerunAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    controller.canWrite && node is GiteaActionsNode.Run && node.run.status.isFinished

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val run = node.run ?: return
    controller.perform(GiteaBundle.message("actions.operation.rerun.failed", run.number), { repo ->
      rerunRun(repo, run.id)
      awaitRunAttempt(repo, run.id, run.attempt + 1)
    }) { controller.focusRun(run.id, minAttempt = run.attempt + 1) }
  }
}

/** `Gitea.Actions.Run.Rerun.Failed` – only the failed jobs (verified 201). */
class GiteaActionsRerunFailedAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    controller.canWrite && node is GiteaActionsNode.Run && (node.run.status == GiteaActionStatus.FAILURE || node.run.status == GiteaActionStatus.CANCELLED)

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val run = node.run ?: return
    controller.perform(GiteaBundle.message("actions.operation.rerun.failed", run.number), { repo ->
      rerunFailedJobs(repo, run.id)
      awaitRunAttempt(repo, run.id, run.attempt + 1)
    }) { controller.focusRun(run.id, minAttempt = run.attempt + 1) }
  }
}

/** `Gitea.Actions.Job.Rerun` – one job; only jobs of the latest attempt have ids the API accepts. */
class GiteaActionsRerunJobAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    controller.canWrite && node is GiteaActionsNode.Job && node.latestAttempt && node.job.status.isFinished && !node.run.status.isActive

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val job = (node as? GiteaActionsNode.Job) ?: return
    controller.perform(GiteaBundle.message("actions.operation.rerun.job.failed", job.job.name), { repo ->
      rerunJob(repo, job.run.id, job.job.id)
      awaitRunAttempt(repo, job.run.id, job.run.attempt + 1)
    }) { controller.focusRun(job.run.id, minAttempt = job.run.attempt + 1, jobName = job.job.name) }
  }
}

/** `Gitea.Actions.Run.Cancel` – on a run, or on a job/step of it (also in the log toolbar). */
class GiteaActionsCancelAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    controller.canWrite && (node.run?.status?.isActive == true || (node as? GiteaActionsNode.Job)?.job?.status?.isActive == true ||
                            (node as? GiteaActionsNode.Step)?.job?.status?.isActive == true)

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val run = node.run ?: return
    controller.perform(GiteaBundle.message("actions.operation.cancel.failed", run.number)) { cancelRun(it, run.id) }
  }
}

/** `Gitea.Actions.Run.ForceCancel` – for runs whose runner is gone; asks first. */
class GiteaActionsForceCancelAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    controller.canWrite && node is GiteaActionsNode.Run && node.run.status.isActive

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val run = node.run ?: return
    val answer = Messages.showYesNoDialog(controller.project, GiteaBundle.message("actions.force.cancel.confirm", run.number, run.title),
                                          GiteaBundle.message("actions.force.cancel.title"), Messages.getWarningIcon())
    if (answer != Messages.YES) {
      return
    }
    controller.perform(GiteaBundle.message("actions.operation.cancel.failed", run.number)) { forceCancelRun(it, run.id) }
  }
}

/** `Gitea.Actions.Run.Approve` – lets a waiting run of a fork PR start. */
class GiteaActionsApproveAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean {
    if (!controller.canWrite || node !is GiteaActionsNode.Run) {
      return false
    }
    val status = node.run.status
    return status == GiteaActionStatus.WAITING || status == GiteaActionStatus.QUEUED && node.run.isPullRequestRun
  }

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val run = node.run ?: return
    controller.perform(GiteaBundle.message("actions.operation.approve.failed", run.number)) { approveRun(it, run.id) }
  }
}

/** `Gitea.Actions.Run.Delete` – asks first. */
class GiteaActionsDeleteRunAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    controller.canWrite && node is GiteaActionsNode.Run && node.run.status.isFinished

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val run = node.run ?: return
    val answer = Messages.showYesNoDialog(controller.project, GiteaBundle.message("actions.delete.confirm", run.number, run.title),
                                          GiteaBundle.message("actions.delete.title"), Messages.getWarningIcon())
    if (answer != Messages.YES) {
      return
    }
    controller.perform(GiteaBundle.message("actions.operation.delete.failed", run.number)) { deleteRun(it, run.id) }
  }
}

/**
 * `Gitea.Actions.Workflow.Dispatch` – "Run Workflow…" for a workflow (or the workflow of a run): reads the
 * `workflow_dispatch` inputs from the workflow file on the default branch and asks for ref and inputs.
 */
class GiteaActionsDispatchAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    controller.canWrite && (node is GiteaActionsNode.Workflow || node.run?.workflowFile != null)

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val project = controller.project
    controller.launch {
      try {
        val source = withContext(Dispatchers.Default) { loadSource(controller.connection, node) }
        askAndDispatch(controller, source)
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        LOG.info("Could not prepare the workflow dispatch", e)
        Messages.showErrorDialog(project, e.localizedMessage ?: e.toString(), GiteaBundle.message("actions.dispatch.error.title"))
      }
    }
  }

  /** The workflow, the branches to offer and the workflow file's text on the default branch (`null` if it is missing). */
  private class DispatchSource(val workflow: GiteaWorkflow, val branches: List<String>, val defaultBranch: String, val yaml: String?)

  private suspend fun loadSource(connection: GiteaProjectConnection, node: GiteaActionsNode): DispatchSource {
    val repository = connection.repo.repository.path
    val defaultBranch = connection.repository.defaultBranch ?: "main"
    val workflowFile = node.run?.workflowFile
    val workflow = (node as? GiteaActionsNode.Workflow)?.workflow
                   ?: connection.api.loadWorkflows(repository).map(GiteaWorkflow::from).find { it.id == workflowFile }
                   ?: error(GiteaBundle.message("actions.dispatch.workflow.missing", workflowFile.orEmpty()))
    val branches = connection.api.loadBranches(repository).map { it.name }
    val yaml = workflow.path?.let { connection.api.loadRawFile(repository, it, defaultBranch) }
    return DispatchSource(workflow, branches, defaultBranch, yaml)
  }

  /** Shows the inputs dialog (preselecting the checked-out branch) and dispatches; the new run is selected once it shows up. */
  private fun askAndDispatch(controller: GiteaActionsTreeController, source: DispatchSource) {
    val project = controller.project
    val workflow = source.workflow
    val dispatch = source.yaml?.let(GiteaWorkflowDispatch::parse)
    if (dispatch == null) {
      Messages.showInfoMessage(project, GiteaBundle.message("actions.dispatch.not.dispatchable", workflow.name, source.defaultBranch),
                               GiteaBundle.message("actions.dispatch.title", workflow.name))
      return
    }
    val currentBranch = controller.connection.repo.gitRepository.currentBranch?.name?.takeIf { it in source.branches }
    val dialog = GiteaWorkflowDispatchDialog(project, workflow, source.branches, currentBranch ?: source.defaultBranch, dispatch)
    if (!dialog.showAndGet()) {
      return
    }
    val ref = dialog.ref
    val inputs = dialog.inputs
    controller.perform(GiteaBundle.message("actions.operation.dispatch.failed", workflow.name), { repo ->
      dispatchWorkflow(repo, workflow.id, ref, inputs)
    }) { runId -> runId?.let { controller.focusNewRun(it, workflow.id) } }
  }
}

/**
 * `Gitea.Actions.Open.Workflow.File` – the workflow file of a workflow or run row. A workflow, or a run of the current
 * branch (or its PR), opens the local file in a normal editor; a run of another branch opens the file at the run's
 * commit read-only (also when the file is missing locally).
 */
class GiteaActionsOpenWorkflowFileAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    (node as? GiteaActionsNode.Workflow)?.workflow?.path != null || node.run?.workflowFile != null

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val project = controller.project
    val run = node.run
    val workflowPath = (node as? GiteaActionsNode.Workflow)?.workflow?.path
    val fileName = workflowPath?.substringAfterLast('/') ?: run?.workflowFile ?: return
    controller.launch {
      try {
        val local = when {
          run == null || isCurrentBranch(controller, run) -> findLocalFile(controller, workflowPath, fileName)
          else -> null
        }
        val file = local ?: loadAtRunCommit(controller.connection, run, workflowPath, fileName)
        FileEditorManager.getInstance(project).openFile(file, true)
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        LOG.info("Could not open the workflow file", e)
        Messages.showErrorDialog(project, e.localizedMessage ?: e.toString(), GiteaBundle.message("actions.workflow.file.error.title"))
      }
    }
  }

  private suspend fun findLocalFile(controller: GiteaActionsTreeController, workflowPath: String?, fileName: String): VirtualFile? {
    val root = controller.connection.repo.gitRepository.root
    return withContext(Dispatchers.IO) {
      when {
        workflowPath != null -> root.findFileByRelativePath(workflowPath)?.takeIf { !it.isDirectory }
        else -> GiteaWorkflowFiles.findLocal(root, fileName)
      }
    }
  }

  /** A read-only copy of the file at the run's commit (the default branch for a workflow row). */
  private suspend fun loadAtRunCommit(connection: GiteaProjectConnection, run: GiteaRun?, workflowPath: String?, fileName: String): VirtualFile {
    val ref = run?.headSha ?: run?.ref ?: connection.repository.defaultBranch ?: "main"
    val label = run?.branch ?: run?.pullRequestNumber?.let { "#$it" } ?: run?.headSha?.take(8) ?: ref
    // runs only know the file; at the run's commit it is in the first workflows directory that exists (Gitea's rule)
    val candidates = workflowPath?.let(::listOf) ?: GiteaWorkflowFiles.WORKFLOW_DIRS.map { "$it/$fileName" }
    val repository = connection.repo.repository.path
    val text = withContext(Dispatchers.Default) {
      candidates.firstNotNullOfOrNull { connection.api.loadRawFile(repository, it, ref) }
    } ?: error(GiteaBundle.message("actions.workflow.file.missing", fileName, label))
    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
    return LightVirtualFile("$fileName@$label", fileType, text).apply { isWritable = false }
  }

  /** The run ran on the checked-out branch: same branch as the Current Branch tab, or the PR of that branch. */
  private fun isCurrentBranch(controller: GiteaActionsTreeController, run: GiteaRun): Boolean {
    val state = GiteaActionsCurrentBranchService.getInstance(controller.project).state.value
    if (state != null && state.connection.id == controller.connection.id) {
      val sameBranch = run.branch != null && run.branch == state.branch
      val branchPullRequest = run.pullRequestNumber != null && run.pullRequestNumber == state.pullRequest?.number
      return sameBranch || branchPullRequest
    }
    return run.branch != null && run.branch == controller.connection.repo.gitRepository.currentBranch?.name
  }
}

/** `Gitea.Actions.Artifact.Download` */
class GiteaActionsDownloadArtifactAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    node is GiteaActionsNode.Artifact && !node.artifact.expired

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val artifact = (node as? GiteaActionsNode.Artifact)?.artifact ?: return
    GiteaActionsDownloads.getInstance(controller.project).downloadArtifact(controller.connection, artifact)
  }
}

/** `Gitea.Actions.Run.Download.Logs` – all job logs of a run as one zip. */
class GiteaActionsDownloadLogsAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean =
    node.run != null && node !is GiteaActionsNode.Artifact && node !is GiteaActionsNode.Artifacts

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    val run = node.run ?: return
    GiteaActionsDownloads.getInstance(controller.project).downloadRunLogs(controller.connection, run.id)
  }
}

/** Web link of a row: run, job, workflow or PR. */
private fun webLink(controller: GiteaActionsTreeController, node: GiteaActionsNode): URI? {
  val repository = controller.connection.repo.repository.getWebURI()
  return when (node) {
    is GiteaActionsNode.Job -> GiteaWebUrls.actionsJob(repository, node.run.id, node.job.id)
    is GiteaActionsNode.Step -> GiteaWebUrls.actionsJob(repository, node.run.id, node.job.id)
    is GiteaActionsNode.Workflow -> GiteaWebUrls.actionsWorkflow(repository, node.workflow.id)
    is GiteaActionsNode.PullRequest -> GiteaWebUrls.pullRequest(repository, node.number)
    else -> node.run?.let { GiteaWebUrls.actionsRun(repository, it.id) }
  }
}

/** `Gitea.Actions.Open.In.Browser` */
class GiteaActionsOpenInBrowserAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean = webLink(controller, node) != null

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    webLink(controller, node)?.let { BrowserUtil.browse(it) }
  }
}

/** `Gitea.Actions.Copy.Link` */
class GiteaActionsCopyLinkAction : GiteaActionsTreeAction() {
  override fun isEnabled(controller: GiteaActionsTreeController, node: GiteaActionsNode): Boolean = webLink(controller, node) != null

  override fun perform(e: AnActionEvent, controller: GiteaActionsTreeController, node: GiteaActionsNode) {
    webLink(controller, node)?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.toString())) }
  }
}

/** `Gitea.PullRequest.ShowActions` – "Show Runs in Gitea Actions" for the selected or open PR. */
class GiteaPullRequestShowActionsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && number(e) != null && GiteaActionsNavigator.getInstance(project).isAvailable()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val number = number(e) ?: return
    GiteaActionsNavigator.getInstance(e.project ?: return).showRunsForPullRequest(number)
  }

  private fun number(e: AnActionEvent): Long? =
    e.getData(GiteaPullRequestsActionKeys.DETAILS_VM)?.dataProvider?.number ?: e.getData(GiteaPullRequestsActionKeys.SELECTED)?.number
}

/** `Gitea.Actions.Secrets` – "Secrets & Variables…" in the Current Branch and Workflows toolbars; any logged-in user. */
class GiteaActionsSecretsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val controller = e.getData(GiteaActionsDataKeys.CONTROLLER)
    e.presentation.isEnabledAndVisible = controller != null &&
                                         (controller.tab == GiteaActionsTab.CurrentBranch || controller.tab == GiteaActionsTab.Workflows) &&
                                         GiteaSecretsComponentFactory.isAvailable(controller.connection)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val controller = e.getData(GiteaActionsDataKeys.CONTROLLER) ?: return
    GiteaSecretsDialog(controller.project, controller.connection).show()
  }
}
