package de.hendrikfrahmann.giteaplugin.log

import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJobState
import com.intellij.collaboration.ui.codereview.list.search.ShowDirection
import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.data.VcsCommitExternalStatus
import com.intellij.vcs.log.data.util.VcsCommitsDataLoader
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusPresentation
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusProvider
import com.intellij.vcs.log.ui.table.column.util.VcsLogExternalStatusColumnService
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsNavigator
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaCiCheck
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel
import de.hendrikfrahmann.giteaplugin.refresh.GiteaRefreshService
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnectionManager
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import de.hendrikfrahmann.giteaplugin.ui.GiteaCiChecksPopup
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * The "Gitea Checks" column of the Git log, like the GitHub plugin's "GitHub Commit Checks": one icon per commit with the
 * combined result of its commit statuses (Gitea Actions and external CI), the checks in the tooltip, and on click the
 * checks popup of the PR details – a Gitea Actions job opens in the Actions tool window with its log.
 *
 * Works with the project's connection (the repository the PR and Actions tool windows show), so only commits of that
 * repository's root get statuses. The platform loads just the visible rows and nothing while the column is hidden
 * (column menu of the log), so that is the switch for its requests.
 */
class GiteaCommitChecksProvider : VcsCommitExternalStatusProvider.WithColumn<GiteaCommitChecksStatus>() {
  override val id: String = "Gitea.CommitChecks"

  override val columnName: String get() = GiteaBundle.message("log.commit.checks.column")

  override val isColumnEnabledByDefault: Boolean = true

  override fun isColumnAvailable(project: Project, roots: Collection<VirtualFile>): Boolean =
    GiteaProjectsManager.getInstance(project).knownRepositoriesState.value.any { it.gitRepository.root in roots }

  override fun getExternalStatusColumnService(): VcsLogExternalStatusColumnService<GiteaCommitChecksStatus> =
    service<GiteaCommitChecksColumnService>()

  override fun getStubStatus(): GiteaCommitChecksStatus = GiteaCommitChecksStatus.NotLoaded

  override fun getPresentation(project: Project, status: GiteaCommitChecksStatus): VcsCommitExternalStatusPresentation? =
    (status as? GiteaCommitChecksStatus.Loaded)?.let { GiteaCommitChecksPresentation(project, it) }
}

sealed interface GiteaCommitChecksStatus : VcsCommitExternalStatus {
  data object NotLoaded : GiteaCommitChecksStatus

  /** A commit with at least one check; commits without any keep [NotLoaded] and show nothing. */
  class Loaded(val checks: List<GiteaCiCheck>) : GiteaCommitChecksStatus {
    private val failed = checks.count { it.job.status == CodeReviewCIJobState.FAILED }
    private val pending = checks.count { it.job.status == CodeReviewCIJobState.PENDING }

    // asked for on every repaint of the row
    val icon: Icon = when {
      pending != 0 && failed != 0 -> CIBuildStatusIcons.failedInProgress
      // not the animated icon of the PR details: a table cell only repaints when its row does
      pending != 0 -> CIBuildStatusIcons.inProgress
      failed != 0 -> CIBuildStatusIcons.failed
      checks.all { it.job.status == CodeReviewCIJobState.SKIPPED } -> CIBuildStatusIcons.skipped
      else -> CIBuildStatusIcons.success
    }

    val tooltip: @Nls String by lazy {
      val summary = when {
        pending != 0 && failed != 0 -> CollaborationToolsBundle.message("review.details.status.ci.progress.and.failed")
        pending != 0 -> CollaborationToolsBundle.message("review.details.status.ci.progress")
        failed != 0 -> CollaborationToolsBundle.message("review.details.status.ci.failed")
        else -> CollaborationToolsBundle.message("review.details.status.ci.passed")
      }
      HtmlBuilder().apply {
        append(HtmlChunk.text(summary).bold())
        for (check in checks) {
          append(HtmlChunk.br())
          append(HtmlChunk.text(check.job.name))
          check.description?.let { append(HtmlChunk.text(" – $it")) }
        }
      }.wrapWithHtmlBody().toString()
    }
  }
}

/** One instance for all log tables; [getDataLoader] gives each table its own loader in the project's cache scope. */
@Service(Service.Level.APP)
class GiteaCommitChecksColumnService(override val scope: CoroutineScope) : VcsLogExternalStatusColumnService<GiteaCommitChecksStatus>() {
  override fun getDataLoader(project: Project): VcsCommitsDataLoader<GiteaCommitChecksStatus> = GiteaCommitChecksLoader(project)
}

