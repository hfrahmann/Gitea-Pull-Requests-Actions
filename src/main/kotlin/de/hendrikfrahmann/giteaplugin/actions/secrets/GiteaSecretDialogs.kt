package de.hendrikfrahmann.giteaplugin.actions.secrets

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import java.util.Arrays
import javax.swing.JComponent

private val LOG = logger<GiteaSubmitDialog>()

/** Name rules of Gitea (verified on 28.1); checked before sending, the server's message is shown if it still refuses. */
object GiteaSecretNames {
  private val VALID = Regex("[A-Za-z_][A-Za-z0-9_]*")
  private val RESERVED = listOf("GITEA_", "GITHUB_")

  fun validate(name: String): @Nls String? = when {
    name.isEmpty() -> GiteaBundle.message("actions.secrets.name.empty")
    !VALID.matches(name) -> GiteaBundle.message("actions.secrets.name.invalid")
    RESERVED.any { name.startsWith(it, ignoreCase = true) } -> GiteaBundle.message("actions.secrets.name.reserved")
    else -> null
  }
}

/**
 * A dialog that sends its input on OK and stays open with the server's message when the request fails.
 * Validation runs while typing (DialogWrapper turns that off for UI DSL panels).
 */
abstract class GiteaSubmitDialog(project: Project, private val cs: CoroutineScope) : DialogWrapper(project) {
  private var submitting = false

  /** Sends the input; throwing keeps the dialog open with the error. */
  protected abstract suspend fun submit()

  public override fun continuousValidation(): Boolean = true

  override fun doOKAction() {
    if (submitting || doValidateAll().isNotEmpty()) {
      return
    }
    submitting = true
    isOKActionEnabled = false
    setErrorText(null)
    cs.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
      try {
        withContext(Dispatchers.Default) { submit() }
        close(OK_EXIT_CODE)
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        LOG.info("Gitea secret/variable request failed: ${(e as? GiteaHttpStatusError)?.statusCode}")
        setErrorText((e as? GiteaHttpStatusError)?.apiMessage ?: e.localizedMessage)
      }
      finally {
        submitting = false
        isOKActionEnabled = true
      }
    }
  }
}

/**
 * New secret, or a new value for an existing one (values can't be read back). The value is a password field; it is
 * cleared when the dialog closes and never logged.
 */
class GiteaSecretDialog(
  project: Project,
  cs: CoroutineScope,
  /** `null` for a new secret. */
  private val existingName: String?,
  existingDescription: String?,
  private val takenNames: Set<String>,
  private val save: suspend (name: String, value: String, description: String?) -> Unit,
) : GiteaSubmitDialog(project, cs) {
  private val nameField = JBTextField(existingName.orEmpty()).apply { isEnabled = existingName == null }
  private val valueField = JBPasswordField()
  private val descriptionField = JBTextField(existingDescription.orEmpty())

  init {
    title = GiteaBundle.message(when {
      existingName == null -> "actions.secrets.add.title"
      else -> "actions.secrets.update.title"
    }, existingName.orEmpty())
    setOKButtonText(GiteaBundle.message(when {
      existingName == null -> "actions.secrets.add.ok"
      else -> "actions.secrets.update.ok"
    }))
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    row(GiteaBundle.message("actions.secrets.name")) {
      cell(nameField).align(AlignX.FILL).comment(GiteaBundle.message("actions.secrets.name.comment"))
    }
    row(GiteaBundle.message("actions.secrets.value")) {
      cell(valueField).align(AlignX.FILL).comment(GiteaBundle.message(when {
        existingName == null -> "actions.secrets.value.comment"
        else -> "actions.secrets.value.update.comment"
      }))
    }
    row(GiteaBundle.message("actions.secrets.description")) { cell(descriptionField).align(AlignX.FILL) }
  }

  override fun getPreferredFocusedComponent(): JComponent = when {
    existingName == null -> nameField
    else -> valueField
  }

  public override fun doValidateAll(): List<ValidationInfo> = buildList {
    if (existingName == null) {
      val name = nameField.text.trim()
      GiteaSecretNames.validate(name)?.let { add(ValidationInfo(it, nameField)) }
      if (name.uppercase() in takenNames) {
        add(ValidationInfo(GiteaBundle.message("actions.secrets.name.exists.secret"), nameField))
      }
    }
    if (valueField.password.isEmpty()) {
      add(ValidationInfo(GiteaBundle.message("actions.secrets.value.empty"), valueField))
    }
  }

  override suspend fun submit() {
    val chars = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { valueField.password }
    try {
      save(existingName ?: nameField.text.trim(), String(chars), descriptionField.text.trim().ifEmpty { null })
    }
    finally {
      Arrays.fill(chars, '\u0000')
    }
  }

  override fun dispose() {
    valueField.text = ""
    super.dispose()
  }
}

/** New variable, or editing one (also renaming it). Values may span lines. */
class GiteaVariableDialog(
  project: Project,
  cs: CoroutineScope,
  /** `null` for a new variable. */
  private val existingName: String?,
  existingValue: String?,
  existingDescription: String?,
  private val takenNames: Set<String>,
  private val save: suspend (name: String, value: String, description: String?) -> Unit,
) : GiteaSubmitDialog(project, cs) {
  private val nameField = JBTextField(existingName.orEmpty())
  private val valueField = JBTextArea(existingValue.orEmpty(), 5, 40)
  private val descriptionField = JBTextField(existingDescription.orEmpty())

  init {
    title = GiteaBundle.message(when {
      existingName == null -> "actions.variables.add.title"
      else -> "actions.variables.edit.title"
    }, existingName.orEmpty())
    setOKButtonText(GiteaBundle.message(when {
      existingName == null -> "actions.secrets.add.ok"
      else -> "actions.variables.edit.ok"
    }))
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    row(GiteaBundle.message("actions.secrets.name")) {
      cell(nameField).align(AlignX.FILL).comment(GiteaBundle.message("actions.secrets.name.comment"))
    }
    row(GiteaBundle.message("actions.secrets.value")) { cell(JBScrollPane(valueField)).align(AlignX.FILL) }
    row(GiteaBundle.message("actions.secrets.description")) { cell(descriptionField).align(AlignX.FILL) }
  }

  override fun getPreferredFocusedComponent(): JComponent = when {
    existingName == null -> nameField
    else -> valueField
  }

  public override fun doValidateAll(): List<ValidationInfo> = buildList {
    val name = nameField.text.trim()
    GiteaSecretNames.validate(name)?.let { add(ValidationInfo(it, nameField)) }
    if (!name.equals(existingName, ignoreCase = true) && name.uppercase() in takenNames) {
      add(ValidationInfo(GiteaBundle.message("actions.secrets.name.exists.variable"), nameField))
    }
    if (valueField.text.isEmpty()) {
      add(ValidationInfo(GiteaBundle.message("actions.secrets.value.empty"), valueField))
    }
  }

  override suspend fun submit() {
    val (name, value, description) = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
      Triple(nameField.text.trim(), valueField.text, descriptionField.text.trim().ifEmpty { null })
    }
    save(name, value, description)
  }

  /** Field access for tests. */
  internal fun fill(name: String, value: String) {
    nameField.text = name
    valueField.text = value
  }
}
