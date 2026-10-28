package de.hendrikfrahmann.giteaplugin.pullrequest.file

import com.intellij.collaboration.file.ComplexPathVirtualFileWithoutContent
import com.intellij.collaboration.file.codereview.CodeReviewDiffVirtualFile
import com.intellij.diff.impl.DiffEditorViewer
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.vcs.editor.ComplexPathVirtualFileSystem
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff.GiteaPullRequestDiffService
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.toolwindow.GiteaToolWindowProjectViewModel
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import javax.swing.Icon

/**
 * `giteapr://` – virtual files for PR diffs and timelines in editor tabs (like GitLab's `gitlabmr://`). A file belongs to one
 * connection ([Path.sessionId] = connection id) and becomes invalid when the tool window switches repository/account.
 */
class GiteaVirtualFileSystem : ComplexPathVirtualFileSystem<GiteaVirtualFileSystem.Path>(PathSerializer) {
  override fun getProtocol(): String = PROTOCOL

  override fun findOrCreateFile(project: Project, path: Path): VirtualFile? {
    if (project.locationHash != path.projectHash) {
      return null
    }
    return when {
      path.timeline -> GiteaPullRequestTimelineFile(path.sessionId, project, path.repository, path.number)
      else -> GiteaPullRequestDiffFile(path.sessionId, project, path.repository, path.number)
    }
  }

  fun getPath(connectionId: String, project: Project, repository: String, number: Long, timeline: Boolean = false): String =
    getPath(Path(connectionId, project.locationHash, repository, number, timeline))

  data class Path(
    override val sessionId: String = "",
    override val projectHash: String = "",
    /** `owner/name` */
    val repository: String = "",
    val number: Long = 0,
    /** `true`: the conversation (timeline editor), `false`: the review diff. */
    val timeline: Boolean = false,
  ) : ComplexPath

  private object PathSerializer : ComplexPathSerializer<Path> {
    override fun serialize(path: Path): String = GiteaJson.mapper.writeValueAsString(path)
    override fun deserialize(rawPath: String): Path = GiteaJson.mapper.readValue(rawPath, Path::class.java)
  }

  companion object {
    const val PROTOCOL: String = "giteapr"

    fun getInstance(): GiteaVirtualFileSystem = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as GiteaVirtualFileSystem
  }
}

/** The review diff of PR [number]: all changed files with inline threads, opened with the platform diff viewer. */
class GiteaPullRequestDiffFile(
  val connectionId: String,
  private val project: Project,
  private val repository: String,
  val number: Long,
) : CodeReviewDiffVirtualFile("$number.diff") {
  override fun getFileSystem(): ComplexPathVirtualFileSystem<*> = GiteaVirtualFileSystem.getInstance()
  override fun getPath(): String = GiteaVirtualFileSystem.getInstance().getPath(connectionId, project, repository, number)
  /** Shown as the tab tooltip: the PR title if known (the tooltip API changed in 262), else the path. */
  override fun getPresentablePath(): String = pullRequestTitle(project, connectionId, number) ?: "$repository/pulls/$number.diff"
  override fun getPresentableName(): String = GiteaBundle.message("pull.request.diff.file.name", number.toString())

  override fun isValid(): Boolean = isConnectionOpen(project, connectionId)

  override fun createViewer(project: Project): DiffEditorViewer =
    project.service<GiteaPullRequestDiffService>().createProcessor(connectionId, number)

  override fun equals(other: Any?): Boolean =
    other is GiteaPullRequestDiffFile && other.connectionId == connectionId && other.repository == repository && other.number == number

  override fun hashCode(): Int = (connectionId.hashCode() * 31 + repository.hashCode()) * 31 + number.hashCode()

  class TitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): @NlsContexts.TabTitle String? = when (file) {
      is GiteaPullRequestDiffFile -> file.presentableName
      is GiteaPullRequestTimelineFile -> file.presentableName
      else -> null
    }
  }
}

/** The conversation of PR [number], shown by [GiteaFileEditorProvider] (like GitLab's timeline file). */
class GiteaPullRequestTimelineFile(
  val connectionId: String,
  private val project: Project,
  private val repository: String,
  val number: Long,
) : ComplexPathVirtualFileWithoutContent(connectionId) {
  override fun getName(): String = "#$number"
  override fun getFileSystem(): ComplexPathVirtualFileSystem<*> = GiteaVirtualFileSystem.getInstance()
  override fun getPath(): String = GiteaVirtualFileSystem.getInstance().getPath(connectionId, project, repository, number, timeline = true)
  /** Shown as the tab tooltip: the PR title if known (the tooltip API changed in 262), else the path. */
  override fun getPresentablePath(): String = pullRequestTitle(project, connectionId, number) ?: "$repository/pulls/$number"
  override fun getPresentableName(): String = GiteaBundle.message("pull.request.timeline.file.name", number.toString())
  override fun getFileType(): FileType = GiteaTimelineFileType

  override fun isValid(): Boolean = isConnectionOpen(project, connectionId)

  override fun equals(other: Any?): Boolean =
    other is GiteaPullRequestTimelineFile && other.connectionId == connectionId && other.repository == repository && other.number == number

  override fun hashCode(): Int = (connectionId.hashCode() * 31 + repository.hashCode()) * 31 + number.hashCode()
}

/** A file is valid while the tool window is still connected with the connection it was opened for. */
private fun isConnectionOpen(project: Project, connectionId: String): Boolean =
  !project.isDisposed && project.serviceIfCreated<GiteaToolWindowProjectViewModel>()?.connectedProjectVm?.value?.connection?.id == connectionId

/** "Title (#n)" of an open or listed PR of the connection, `null` if not loaded. */
private fun pullRequestTitle(project: Project, connectionId: String, number: Long): String? =
  project.serviceIfCreated<GiteaToolWindowProjectViewModel>()?.connectedProjectVm?.value
    ?.takeIf { it.connection.id == connectionId }?.pullRequestTitle(number)?.let { "$it (#$number)" }

/** Not registered: only identifies timeline files (icon, no text editor). */
private object GiteaTimelineFileType : FileType {
  override fun getName(): String = "GiteaTimeline"
  override fun getDescription(): String = GiteaBundle.message("pull.request.timeline.file.type")
  override fun getDefaultExtension(): String = ""
  override fun getIcon(): Icon = GiteaIcons.Gitea
  override fun isBinary(): Boolean = true
  override fun isReadOnly(): Boolean = true
}
