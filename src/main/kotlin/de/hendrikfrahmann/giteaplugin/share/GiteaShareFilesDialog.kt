package de.hendrikfrahmann.giteaplugin.share

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import javax.swing.JComponent

/**
 * Files and message of the first commit when sharing a project without commits.
 * (Not a `SelectFilesDialog` subclass: overriding its panel methods is internal API.)
 */
class GiteaShareFilesDialog(project: Project, private val root: VirtualFile, private val files: List<VirtualFile>) : DialogWrapper(project) {

  private val list = CheckBoxList<VirtualFile>().apply {
    files.sortedBy { it.path }.forEach { addItem(it, VfsUtilCore.getRelativePath(it, root) ?: it.path, true) }
  }

  private val message = JBTextArea(GiteaBundle.message("share.commit.message.default"), 3, 40).apply {
    lineWrap = true
    wrapStyleWord = true
    border = JBUI.Borders.empty(4)
  }

  var selectedFiles: Collection<VirtualFile>
    get() = files.filter { list.isItemSelected(it) }
    set(value) {
      val selected = value.toSet()
      files.forEach { list.setItemSelected(it, it in selected) }
      list.repaint()
    }

  val commitMessage: String get() = message.text.trim()

  init {
    title = GiteaBundle.message("share.files.title")
    setOKButtonText(GiteaBundle.message("share.files.ok"))
    init()
  }

  override fun createCenterPanel(): JComponent =
    Splitter(true, 0.75f).apply {
      setHonorComponentsMinimumSize(true)
      firstComponent = JBScrollPane(list).apply { preferredSize = JBUI.size(500, 300) }
      secondComponent = JBScrollPane(message)
    }

  override fun getDimensionServiceKey(): String = "Gitea.ShareProject.FilesDialog"
}
