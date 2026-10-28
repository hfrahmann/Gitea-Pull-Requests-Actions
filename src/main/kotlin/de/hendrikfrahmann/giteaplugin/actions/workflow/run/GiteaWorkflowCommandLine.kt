package de.hendrikfrahmann.giteaplugin.actions.workflow.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Path

/**
 * The command line of a local workflow run: `gitea-runner exec` directly, or in the runner image with the
 * Docker socket and the repository mounted at the same path (job containers get the files copied from that path).
 * Secret values never appear in the arguments: `-s NAME` (and `docker run -e NAME`) take them from the environment.
 */
data class GiteaWorkflowCommandLine(
  val runner: Runner,
  /** Repository root – the working directory, mounted at the same path in Docker. */
  val root: Path,
  /** Workflow file relative to [root]. */
  val workflowPath: String,
  val job: String?,
  val event: String?,
  val jobImage: String?,
  val inputs: Map<String, String>,
  val variables: Map<String, String>,
  val secrets: Map<String, String>,
  /** `.env` file for the containers (`--env-file`), relative to [root] or absolute. */
  val envFile: String?,
  val extraArguments: String,
  /** Container name for Docker, so a stopped run can be cleaned up. */
  val containerName: String,
) {
  sealed interface Runner {
    data class Docker(val dockerExecutable: String, val image: String) : Runner
    data class Binary(val executable: String) : Runner
  }

  fun toGeneralCommandLine(): GeneralCommandLine {
    val command = mutableListOf<String>()
    when (runner) {
      is Runner.Docker -> {
        val root = root.toString()
        command += listOf(runner.dockerExecutable, "run", "--rm", "-i", "--name", containerName,
                          "-v", "$DOCKER_SOCKET:$DOCKER_SOCKET", "-v", "$root:$root", "-w", root)
        envFile?.let(root::resolveOutside)?.let { command += listOf("-v", "$it:$it:ro") }
        secrets.keys.forEach { command += listOf("-e", it) }
        command += listOf("--entrypoint", RUNNER_ENTRYPOINT, runner.image)
      }
      is Runner.Binary -> command += runner.executable
    }
    command += listOf("exec", "-W", workflowPath)
    job?.takeIf { it.isNotBlank() }?.let { command += listOf("-j", it) }
    event?.takeIf { it.isNotBlank() }?.let { command += listOf("-E", it) }
    jobImage?.takeIf { it.isNotBlank() }?.let { command += listOf("--image", it) }
    envFile?.takeIf { it.isNotBlank() }?.let { command += listOf("--env-file", it) }
    inputs.forEach { (name, value) -> command += listOf("--input", "$name=$value") }
    variables.forEach { (name, value) -> command += listOf("--var", "$name=$value") }
    secrets.keys.forEach { command += listOf("-s", it) }
    command += ParametersListUtil.parse(extraArguments)
    return GeneralCommandLine(command)
      .withWorkingDirectory(root)
      .withEnvironment(secrets)
      .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
  }

  companion object {
    const val DOCKER_SOCKET: String = "/var/run/docker.sock"
    const val RUNNER_ENTRYPOINT: String = "gitea-runner"

    /** `NAME=value` lines (`.env` style): `#` comments, blank lines, optional `export `, quoted values. */
    fun parseEnv(text: String): Map<String, String> {
      val result = linkedMapOf<String, String>()
      for (raw in text.lines()) {
        val line = raw.trim().removePrefix("export ").trim()
        if (line.isEmpty() || line.startsWith("#")) {
          continue
        }
        val name = line.substringBefore('=', "").trim()
        if (name.isEmpty()) {
          continue
        }
        var value = line.substringAfter('=').trim()
        if (value.length >= 2 && (value.first() == '"' && value.last() == '"' || value.first() == '\'' && value.last() == '\'')) {
          value = value.substring(1, value.length - 1)
        }
        result[name] = value
      }
      return result
    }
  }
}

/** [path] when it is outside this directory (so Docker needs its own mount), else `null`. */
private fun String.resolveOutside(path: String): String? {
  val root = Path.of(this)
  val resolved = root.resolve(path).normalize()
  return when {
    resolved.startsWith(root) -> null
    else -> resolved.toString()
  }
}
