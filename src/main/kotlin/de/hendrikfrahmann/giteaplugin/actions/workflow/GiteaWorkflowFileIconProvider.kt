package de.hendrikfrahmann.giteaplugin.actions.workflow

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import javax.swing.Icon

/**
 * The Gitea logo for workflow files (`.gitea/workflows/…`) and composite actions (`action.yml` below `.gitea/`) – in editor
 * tabs, the project view and wherever the file's icon is shown. Only the path is checked: icons are asked for often.
 */
class GiteaWorkflowFileIconProvider : FileIconProvider, DumbAware {
  override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? = when {
    isGiteaYaml(file.path) -> GiteaIcons.Gitea
    else -> null
  }

  companion object {
    private const val GITEA_DIR = "/.gitea/"
    private val ACTION_FILES = setOf("action.yml", "action.yaml")

    /** A `*.yml`/`*.yaml` in a `.gitea/workflows` directory, or an `action.yml`/`action.yaml` anywhere below `.gitea/`. */
    internal fun isGiteaYaml(path: String): Boolean {
      val name = path.substringAfterLast('/')
      if (!GiteaWorkflowFiles.isWorkflowFileName(name)) {
        return false
      }
      val giteaDir = path.lastIndexOf(GITEA_DIR)
      return giteaDir >= 0 && (name in ACTION_FILES || path.startsWith("workflows/", giteaDir + GITEA_DIR.length))
    }
  }
}
