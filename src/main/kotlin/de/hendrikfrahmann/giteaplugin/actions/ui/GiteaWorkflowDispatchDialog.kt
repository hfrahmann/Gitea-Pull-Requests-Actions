package de.hendrikfrahmann.giteaplugin.actions.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflow
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflowDispatch
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import javax.swing.JComponent

/**
 * "Run Workflow": the ref to run on and the `workflow_dispatch` inputs, with the input types of the workflow
 * file (text, boolean, choice, number, environment). Required inputs must be filled.
 */
class GiteaWorkflowDispatchDialog(
  project: Project,
  workflow: GiteaWorkflow,
  private val refs: List<String>,
  initialRef: String?,
  private val dispatch: GiteaWorkflowDispatch,
) : DialogWrapper(project) {
  private val refField = ComboBox(refs.toTypedArray()).apply {
    isEditable = true
    selectedItem = initialRef ?: refs.firstOrNull()
  }
  private val inputFields: Map<GiteaWorkflowDispatch.Input, JComponent> = dispatch.inputs.associateWith { input ->
    when (input.type) {
      GiteaWorkflowDispatch.Type.BOOLEAN -> JBCheckBox(input.name, input.default.toBoolean())
      GiteaWorkflowDispatch.Type.CHOICE -> ComboBox(input.options.toTypedArray()).apply {
        selectedItem = input.default?.takeIf { it in input.options } ?: input.options.firstOrNull()
      }
      else -> JBTextField(input.default.orEmpty())
    }
  }

  init {
    title = GiteaBundle.message("actions.dispatch.title", workflow.name)
    setOKButtonText(GiteaBundle.message("actions.dispatch.ok"))
    init()
  }

  /** Input fields by input name (tests). */
  internal val inputComponents: Map<String, JComponent> get() = inputFields.mapKeys { it.key.name }

  /** Branch, tag or `refs/…` to run on. */
  val ref: String get() = (refField.editor.item ?: refField.selectedItem)?.toString()?.trim().orEmpty()

  /** Input values as the API takes them: strings, booleans as `true`/`false`. */
  val inputs: Map<String, String>
    get() = inputFields.mapKeys { it.key.name }.mapValues { (_, field) -> value(field) }

  override fun createCenterPanel(): JComponent = panel {
    row(GiteaBundle.message("actions.dispatch.ref")) { cell(refField).align(AlignX.FILL) }
    if (dispatch.inputs.isNotEmpty()) {
      group(GiteaBundle.message("actions.dispatch.inputs")) {
        for ((input, field) in inputFields) {
          val content: Row.() -> Unit = {
            val cell = cell(field).align(AlignX.FILL)
            input.description?.let { cell.comment(it) }
          }
          // a checkbox carries its own label
          if (input.type == GiteaWorkflowDispatch.Type.BOOLEAN) {
            row(init = content)
          }
          else {
            row(input.name + if (input.required) {
              " *"
            }
            else {
              ""
            }, content)
          }
        }
      }
    }
  }

  override fun getPreferredFocusedComponent(): JComponent = refField

  /**
   * Re-check while typing. DialogWrapper switches continuous validation off for Kotlin UI DSL panels (it expects
   * `validationOnInput` on the cells), so without this a failed check on "Run" kept the button disabled.
   */
  public override fun continuousValidation(): Boolean = true

  public override fun doValidateAll(): List<ValidationInfo> = buildList {
    if (ref.isEmpty()) {
      add(ValidationInfo(GiteaBundle.message("actions.dispatch.ref.empty"), refField))
    }
    for ((input, field) in inputFields) {
      if (input.required && input.type != GiteaWorkflowDispatch.Type.BOOLEAN && value(field).isBlank()) {
        add(ValidationInfo(GiteaBundle.message("actions.dispatch.input.required", input.name), field))
      }
      if (input.type == GiteaWorkflowDispatch.Type.NUMBER && value(field).isNotBlank() && value(field).toDoubleOrNull() == null) {
        add(ValidationInfo(GiteaBundle.message("actions.dispatch.input.number", input.name), field))
      }
    }
  }

  private fun value(field: JComponent): String = when (field) {
    is JBCheckBox -> field.isSelected.toString()
    is ComboBox<*> -> field.selectedItem?.toString().orEmpty()
    is JBTextField -> field.text.trim()
    else -> ""
  }
}