/**
 * Loader of one log table (or commit details panel). The platform calls [loadData] with the visible rows plus a margin,
 * shortly after scrolling stops and when the log refreshes; each call replaces the previous one. While a visible commit
 * has running checks they are asked again at the fast refresh interval, so the icon turns green or red without scrolling.
 */
private class GiteaCommitChecksLoader(private val project: Project) : VcsCommitsDataLoader<GiteaCommitChecksStatus> {
  private val cache = GiteaCommitChecksCache.getInstance(project)
  private val cs = cache.cs.childScope()
  private var current: Job? = null

  init {
    // the PR tool window's model connects to the last used repository at startup; make sure it exists for the log too
    GiteaToolWindowProjectViewModel.getInstance(project)
  }

  override fun loadData(commits: List<CommitId>, onChange: (Map<CommitId, GiteaCommitChecksStatus>) -> Unit) {
    current?.cancel()
    // empty: the column was hidden
    if (commits.isEmpty()) {
      return
    }
    current = cs.launch { load(commits, onChange) }
  }

  private suspend fun load(commits: List<CommitId>, onChange: (Map<CommitId, GiteaCommitChecksStatus>) -> Unit) {
    val connection = GiteaProjectConnectionManager.getInstance(project).connectionState.filterNotNull().first()
    val root = connection.repo.gitRepository.root
    val shown = commits.filter { it.root == root }.associateBy { cache.key(connection, it.hash.asString()) }
    if (shown.isEmpty()) {
      return
    }
    deliver(shown.mapNotNull { (key, commit) -> cache.peek(key)?.let { commit to it } }, onChange)
    while (true) {
      val now = System.currentTimeMillis()
      coroutineScope {
        for ((key, commit) in shown) {
          if (cache.isDue(key, now)) {
            launch { reload(connection, commit, onChange) }
          }
        }
      }
      if (shown.keys.none { (cache.peek(it) as? GiteaCommitChecks.Reported)?.isPending == true }) {
        return
      }
      GiteaRefreshService.scheduler.awaitNextPoll("Gitea checks in the Git log", active = true)
    }
  }

  private suspend fun reload(connection: GiteaProjectConnection, commit: CommitId,
                             onChange: (Map<CommitId, GiteaCommitChecksStatus>) -> Unit) {
    val checks = cache.load(connection, commit.hash.asString()) ?: return
    deliver(listOf(commit to checks), onChange)
  }

  private suspend fun deliver(statuses: List<Pair<CommitId, GiteaCommitChecks>>, onChange: (Map<CommitId, GiteaCommitChecksStatus>) -> Unit) {
    if (statuses.isEmpty()) {
      return
    }
    val map = statuses.associate { (commit, checks) -> commit to checks.toStatus() }
    withContext(Dispatchers.EDT) { onChange(map) }
  }

  private fun GiteaCommitChecks.toStatus(): GiteaCommitChecksStatus = when (this) {
    is GiteaCommitChecks.Reported -> when {
      checks.isEmpty() -> GiteaCommitChecksStatus.NotLoaded
      else -> GiteaCommitChecksStatus.Loaded(checks)
    }
    GiteaCommitChecks.NotOnServer -> GiteaCommitChecksStatus.NotLoaded
  }

  override fun dispose() {
    cs.cancel()
  }
}

/** Icon and tooltip of a commit; a click shows its checks, or opens the only one directly. */
private class GiteaCommitChecksPresentation(
  private val project: Project,
  private val status: GiteaCommitChecksStatus.Loaded,
) : VcsCommitExternalStatusPresentation.Clickable {
  override val icon: Icon get() = status.icon
  override val text: String get() = status.tooltip

  override fun onClick(e: InputEvent?): Boolean {
    val point = (e as? MouseEvent)?.let(::RelativePoint) ?: return false
    val navigator = GiteaActionsNavigator.getInstance(project)
    val single = status.checks.singleOrNull()
    if (single != null) {
      GiteaCiChecksPopup.open(point, navigator, single.job)
      return true
    }
    GiteaCommitChecksCache.getInstance(project).cs.launch(Dispatchers.EDT) {
      GiteaCiChecksPopup.chooseAndOpen(point, navigator, status.checks, ShowDirection.BELOW,
                                       GiteaBundle.message("log.commit.checks.popup.title"))
    }
    return true
  }
}
