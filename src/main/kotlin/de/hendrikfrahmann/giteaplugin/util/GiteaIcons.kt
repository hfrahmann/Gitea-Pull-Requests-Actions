package de.hendrikfrahmann.giteaplugin.util

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.icons.IconWithToolTip
import org.jetbrains.annotations.Nls
import javax.swing.Icon

object GiteaIcons {
  /** Gitea logo, 16x16 – accounts, actions, notifications. */
  @JvmField
  val Gitea: Icon = load("/icons/gitea.svg")

  /**
   * Outline teacup for the tool window stripe, matching the new UI's monochrome icons (16x16, `@20x20` and `_dark`
   * variants next to it).
   */
  @JvmField
  val GiteaToolWindow: Icon = load("/icons/giteaToolWindow.svg")

  /** Folder with the Gitea logo in the corner, for `.gitea` directories (like the platform's `FolderGithub`). */
  @JvmField
  val GiteaFolder: Icon = load("/icons/folderGitea.svg")

  /**
   * [icon] with a tooltip, for icons that are the only sign of a state: renderers based on
   * `SimpleColoredComponent` show it when hovering the icon and add it to what screen readers announce for the row.
   */
  fun withToolTip(icon: Icon, toolTip: @Nls String): Icon = object : IconWithToolTip, Icon by icon {
    override fun getToolTip(composite: Boolean): String = toolTip
  }

  private fun load(path: String): Icon = IconLoader.getIcon(path, GiteaIcons::class.java.classLoader)
}
