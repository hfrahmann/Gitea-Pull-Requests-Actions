package de.hendrikfrahmann.giteaplugin.actions.secrets

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/** "Secrets & Variables" of the connected repository, opened from the Actions tool window's toolbar. */
class GiteaSecretsDialog(private val project: Project, private val connection: GiteaProjectConnection) : DialogWrapper(project) {
  // requests and nested dialogs run while this dialog is open, hence any modality
  private val cs = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())

  init {
    title = GiteaBundle.message("actions.secrets.dialog.title", connection.repo.repository.path.toString())
    init()
  }

  override fun createCenterPanel(): JComponent =
    GiteaSecretsComponentFactory.create(cs, project, connection) { contentPanel }.apply {
      preferredSize = Dimension(JBUI.scale(640), JBUI.scale(380))
    }

  override fun createActions(): Array<Action> = arrayOf(okAction.apply { putValue(Action.NAME, GiteaBundle.message("actions.secrets.dialog.close")) })

  override fun getDimensionServiceKey(): String = "Gitea.Actions.Secrets"

  override fun dispose() {
    cs.cancel()
    super.dispose()
  }
}
