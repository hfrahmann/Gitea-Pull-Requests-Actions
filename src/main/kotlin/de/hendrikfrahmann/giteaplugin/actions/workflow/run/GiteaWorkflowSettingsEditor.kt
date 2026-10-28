package de.hendrikfrahmann.giteaplugin.actions.workflow.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueIs
import com.intellij.ui.layout.selectedValueMatches
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import javax.swing.JComponent

/** Editor of a "Gitea Workflow" run configuration. */
class GiteaWorkflowSettingsEditor(private val project: Project) : SettingsEditor<GiteaWorkflowRunConfiguration>() {
  private val workflowFile = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor()
      .withExtensionFilter(GiteaBundle.message("workflow.run.file.filter"), "yml", "yaml")
      .withTitle(GiteaBundle.message("workflow.run.file.title")))
  }
  private val job = JBTextField()
  private val event = ComboBox(EVENTS.toTypedArray()).apply { isEditable = true }
  private val inputs = JBTextArea(3, 40)
  private val runner = ComboBox(GiteaWorkflowRunnerChoice.entries.toTypedArray()).apply {
    renderer = GiteaLocalRunnerUi.renderer<GiteaWorkflowRunnerChoice> { GiteaLocalRunnerUi.text(it) }
  }
  private val runnerImage = JBTextField()
  private val runnerBinary = fileField(GiteaBundle.message("settings.local.runs.binary.title"))
  private val jobImage = JBTextField()
  private val secretsFile = fileField(GiteaBundle.message("workflow.run.secrets.title"))
  private val variablesSource = ComboBox(GiteaWorkflowVariablesSource.entries.toTypedArray()).apply {
    renderer = GiteaLocalRunnerUi.renderer<GiteaWorkflowVariablesSource> { GiteaLocalRunnerUi.text(it) }
  }
  private val variablesFile = fileField(GiteaBundle.message("workflow.run.variables.title"))
  private val envFile = fileField(GiteaBundle.message("workflow.run.env.title"))
  private val extraArguments = JBTextField()

  private fun fileField(title: String) = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor().withTitle(title))
  }

  override fun createEditor(): JComponent = panel {
    row(GiteaBundle.message("workflow.run.file")) { cell(workflowFile).align(AlignX.FILL) }
    row(GiteaBundle.message("workflow.run.job")) {
      cell(job).align(AlignX.FILL).comment(GiteaBundle.message("workflow.run.job.comment"))
    }
    row(GiteaBundle.message("workflow.run.event")) {
      cell(event).comment(GiteaBundle.message("workflow.run.event.comment"))
    }
    row(GiteaBundle.message("workflow.run.inputs")) {
      scrollCell(inputs).align(AlignX.FILL).comment(GiteaBundle.message("workflow.run.inputs.comment"))
    }
    group(GiteaBundle.message("workflow.run.runner.group")) {
      row(GiteaBundle.message("settings.local.runs.runner")) {
        cell(runner).comment(GiteaBundle.message("workflow.run.runner.comment"))
      }
      row(GiteaBundle.message("settings.local.runs.image")) {
        cell(runnerImage).align(AlignX.FILL).comment(GiteaBundle.message("workflow.run.default.comment"))
      }.visibleIf(runner.selectedValueMatches { it != GiteaWorkflowRunnerChoice.BINARY })
      row(GiteaBundle.message("settings.local.runs.binary")) {
        cell(runnerBinary).align(AlignX.FILL).comment(GiteaBundle.message("workflow.run.default.comment"))
      }.visibleIf(runner.selectedValueIs(GiteaWorkflowRunnerChoice.BINARY))
      row(GiteaBundle.message("settings.local.runs.job.image")) {
        cell(jobImage).align(AlignX.FILL).comment(GiteaBundle.message("workflow.run.default.comment"))
      }
    }
    group(GiteaBundle.message("workflow.run.context.group")) {
      row(GiteaBundle.message("workflow.run.secrets")) {
        cell(secretsFile).align(AlignX.FILL).comment(GiteaBundle.message("workflow.run.secrets.comment"))
      }
      row(GiteaBundle.message("workflow.run.variables")) { cell(variablesSource) }
      row(GiteaBundle.message("workflow.run.variables.file.label")) {
        cell(variablesFile).align(AlignX.FILL).comment(GiteaBundle.message("workflow.run.env.format.comment"))
      }.visibleIf(variablesSource.selectedValueIs(GiteaWorkflowVariablesSource.FILE))
      row {
        comment(GiteaBundle.message("workflow.run.variables.gitea.comment"))
      }.visibleIf(variablesSource.selectedValueIs(GiteaWorkflowVariablesSource.GITEA))
      row(GiteaBundle.message("workflow.run.env")) {
        cell(envFile).align(AlignX.FILL).comment(GiteaBundle.message("workflow.run.env.comment"))
      }
      row(GiteaBundle.message("workflow.run.arguments")) {
        cell(extraArguments).align(AlignX.FILL).comment(GiteaBundle.message("workflow.run.arguments.comment"))
      }
    }
  }

  override fun resetEditorFrom(configuration: GiteaWorkflowRunConfiguration) {
    val options = configuration.options
    workflowFile.text = configuration.workflowFile
    job.text = configuration.job
    event.selectedItem = options.event.orEmpty()
    inputs.text = options.inputs.orEmpty()
    runner.selectedItem = options.runner
    runnerImage.text = options.runnerImage.orEmpty()
    runnerBinary.text = options.runnerBinary.orEmpty()
    jobImage.text = options.jobImage.orEmpty()
    secretsFile.text = options.secretsFile.orEmpty()
    variablesSource.selectedItem = options.variablesSource
    variablesFile.text = options.variablesFile.orEmpty()
    envFile.text = options.envFile.orEmpty()
    extraArguments.text = options.extraArguments.orEmpty()
  }

  override fun applyEditorTo(configuration: GiteaWorkflowRunConfiguration) {
    val options = configuration.options
    configuration.workflowFile = workflowFile.text.trim()
    configuration.job = job.text.trim()
    options.event = (event.editor.item as? String ?: event.selectedItem as? String).orEmpty().trim()
    options.inputs = inputs.text
    options.runner = runner.selectedItem as? GiteaWorkflowRunnerChoice ?: GiteaWorkflowRunnerChoice.DEFAULT
    options.runnerImage = runnerImage.text.trim()
    options.runnerBinary = runnerBinary.text.trim()
    options.jobImage = jobImage.text.trim()
    options.secretsFile = secretsFile.text.trim()
    options.variablesSource = variablesSource.selectedItem as? GiteaWorkflowVariablesSource ?: GiteaWorkflowVariablesSource.NONE
    options.variablesFile = variablesFile.text.trim()
    options.envFile = envFile.text.trim()
    options.extraArguments = extraArguments.text.trim()
  }

  companion object {
    /** Offered in the event box (editable); empty = the runner's default, `push`. */
    private val EVENTS = listOf("", "push", "pull_request", "workflow_dispatch", "schedule", "release")
  }
}
