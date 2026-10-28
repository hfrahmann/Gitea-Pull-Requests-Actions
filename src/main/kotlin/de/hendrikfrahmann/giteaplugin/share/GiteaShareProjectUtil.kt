package de.hendrikfrahmann.giteaplugin.share

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.vcsUtil.VcsFileUtil
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaRepositoryDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaCreateRepositoryRequest
import de.hendrikfrahmann.giteaplugin.api.request.createRepository
import de.hendrikfrahmann.giteaplugin.api.request.httpCloneUrl
import de.hendrikfrahmann.giteaplugin.authentication.GiteaConnectionTester
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginResult
import de.hendrikfrahmann.giteaplugin.authentication.GiteaLoginUtil
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaProjectDefaultAccountHolder
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import de.hendrikfrahmann.giteaplugin.notification.GiteaNotificationIds
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import git4idea.GitUtil
import git4idea.actions.GitInit
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import git4idea.util.GitFileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls

private val LOG = logger<GiteaShareProjectUtil>()

/**
 * "Share Project on Gitea": create the repository via API, `git init` if needed, add the remote, make the first
 * commit (files chosen by the user) if there is none, push the current branch.
 *
 * Re-implements the flow of git4idea's `GitShareProjectService`, which is internal API.
 */
object GiteaShareProjectUtil {

  @RequiresEdt
  fun shareProject(project: Project, file: VirtualFile?, cs: CoroutineScope) {
    FileDocumentManager.getInstance().saveAllDocuments()
    val repositoryManager = GitRepositoryManager.getInstance(project)
    val gitRepository = (file ?: project.guessProjectDir())?.let { repositoryManager.getRepositoryForFileQuick(it) }
    val root = gitRepository?.root ?: project.guessProjectDir() ?: return

    if (!confirmIfAlreadyOnGitea(project, gitRepository)) {
      return
    }

    val accountManager = GiteaAccountManager.getInstance()
    if (accountManager.accountsState.value.isEmpty()) {
      val result = GiteaLoginUtil.logInViaOAuth(project, null) { server, name -> accountManager.isAccountUnique(server, name) }
      if (result !is GiteaLoginResult.Success) {
        return
      }
      // Continue once the credentials are stored – the share dialog loads data with them
      cs.launch {
        accountManager.updateAccount(result.account, result.credentials)
        withContext(Dispatchers.EDT) { shareWith(project, root, gitRepository, listOf(result.account), result.account, cs) }
      }
      return
    }
    shareWith(project, root, gitRepository, accountManager.accountsState.value.toList(),
              GiteaProjectDefaultAccountHolder.getInstance(project).account, cs)
  }

  /** `true` if the repository has no Gitea remote yet, or the user wants to share it once more. */
  private fun confirmIfAlreadyOnGitea(project: Project, gitRepository: GitRepository?): Boolean {
    val existing = GiteaProjectsManager.getInstance(project).knownRepositoriesState.value.filter { it.gitRepository == gitRepository }
    if (existing.isEmpty()) {
      return true
    }
    val answer = Messages.showYesNoDialog(
      project,
      GiteaBundle.message("share.existing.message", existing.joinToString("\n") { "${it.gitRemote.name}: ${it.remote.url}" }),
      GiteaBundle.message("share.dialog.title"),
      GiteaBundle.message("share.existing.anyway"), Messages.getCancelButton(), Messages.getWarningIcon(),
    )
    return answer == Messages.YES
  }

  private fun shareWith(
    project: Project,
    root: VirtualFile,
    gitRepository: GitRepository?,
    accounts: List<GiteaAccount>,
    defaultAccount: GiteaAccount?,
    cs: CoroutineScope,
  ) {
    val existingRemotes = gitRepository?.remotes?.mapTo(mutableSetOf()) { it.name }.orEmpty()
    val dialog = GiteaShareDialog(project, accounts, defaultAccount, project.name, existingRemotes)
    if (!dialog.showAndGet()) {
      return
    }
    val settings = dialog.result

    cs.launch(Dispatchers.Default) {
      withBackgroundProgress(project, GiteaBundle.message("share.progress", settings.name), cancellable = true) {
        try {
          perform(project, root, gitRepository, settings)
        }
        catch (e: CancellationException) {
          throw e
        }
        catch (e: Exception) {
          LOG.warn("Sharing ${settings.name} on Gitea failed", e)
          notify(project, NotificationType.ERROR, GiteaBundle.message("share.failed"), GiteaConnectionTester.describe(e))
        }
      }
    }
  }

