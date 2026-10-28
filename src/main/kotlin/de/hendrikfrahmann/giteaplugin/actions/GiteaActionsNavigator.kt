package de.hendrikfrahmann.giteaplugin.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.actions.ui.toolwindow.GiteaActionsTab
import de.hendrikfrahmann.giteaplugin.actions.ui.toolwindow.GiteaActionsToolWindowViewModel
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.ui.action.GiteaWebUrls
import java.net.URI

/**
 * Entry points into the Actions tool window for other features (PR details, PR actions, notifications), so they
 * don't depend on its UI. Without Actions in the repository, links open in the browser.
 */
@Service(Service.Level.PROJECT)
class GiteaActionsNavigator(private val project: Project) {
  private val toolWindowVm get() = GiteaActionsToolWindowViewModel.getInstance(project)

  /** Actions are enabled for the connected repository. */
  fun isAvailable(connection: GiteaProjectConnection? = toolWindowVm.connection.value): Boolean = connection?.repository?.hasActions == true

  fun showCurrentBranch() = toolWindowVm.showTab(GiteaActionsTab.CurrentBranch)

  /** A tab with run [runId]; with [jobId] (or [selectJob]: its first job) also shows that job's log. */
  fun showRun(runId: Long, jobId: Long? = null, selectJob: Boolean = jobId != null) {
    val connection = toolWindowVm.connection.value ?: return
    if (!isAvailable(connection)) {
      BrowserUtil.browse(when {
        jobId != null -> GiteaWebUrls.actionsJob(connection.repo.repository.getWebURI(), runId, jobId)
        else -> GiteaWebUrls.actionsRun(connection.repo.repository.getWebURI(), runId)
      })
      return
    }
    toolWindowVm.showTab(GiteaActionsTab.Run(runId))
    if (selectJob) {
      toolWindowVm.requestJob(runId, jobId)
    }
  }

  /** Runs for the head of PR [number] in a "PR #N" tab. */
  fun showRunsForPullRequest(number: Long) = toolWindowVm.showTab(GiteaActionsTab.PullRequest(number))

  /**
   * A Gitea Actions job link (`<repo>/actions/runs/{run}/jobs/{job}`, the `target_url` of its commit status) opens the
   * job in the tool window; `false` for other links (external CI, other repositories).
   */
  fun showJobLink(url: String): Boolean {
    val connection = toolWindowVm.connection.value ?: return false
    val (runId, jobId) = parseJobLink(connection.repo.repository.getWebURI(), url) ?: return false
    if (!isAvailable(connection)) {
      return false
    }
    showRun(runId, jobId)
    return true
  }

  companion object {
    private val JOB_LINK = Regex("""/actions/runs/(\d+)/jobs/(\d+)/?$""")

    fun getInstance(project: Project): GiteaActionsNavigator = project.service()

    /** Run and job id from a job link below [repository]'s web URI; query and fragment are ignored. */
    internal fun parseJobLink(repository: URI, url: String): Pair<Long, Long>? {
      val path = runCatching { URI.create(url).path }.getOrNull() ?: return null
      val repoPath = repository.path.trimEnd('/')
      if (!path.startsWith("$repoPath/actions/")) {
        return null
      }
      val match = JOB_LINK.find(path) ?: return null
      return match.groupValues[1].toLong() to match.groupValues[2].toLong()
    }
  }
}
