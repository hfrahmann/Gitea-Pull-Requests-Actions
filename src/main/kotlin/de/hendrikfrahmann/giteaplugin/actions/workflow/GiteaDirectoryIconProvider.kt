package de.hendrikfrahmann.giteaplugin.actions.workflow

import com.intellij.ide.IconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import javax.swing.Icon

/** A folder with the Gitea logo for `.gitea` directories, like the bundled GitHub plugin does for `.github`. */
class GiteaDirectoryIconProvider : IconProvider(), DumbAware {
  override fun getIcon(element: PsiElement, flags: Int): Icon? = when {
    element is PsiDirectory && element.name == GITEA_DIR_NAME -> GiteaIcons.GiteaFolder
    else -> null
  }

  private companion object {
    const val GITEA_DIR_NAME = ".gitea"
  }
}
