package de.hendrikfrahmann.giteaplugin.ui.action

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.actions.ShowAnnotateOperationsPopup
import com.intellij.openapi.vcs.annotate.AnnotationGutterActionProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.vcsUtil.VcsUtil
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.isGiteaUnauthorized
import de.hendrikfrahmann.giteaplugin.api.request.findPullRequestByMergeCommit
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaProjectDefaultAccountHolder
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotifications
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectMapping
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import de.hendrikfrahmann.giteaplugin.repository.GiteaRepositoryCoordinates
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import git4idea.GitRevisionNumber
import git4idea.GitUtil
import git4idea.annotate.GitFileAnnotation
import git4idea.remote.hosting.action.HostedGitRepositoryReference
import git4idea.remote.hosting.action.HostedGitRepositoryReferenceActionGroup
import git4idea.remote.hosting.action.HostedGitRepositoryReferenceUtil
import git4idea.remote.hosting.findKnownRepositories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URI

private val LOG = logger<GiteaAnnotationGutterActionProvider>()

/** Annotate (blame) gutter → "Open on Gitea": the commit of the clicked line. */
class GiteaAnnotationGutterActionProvider : AnnotationGutterActionProvider {
  override fun createAction(annotation: FileAnnotation): AnAction = GiteaOpenInBrowserFromAnnotationActionGroup(annotation)
}

/** Annotate gutter → "Open Pull Request on Gitea": the PR that merged the commit of the clicked line. */
class GiteaPullRequestAnnotationGutterActionProvider : AnnotationGutterActionProvider {
  override fun createAction(annotation: FileAnnotation): AnAction = GiteaOpenPullRequestFromAnnotationAction(annotation)
}

private fun lineRevision(annotation: FileAnnotation, dataContext: DataContext): GitRevisionNumber? {
  if (annotation !is GitFileAnnotation) {
    return null
  }
  val line = ShowAnnotateOperationsPopup.getAnnotationLineNumber(dataContext).takeIf { it >= 0 } ?: return null
  return annotation.getLineRevisionNumber(line) as? GitRevisionNumber
}

class GiteaOpenInBrowserFromAnnotationActionGroup(private val annotation: FileAnnotation)
  : HostedGitRepositoryReferenceActionGroup(GiteaBundle.messagePointer("action.Gitea.Annotation.Open.Commit.text"),
                                            GiteaBundle.messagePointer("action.Gitea.Annotation.Open.Commit.description"),
                                            { GiteaIcons.Gitea }) {

  override fun findReferences(dataContext: DataContext): List<HostedGitRepositoryReference> {
    val revision = lineRevision(annotation, dataContext) ?: return emptyList()
    val file = annotation.file ?: return emptyList()
    val project = annotation.project
    return HostedGitRepositoryReferenceUtil.findReferences(project, GiteaProjectsManager.getInstance(project), file, revision) {
      repository, revisionHash -> GiteaWebUrls.commit(repository, revisionHash)
    }
  }

  override fun handleReference(reference: HostedGitRepositoryReference) {
    reference.buildWebURI()?.let(BrowserUtil::browse)
  }
}

/**
 * Looks the PR up via `commits/{sha}/pull`. Gitea only matches a PR's merge commit (see [findPullRequestByMergeCommit]),
 * so for commits merged with a merge commit nothing is found; then a notification offers the commit page instead.
 */
