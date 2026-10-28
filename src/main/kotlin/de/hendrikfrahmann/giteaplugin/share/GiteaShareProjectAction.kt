package de.hendrikfrahmann.giteaplugin.share

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** `Gitea.Share` – Git | Gitea | Share Project on Gitea, and VCS | Share Project. */
class GiteaShareProjectAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && !project.isDefault && TrustedProjects.isProjectTrusted(project)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    if (project.isDisposed) {
      return
    }
    GiteaShareProjectUtil.shareProject(project, e.getData(CommonDataKeys.VIRTUAL_FILE), project.service<GiteaShareScope>().cs)
  }
}

/** Project-scoped coroutine scope for share operations (cancelled when the project closes). */
@Service(Service.Level.PROJECT)
class GiteaShareScope(@Suppress("unused") private val project: Project, val cs: CoroutineScope)
