package de.hendrikfrahmann.giteaplugin.actions.workflow

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import java.util.function.Function
import javax.swing.JComponent

/** Banner on workflow files Gitea doesn't run: `.github/workflows` while `.gitea/workflows` exists. */
class GiteaInactiveWorkflowNotificationProvider : EditorNotificationProvider, DumbAware {
  override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
    val workflow = GiteaWorkflowFiles.find(project, file)?.takeIf { !it.isActive } ?: return null
    val active = workflow.activeDirectory ?: return null
    return Function { editor ->
      EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning)
        .text(GiteaBundle.message("workflow.inactive.banner", workflow.directory, active))
    }
  }
}