class GiteaOpenPullRequestFromAnnotationAction(private val annotation: FileAnnotation)
  : DumbAwareAction(GiteaBundle.messagePointer("action.Gitea.Annotation.Open.Pull.Request.text"),
                    GiteaBundle.messagePointer("action.Gitea.Annotation.Open.Pull.Request.description"),
                    GiteaIcons.Gitea) {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = lineRevision(annotation, e.dataContext) != null && findMappings().isNotEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val revision = lineRevision(annotation, e.dataContext) ?: return
    val mappings = findMappings().takeIf { it.isNotEmpty() } ?: return
    val project = annotation.project
    project.service<GiteaAnnotationScope>().cs.launch {
      withBackgroundProgress(project, GiteaBundle.message("annotation.pull.request.progress")) {
        openPullRequest(project, mappings, revision.asString())
      }
    }
  }

  private fun findMappings(): List<GiteaProjectMapping> {
    val file = annotation.file ?: return emptyList()
    val project = annotation.project
    val repository = GitUtil.getRepositoryManager(project).getRepositoryForFileQuick(VcsUtil.getFilePath(file))
                     ?: return emptyList()
    return GiteaProjectsManager.getInstance(project).findKnownRepositories(repository)
  }
}

/** Opens the PR that merged [sha] from the first of [mappings] that knows it; otherwise offers the commit page. */
private suspend fun openPullRequest(project: Project, mappings: List<GiteaProjectMapping>, sha: String) {
  val defaultAccount = GiteaProjectDefaultAccountHolder.getInstance(project).account
  val accounts = GiteaAccountManager.getInstance().accountsState.value
  var searched = false
  for (mapping in mappings) {
    val account = accountFor(mapping.repository.serverPath, accounts, defaultAccount) ?: continue
    searched = true
    when (val lookup = lookUpPullRequest(project, account, mapping.repository, sha)) {
      is PullRequestLookup.Found -> {
        BrowserUtil.browse(lookup.uri)
        return
      }
      PullRequestLookup.Failed -> return
      PullRequestLookup.NotFound -> Unit
    }
  }
  val text = when {
    searched -> GiteaBundle.message("annotation.pull.request.not.found", sha.take(SHORT_SHA_LENGTH))
    else -> GiteaBundle.message("annotation.pull.request.no.account")
  }
  notify(project, text, GiteaWebUrls.commit(mappings.first().repository.getWebURI(), sha))
}

private const val SHORT_SHA_LENGTH = 10

private sealed interface PullRequestLookup {
  class Found(val uri: URI) : PullRequestLookup
  data object NotFound : PullRequestLookup
  /** The user has been told why. */
  data object Failed : PullRequestLookup
}

private suspend fun lookUpPullRequest(project: Project, account: GiteaAccount, coordinates: GiteaRepositoryCoordinates, sha: String): PullRequestLookup {
  val pullRequest = try {
    GiteaApiManager.getInstance().getClient(account).findPullRequestByMergeCommit(coordinates.path, sha)
  }
  catch (e: CancellationException) {
    throw e
  }
  catch (e: Exception) {
    LOG.info("Pull request lookup for $sha in $coordinates failed", e)
    if (e.isGiteaUnauthorized) {
      GiteaNotifications.notifyAuthFailed(project, account)
    }
    else {
      notify(project, GiteaBundle.message("annotation.pull.request.error", e.localizedMessage), GiteaWebUrls.commit(coordinates.getWebURI(), sha))
    }
    return PullRequestLookup.Failed
  }
  return pullRequest?.let { PullRequestLookup.Found(GiteaWebUrls.pullRequest(coordinates.getWebURI(), it.number)) } ?: PullRequestLookup.NotFound
}

private fun accountFor(server: GiteaServerPath, accounts: Collection<GiteaAccount>, defaultAccount: GiteaAccount?): GiteaAccount? {
  val candidates = accounts.filter { it.server == server }
  return defaultAccount?.takeIf { it in candidates } ?: candidates.firstOrNull()
}

private fun notify(project: Project, text: String, commitUri: URI) {
  NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
    .createNotification(GiteaBundle.message("annotation.pull.request.title"), text, NotificationType.INFORMATION)
    .setDisplayId(GiteaNotificationIds.PULL_REQUEST_NOT_FOUND)
    .addAction(NotificationAction.createSimpleExpiring(GiteaBundle.message("annotation.pull.request.open.commit")) {
      BrowserUtil.browse(commitUri)
    })
    .notify(project)
}

@Service(Service.Level.PROJECT)
internal class GiteaAnnotationScope(val cs: CoroutineScope)
