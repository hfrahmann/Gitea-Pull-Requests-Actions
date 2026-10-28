package de.hendrikfrahmann.giteaplugin.actions.workflow.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SimpleListCellRenderer
import de.hendrikfrahmann.giteaplugin.actions.workflow.GiteaWorkflowFiles
import de.hendrikfrahmann.giteaplugin.settings.GiteaLocalRunner
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import de.hendrikfrahmann.giteaplugin.util.GiteaIcons
import org.jetbrains.annotations.Nls
import javax.swing.JList

/** "Gitea Workflow" run configurations: a workflow (or one job of it) run locally with `gitea-runner exec`. */
class GiteaWorkflowConfigurationType : ConfigurationTypeBase(ID, GiteaBundle.message("workflow.run.type.name"),
                                                            GiteaBundle.message("workflow.run.type.description"), GiteaIcons.Gitea) {
  init {
    addFactory(GiteaWorkflowConfigurationFactory(this))
  }

  val factory: ConfigurationFactory get() = configurationFactories.single()

  companion object {
    const val ID: String = "GiteaWorkflow"

    fun getInstance(): GiteaWorkflowConfigurationType = ConfigurationTypeUtil.findConfigurationType(GiteaWorkflowConfigurationType::class.java)
  }
}

class GiteaWorkflowConfigurationFactory(type: GiteaWorkflowConfigurationType) : ConfigurationFactory(type) {
  override fun getId(): String = GiteaWorkflowConfigurationType.ID

  override fun createTemplateConfiguration(project: Project): RunConfiguration =
    GiteaWorkflowRunConfiguration(project, this, GiteaBundle.message("workflow.run.type.name"))

  override fun getOptionsClass(): Class<out BaseState> = GiteaWorkflowRunConfigurationOptions::class.java
}

/** Runner of one configuration: the settings' default, or a fixed one. */
enum class GiteaWorkflowRunnerChoice { DEFAULT, DOCKER, BINARY }

/** Where the `vars` context of a local run comes from. */
enum class GiteaWorkflowVariablesSource { NONE, GITEA, FILE }

class GiteaWorkflowRunConfigurationOptions : LocatableRunConfigurationOptions() {
  /** Absolute path of the workflow file. */
  var workflowFile: String? by string("")

  /** Job id; empty = all jobs. */
  var job: String? by string("")

  /** Event that triggers the run; empty = the runner's default (`push`). */
  var event: String? by string("")

  /** `name=value` per line, for `workflow_dispatch` inputs. */
  var inputs: String? by string("")

  var runner: GiteaWorkflowRunnerChoice by enum(GiteaWorkflowRunnerChoice.DEFAULT)

  /** Empty = from the Gitea settings. */
  var runnerImage: String? by string("")
  var runnerBinary: String? by string("")
  var jobImage: String? by string("")

  /** `.env`-style file with secrets; values only reach the runner through its environment. */
  var secretsFile: String? by string("")

  var variablesSource: GiteaWorkflowVariablesSource by enum(GiteaWorkflowVariablesSource.NONE)
  var variablesFile: String? by string("")

  /** `--env-file` for the job containers. */
  var envFile: String? by string("")

  var extraArguments: String? by string("")
}

class GiteaWorkflowRunConfiguration(project: Project, factory: ConfigurationFactory, name: String?)
  : LocatableConfigurationBase<GiteaWorkflowRunConfigurationOptions>(project, factory, name) {

  public override fun getOptions(): GiteaWorkflowRunConfigurationOptions = super.getOptions() as GiteaWorkflowRunConfigurationOptions

  var workflowFile: String
    get() = options.workflowFile.orEmpty()
    set(value) {
      options.workflowFile = value
    }

  var job: String
    get() = options.job.orEmpty()
    set(value) {
      options.job = value
    }

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GiteaWorkflowSettingsEditor(project)

  override fun checkConfiguration() {
    val file = workflowFile.takeIf { it.isNotBlank() }?.let { LocalFileSystem.getInstance().findFileByPath(it) }
               ?: throw RuntimeConfigurationError(GiteaBundle.message("workflow.run.error.file"))
    if (GiteaWorkflowFiles.find(project, file) == null) {
      throw RuntimeConfigurationError(GiteaBundle.message("workflow.run.error.not.workflow"))
    }
    if (options.variablesSource == GiteaWorkflowVariablesSource.FILE && options.variablesFile.isNullOrBlank()) {
      throw RuntimeConfigurationError(GiteaBundle.message("workflow.run.error.variables.file"))
    }
  }

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
    GiteaWorkflowRunState(this, environment)

  override fun suggestedName(): @NlsSafe String = suggestedName(workflowFile.substringAfterLast('/'), job)

  companion object {
    fun suggestedName(fileName: String, job: String?): @NlsSafe String =
      if (job.isNullOrBlank()) {
        fileName
      }
      else {
        "$fileName: $job"
      }
  }
}

object GiteaLocalRunnerUi {
  /** List renderer showing [text] of each value. */
  fun <T> renderer(text: (T?) -> @Nls String): SimpleListCellRenderer<T> = object : SimpleListCellRenderer<T>() {
    override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
      this.text = text(value)
    }
  }

  fun text(runner: GiteaLocalRunner?): @Nls String = when (runner) {
    GiteaLocalRunner.BINARY -> GiteaBundle.message("settings.local.runs.runner.binary")
    else -> GiteaBundle.message("settings.local.runs.runner.docker")
  }

  fun text(choice: GiteaWorkflowRunnerChoice?): @Nls String = when (choice) {
    GiteaWorkflowRunnerChoice.DOCKER -> text(GiteaLocalRunner.DOCKER)
    GiteaWorkflowRunnerChoice.BINARY -> text(GiteaLocalRunner.BINARY)
    else -> GiteaBundle.message("workflow.run.runner.default")
  }

  fun text(source: GiteaWorkflowVariablesSource?): @Nls String = when (source) {
    GiteaWorkflowVariablesSource.GITEA -> GiteaBundle.message("workflow.run.variables.gitea")
    GiteaWorkflowVariablesSource.FILE -> GiteaBundle.message("workflow.run.variables.file")
    else -> GiteaBundle.message("workflow.run.variables.none")
  }
}
