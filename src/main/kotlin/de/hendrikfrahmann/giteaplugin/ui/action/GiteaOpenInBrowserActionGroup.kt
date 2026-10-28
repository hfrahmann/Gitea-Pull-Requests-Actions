package de.hendrikfrahmann.giteaplugin.ui.action

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectsManager
import git4idea.remote.hosting.HostedGitRepositoriesManager
import git4idea.remote.hosting.action.GlobalHostedGitRepositoryReferenceActionGroup
import git4idea.remote.hosting.action.HostedGitRepositoryReference
import java.awt.datatransfer.StringSelection
import java.net.URI

/**
 * Links for the editor, editor tab, project view, file history and VCS log. The platform base finds the Gitea
 * remotes of the file's repository and shows one entry per remote when there are several.
 * Files are linked by the commit of their last change in `HEAD` (permalink), like GitHub and GitLab do.
 */
abstract class GiteaRepositoryReferenceActionGroup : GlobalHostedGitRepositoryReferenceActionGroup() {
  override fun repositoriesManager(project: Project): HostedGitRepositoriesManager<*> = GiteaProjectsManager.getInstance(project)

  override fun getUri(repository: URI, revisionHash: String): URI = GiteaWebUrls.commit(repository, revisionHash)

  override fun getUri(repository: URI, revisionHash: String, relativePath: String, lineRange: IntRange?): URI =
    GiteaWebUrls.fileAtCommit(repository, revisionHash, relativePath, lineRange)
}

/** `Git.Hosting.Open.In.Browser.Group` → Gitea */
class GiteaOpenInBrowserActionGroup : GiteaRepositoryReferenceActionGroup() {
  override fun handleReference(reference: HostedGitRepositoryReference) {
    reference.buildWebURI()?.let(BrowserUtil::browse)
  }
}

/** `Git.Hosting.Copy.Link.Group` (Copy Path/Reference…) → Gitea */
class GiteaCopyLinkActionGroup : GiteaRepositoryReferenceActionGroup() {
  override fun handleReference(reference: HostedGitRepositoryReference) {
    val uri = reference.buildWebURI() ?: return
    CopyPasteManager.getInstance().setContents(StringSelection(uri.toString()))
  }
}
