package de.hendrikfrahmann.giteaplugin.actions.workflow.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.EnvironmentUtil
import de.hendrikfrahmann.giteaplugin.actions.workflow.GiteaWorkflowFiles
import de.hendrikfrahmann.giteaplugin.actions.workflow.GiteaWorkflowRemotes
import de.hendrikfrahmann.giteaplugin.api.request.GiteaActionsScope
import de.hendrikfrahmann.giteaplugin.api.request.loadVariables
import de.hendrikfrahmann.giteaplugin.settings.GiteaGeneralSettingsConfigurable
import de.hendrikfrahmann.giteaplugin.settings.GiteaLocalRunner
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Starts `gitea-runner exec` for a [GiteaWorkflowRunConfiguration]; output in the Run tool window. */
class GiteaWorkflowRunState(private val configuration: GiteaWorkflowRunConfiguration, environment: ExecutionEnvironment)
  : CommandLineState(environment) {

  override fun startProcess(): ProcessHandler {
    val commandLine = createCommandLine().toGeneralCommandLine()
    val handler = KillableColoredProcessHandler(commandLine)
    // SIGINT first: `docker run` passes it on and the runner removes its job containers
    handler.setShouldKillProcessSoftly(true)
    ProcessTerminatedListener.attach(handler)
    return handler
  }

  private fun createCommandLine(): GiteaWorkflowCommandLine {
    val project = configuration.project
    val options = configuration.options
    val file = LocalFileSystem.getInstance().findFileByPath(configuration.workflowFile)
               ?: throw ExecutionException(GiteaBundle.message("workflow.run.error.file"))
    val workflow = GiteaWorkflowFiles.find(project, file) ?: throw ExecutionException(GiteaBundle.message("workflow.run.error.not.workflow"))
    val root = workflow.repository.root.toNioPath()

    val settings = GiteaSettings.getInstance()
    val runner = runner(options, settings)

    val secrets = options.secretsFile?.takeIf { it.isNotBlank() }?.let { GiteaWorkflowCommandLine.parseEnv(readFile(root, it)) }.orEmpty()
    val variables = when (options.variablesSource) {
      GiteaWorkflowVariablesSource.NONE -> emptyMap()
      GiteaWorkflowVariablesSource.FILE -> GiteaWorkflowCommandLine.parseEnv(readFile(root, options.variablesFile.orEmpty()))
      GiteaWorkflowVariablesSource.GITEA -> loadGiteaVariables(workflow)
    }

    return GiteaWorkflowCommandLine(
      runner = runner,
      root = root,
      workflowPath = workflow.relativePath,
      job = configuration.job.takeIf { it.isNotBlank() },
      event = options.event,
      jobImage = options.jobImage?.takeIf { it.isNotBlank() } ?: settings.jobImage,
      inputs = GiteaWorkflowCommandLine.parseEnv(options.inputs.orEmpty()),
      variables = variables,
      secrets = secrets,
      envFile = options.envFile?.takeIf { it.isNotBlank() },
      extraArguments = options.extraArguments.orEmpty(),
      containerName = "gitea-workflow-${UUID.randomUUID().toString().take(8)}",
    )
  }

  /** Docker or the runner binary, as chosen in the configuration or (by default) in the settings. */
  private fun runner(options: GiteaWorkflowRunConfigurationOptions, settings: GiteaSettings): GiteaWorkflowCommandLine.Runner {
    val runnerKind = when (options.runner) {
      GiteaWorkflowRunnerChoice.DOCKER -> GiteaLocalRunner.DOCKER
      GiteaWorkflowRunnerChoice.BINARY -> GiteaLocalRunner.BINARY
      GiteaWorkflowRunnerChoice.DEFAULT -> settings.localRunner
    }
    return when (runnerKind) {
      GiteaLocalRunner.DOCKER -> GiteaWorkflowCommandLine.Runner.Docker(
        findExecutable(settings.dockerExecutable, DOCKER_NAMES) ?: notConfigured(GiteaBundle.message("workflow.run.error.docker")),
        options.runnerImage?.takeIf { it.isNotBlank() } ?: settings.runnerImage)
      GiteaLocalRunner.BINARY -> GiteaWorkflowCommandLine.Runner.Binary(
        findExecutable(options.runnerBinary?.takeIf { it.isNotBlank() } ?: settings.runnerBinary, RUNNER_NAMES)
        ?: notConfigured(GiteaBundle.message("workflow.run.error.binary")))
    }
  }

  /** Repository variables of the first Gitea remote (`origin` first); needs admin rights on the repository. */
  private fun loadGiteaVariables(workflow: GiteaWorkflowFiles.WorkflowFile): Map<String, String> {
    val project = configuration.project
    val mapping = GiteaWorkflowRemotes.mappings(project, workflow.repository).firstOrNull()
                  ?: throw ExecutionException(GiteaBundle.message("workflow.run.error.no.remote"))
    val api = GiteaWorkflowRemotes.api(project, mapping)
              ?: throw ExecutionException(GiteaBundle.message("workflow.run.error.no.account", mapping.repository.serverPath))
    val load = suspend { api.loadVariables(GiteaActionsScope.Repository(mapping.repository.path)).associate { it.name to it.data } }
    return try {
      if (ApplicationManager.getApplication().isDispatchThread) {
        runWithModalProgressBlocking(project, GiteaBundle.message("workflow.run.loading.variables")) { load() }
      }
      else {
        runBlockingCancellable { load() }
      }
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: Exception) {
      throw ExecutionException(GiteaBundle.message("workflow.run.error.variables", e.localizedMessage ?: e.toString()), e)
    }
  }

  private fun readFile(root: Path, path: String): String =
    try {
      Files.readString(root.resolve(path))
    }
    catch (e: IOException) {
      throw ExecutionException(GiteaBundle.message("workflow.run.error.read", path, e.localizedMessage ?: e.toString()), e)
    }

  /** The runner isn't set up: show the Gitea settings and stop. */
  private fun notConfigured(message: String): Nothing {
    val project = configuration.project
    ApplicationManager.getApplication().invokeLater({
      ShowSettingsUtil.getInstance().showSettingsDialog(project, GiteaGeneralSettingsConfigurable::class.java)
    }, project.disposed)
    throw ExecutionException(message)
  }

  companion object {
    private val DOCKER_NAMES = listOf("docker")
    private val RUNNER_NAMES = listOf("gitea-runner", "act_runner")

    /** Places `PATH` misses when the IDE isn't started from a shell. */
    private val EXTRA_DIRS = listOf("/usr/local/bin", "/opt/homebrew/bin", "/Applications/Docker.app/Contents/Resources/bin")

    /** [configured] if it is executable; when it is empty, the first of [names] on `PATH` (or in [EXTRA_DIRS]). */
    fun findExecutable(configured: String, names: List<String>): String? {
      if (configured.isNotBlank()) {
        return configured.takeIf { File(it).canExecute() }
      }
      for (name in names) {
        findOnPath(name)?.let { return it.path }
        EXTRA_DIRS.map { File(it, name) }.firstOrNull { it.canExecute() }?.let { return it.path }
      }
      return null
    }

    // Own lookup: PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS is scheduled for removal in 2026.3.
    // EnvironmentUtil has the shell's PATH, also when the IDE was started from the Dock.
    private fun findOnPath(name: String): File? {
      val path = EnvironmentUtil.getValue("PATH") ?: return null
      val fileNames = when {
        SystemInfo.isWindows -> listOf("$name.exe", name)
        else -> listOf(name)
      }
      return path.split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .flatMap { dir -> fileNames.map { File(dir, it) } }
        .firstOrNull { it.isFile && it.canExecute() }
    }
  }
}
