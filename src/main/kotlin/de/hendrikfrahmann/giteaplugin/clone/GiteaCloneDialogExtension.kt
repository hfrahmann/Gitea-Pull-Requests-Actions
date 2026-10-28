package de.hendrikfrahmann.giteaplugin.clone

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtension
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionComponent
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionStatusLine
import com.intellij.util.ui.cloneDialog.VcsCloneWithExtensionAction
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.swing.Icon

/** "Gitea" in `File | New | Project from Version Control`. */
class GiteaCloneDialogExtension : VcsCloneDialogExtension {
  override fun getName(): String = "Gitea"

  override fun getIcon(): Icon = GiteaIcons.Gitea

  override fun getAdditionalStatusLines(): List<VcsCloneDialogExtensionStatusLine> {
    val accounts = GiteaAccountManager.getInstance().accountsState.value
    if (accounts.isEmpty()) {
      return listOf(VcsCloneDialogExtensionStatusLine.greyText(GiteaBundle.message("clone.status.no.accounts")))
    }
    return accounts.map { VcsCloneDialogExtensionStatusLine.greyText("${it.name}@${it.server.host}") }
  }

  override fun createMainComponent(project: Project, modalityState: ModalityState): VcsCloneDialogExtensionComponent {
    val cs = CoroutineScope(SupervisorJob() + Dispatchers.EDT + modalityState.asContextElement())
    val component = GiteaCloneComponent(project, cs, GiteaCloneViewModel(cs, GiteaAccountManager.getInstance()))
    Disposer.register(component) { cs.cancel() }
    return component
  }
}

/** `Gitea.Clone`: "Get from VCS" with the Gitea page selected. */
class GiteaCloneAction : VcsCloneWithExtensionAction() {
  override fun getExtension(): Class<out VcsCloneDialogExtension> = GiteaCloneDialogExtension::class.java
}
