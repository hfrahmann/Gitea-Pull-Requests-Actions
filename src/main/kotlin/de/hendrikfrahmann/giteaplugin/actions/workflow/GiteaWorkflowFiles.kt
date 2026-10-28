package de.hendrikfrahmann.giteaplugin.actions.workflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/**
 * Which files Gitea runs as workflows: of `[actions] WORKFLOW_DIRS` (default `.gitea/workflows`,
 * `.github/workflows`) only the **first directory that exists** counts – once `.gitea/workflows` exists,
 * `.github/workflows` is ignored. Workflows are `*.yml`/`*.yaml` files in it, also in subdirectories. The server's
 * `WORKFLOW_DIRS` can't be read through the API, so the default is assumed.
 */
object GiteaWorkflowFiles {
  val WORKFLOW_DIRS: List<String> = listOf(".gitea/workflows", ".github/workflows")

  /** A workflow file of [repository]; [isActive]: in the directory Gitea uses. */
  data class WorkflowFile(
    val repository: GitRepository,
    val file: VirtualFile,
    /** Path from the repository root, e.g. `.gitea/workflows/ci.yml`. */
    val relativePath: String,
    /** The workflows directory the file is in. */
    val directory: String,
    /** The file's directory is the one Gitea uses. */
    val isActive: Boolean,
    /** The directory Gitea uses (where the file is not when [isActive] is false). */
    val activeDirectory: String?,
  ) {
    /** The workflow id in the API: the path below the workflows directory, e.g. `ci.yml`. */
    val workflowId: String get() = relativePath.removePrefix("$directory/")
  }

  fun isWorkflowFileName(name: String): Boolean = name.endsWith(".yml") || name.endsWith(".yaml")

  /** The directory of [WORKFLOW_DIRS] Gitea uses for the working tree at [root], `null` if there is none. */
  fun activeDirectory(root: VirtualFile): String? = WORKFLOW_DIRS.firstOrNull { root.findFileByRelativePath(it)?.isDirectory == true }

  /** [file] as a workflow file of a git repository of [project], `null` if it isn't in one of [WORKFLOW_DIRS]. */
  fun find(project: Project, file: VirtualFile): WorkflowFile? {
    if (file.isDirectory || !file.isInLocalFileSystem || !isWorkflowFileName(file.name)) {
      return null
    }
    val repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return null
    val relativePath = VfsUtilCore.getRelativePath(file, repository.root) ?: return null
    val directory = WORKFLOW_DIRS.firstOrNull { relativePath.startsWith("$it/") } ?: return null
    val active = activeDirectory(repository.root)
    return WorkflowFile(repository, file, relativePath, directory, directory == active, active)
  }

  /** The local file of workflow [workflowId] (`ci.yml`) in the directory Gitea uses for [root]. */
  fun findLocal(root: VirtualFile, workflowId: String): VirtualFile? {
    val directory = activeDirectory(root) ?: return null
    return root.findFileByRelativePath("$directory/$workflowId")?.takeIf { !it.isDirectory }
  }
}
