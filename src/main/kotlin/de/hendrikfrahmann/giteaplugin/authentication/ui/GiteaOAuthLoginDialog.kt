package de.hendrikfrahmann.giteaplugin.authentication.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import java.awt.Component
import javax.swing.JComponent

/** Asks for the server (and optionally an OAuth client id) before the browser-based OAuth login. */
internal class GiteaOAuthLoginDialog(
  project: Project?,
  parent: Component?,
  initialServer: String,
  private val serverEditable: Boolean,
) : DialogWrapper(project, parent, false, IdeModalityType.IDE) {

  private var serverText: String = initialServer
  private var clientIdText: String = ""

  val server: GiteaServerPath get() = GiteaServerPath.fromUserInput(serverText)

  /** The client id to use: the one entered, else the configured one for the server, else Gitea's built-in `tea` app. */
  val clientId: String get() = clientIdText.trim().ifEmpty { GiteaSettings.getInstance().oauthClientIdFor(server) }

  init {
    title = GiteaBundle.message("login.oauth.dialog.title")
    setOKButtonText(GiteaBundle.message("login.oauth.dialog.ok"))
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    row(GiteaBundle.message("login.server.label")) {
      textField()
        .bindText(::serverText)
        .columns(COLUMNS_LARGE)
        .align(AlignX.FILL)
        .enabled(serverEditable)
        .validationOnApply { field ->
          val valid = runCatching { GiteaServerPath.fromUserInput(field.text) }.isSuccess
          ValidationInfo(GiteaBundle.message("login.error.server.url.invalid"), field).takeIf { !valid }
        }
        .focused()
    }
    row { comment(GiteaBundle.message("login.oauth.dialog.comment")) }
    collapsibleGroup(GiteaBundle.message("login.oauth.dialog.advanced")) {
      row(GiteaBundle.message("login.oauth.client.id.label")) {
        textField()
          .bindText(::clientIdText)
          .columns(COLUMNS_LARGE)
          .comment(GiteaBundle.message("login.oauth.client.id.comment"))
      }
    }
  }
}