  private suspend fun perform(project: Project, root: VirtualFile, existingRepository: GitRepository?, settings: GiteaShareSettings) {
    val created = createRemoteRepository(settings)
    val webUrl = settings.account.server.webUri(created.owner?.login ?: settings.owner.login, created.name).toString()
    val remoteUrl = when {
      GiteaSettings.getInstance().cloneUsingSsh && !created.sshUrl.isNullOrBlank() -> created.sshUrl
      else -> settings.account.server.httpCloneUrl(created)
    }

    val repository = withContext(Dispatchers.IO) { existingRepository ?: initRepository(project, root) }
                     ?: throw IllegalStateException(GiteaBundle.message("share.error.no.git.repository"))

    withContext(Dispatchers.IO) {
      Git.getInstance().addRemote(repository, settings.remoteName, remoteUrl).throwOnError()
      repository.update()
    }

    if (repository.isFresh && !commitInitialFiles(project, root, repository)) {
      notify(project, NotificationType.INFORMATION, GiteaBundle.message("share.empty.created"), webUrl, webUrl)
      return
    }

    val branch = repository.currentBranch ?: throw IllegalStateException(GiteaBundle.message("share.error.no.branch"))
    withContext(Dispatchers.IO) {
      Git.getInstance().push(repository, settings.remoteName, remoteUrl, branch.name, true).throwOnError()
      repository.update()
    }
    notify(project, NotificationType.INFORMATION, GiteaBundle.message("share.success", "${settings.owner.login}/${settings.name}"), webUrl, webUrl)
  }

  private suspend fun createRemoteRepository(settings: GiteaShareSettings): GiteaRepositoryDTO =
    GiteaApiManager.getInstance().getClient(settings.account).createRepository(
      GiteaCreateRepositoryRequest(settings.name, settings.description.ifEmpty { null }, settings.isPrivate),
      organization = settings.owner.login.takeIf { settings.owner.isOrganization },
    )

  @RequiresBackgroundThread
  private fun initRepository(project: Project, root: VirtualFile): GitRepository? {
    ThreadingAssertions.assertBackgroundThread()
    Git.getInstance().init(project, root).throwOnError()
    GitInit.refreshAndConfigureVcsMappings(project, root, root.path)
    GitUtil.generateGitignoreFileIfNeeded(project, root)
    return GitRepositoryManager.getInstance(project).getRepositoryForRoot(root)
  }

  /** Asks which files go into the first commit and commits them. `false` if the user cancelled. */
  private suspend fun commitInitialFiles(project: Project, root: VirtualFile, repository: GitRepository): Boolean {
    val candidates = withContext(Dispatchers.IO) { collectCommitCandidates(project, repository) }
    val selection = withContext(Dispatchers.EDT) { askFilesToCommit(project, root, candidates) } ?: return false
    withContext(Dispatchers.IO) { commit(project, root, repository, candidates, selection) }
    return true
  }

  /** Files that could go into the first commit: changed tracked files and untracked ones that aren't ignored. */
  private class CommitCandidates(val tracked: List<VirtualFile>, val untracked: List<VirtualFile>)

  private class CommitSelection(val files: List<VirtualFile>, val message: String)

  private fun collectCommitCandidates(project: Project, repository: GitRepository): CommitCandidates {
    val changeListManager = ChangeListManager.getInstance(project)
    val repositoryManager = GitRepositoryManager.getInstance(project)
    val tracked = changeListManager.affectedFiles.filter { it.isInLocalFileSystem && repositoryManager.getRepositoryForFileQuick(it) == repository }
    val untracked = repository.untrackedFilesHolder.retrieveUntrackedFilePaths().mapNotNull(FilePath::getVirtualFile)
      .filter { !changeListManager.isIgnoredFile(it) }
    return CommitCandidates(tracked - untracked.toSet(), untracked)
  }

  /** The files dialog, with the tracked files preselected; `null` if cancelled or nothing was chosen. */
  private fun askFilesToCommit(project: Project, root: VirtualFile, candidates: CommitCandidates): CommitSelection? {
    val dialog = GiteaShareFilesDialog(project, root, candidates.tracked + candidates.untracked)
    if (candidates.tracked.isNotEmpty()) {
      dialog.selectedFiles = candidates.tracked
    }
    if (!dialog.showAndGet() || dialog.selectedFiles.isEmpty()) {
      return null
    }
    return CommitSelection(dialog.selectedFiles.toList(), dialog.commitMessage)
  }

  private fun commit(project: Project, root: VirtualFile, repository: GitRepository, candidates: CommitCandidates, selection: CommitSelection) {
    val chosen = selection.files.toSet()
    GitFileUtils.addFiles(project, root, candidates.untracked.intersect(chosen))
    GitFileUtils.deleteFilesFromCache(project, root, candidates.tracked - chosen)
    val handler = GitLineHandler(project, root, GitCommand.COMMIT)
    handler.addParameters("-m", selection.message.ifEmpty { GiteaBundle.message("share.commit.message.default") })
    handler.endOptions()
    Git.getInstance().runCommand(handler).throwOnError()
    VcsFileUtil.markFilesDirty(project, candidates.tracked + selection.files)
    repository.update()
  }

  private fun notify(project: Project, type: NotificationType, title: @Nls String, content: @Nls String, url: String? = null) {
    val notification = NotificationGroupManager.getInstance().getNotificationGroup(GiteaNotificationIds.GROUP)
      .createNotification(title, content, type)
      .setDisplayId(when (type) {
        NotificationType.ERROR -> GiteaNotificationIds.SHARE_FAILED
        else -> GiteaNotificationIds.SHARE_SUCCESS
      })
    if (url != null) {
      notification.addAction(NotificationAction.createSimple(GiteaBundle.message("share.open.in.browser")) { BrowserUtil.browse(url) })
    }
    notification.notify(project)
  }
}
