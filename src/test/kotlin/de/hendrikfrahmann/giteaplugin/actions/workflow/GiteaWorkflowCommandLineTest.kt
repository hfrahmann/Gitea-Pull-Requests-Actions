package de.hendrikfrahmann.giteaplugin.actions.workflow

import de.hendrikfrahmann.giteaplugin.actions.workflow.run.GiteaWorkflowCommandLine
import de.hendrikfrahmann.giteaplugin.actions.workflow.run.GiteaWorkflowCommandLine.Runner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Command line of local workflow runs. */
class GiteaWorkflowCommandLineTest {
  private val root = Path.of("/work/repo")

  private fun commandLine(runner: Runner, envFile: String? = null) = GiteaWorkflowCommandLine(
    runner = runner,
    root = root,
    workflowPath = ".gitea/workflows/ci.yml",
    job = "build",
    event = "workflow_dispatch",
    jobImage = "node:20-bookworm",
    inputs = mapOf("size" to "1"),
    variables = mapOf("MYVAR" to "hi"),
    secrets = mapOf("TOKEN" to "s3cr3t"),
    envFile = envFile,
    extraArguments = "--pull --container-architecture \"linux/amd64\"",
    containerName = "gitea-workflow-test",
  )

  @Test
  fun `docker runs the runner image with socket and repository mounted at the same path`() {
    val line = commandLine(Runner.Docker("/usr/bin/docker", "gitea/runner:5.0.0")).toGeneralCommandLine()
    assertEquals(listOf("/usr/bin/docker", "run", "--rm", "-i", "--name", "gitea-workflow-test",
                        "-v", "/var/run/docker.sock:/var/run/docker.sock", "-v", "/work/repo:/work/repo", "-w", "/work/repo",
                        "-e", "TOKEN", "--entrypoint", "gitea-runner", "gitea/runner:5.0.0",
                        "exec", "-W", ".gitea/workflows/ci.yml", "-j", "build", "-E", "workflow_dispatch", "--image", "node:20-bookworm",
                        "--input", "size=1", "--var", "MYVAR=hi", "-s", "TOKEN", "--pull", "--container-architecture", "linux/amd64"),
                 line.getCommandLineList(null))
    assertEquals("s3cr3t", line.environment["TOKEN"])
    assertFalse(line.commandLineString.contains("s3cr3t"))
    assertEquals(root.toFile(), line.workDirectory)
  }

  @Test
  fun `binary runs exec directly`() {
    val line = commandLine(Runner.Binary("/usr/local/bin/gitea-runner"), envFile = ".env.local").toGeneralCommandLine()
    val args = line.getCommandLineList(null)
    assertEquals(listOf("/usr/local/bin/gitea-runner", "exec", "-W", ".gitea/workflows/ci.yml", "-j", "build"), args.take(6))
    assertEquals(listOf("--env-file", ".env.local"), args.subList(args.indexOf("--env-file"), args.indexOf("--env-file") + 2))
    assertFalse(line.commandLineString.contains("s3cr3t"))
  }

  @Test
  fun `env file outside the repository gets its own docker mount`() {
    val args = commandLine(Runner.Docker("docker", "img"), envFile = "/home/me/ci.env").toGeneralCommandLine().getCommandLineList(null)
    assertEquals("/home/me/ci.env:/home/me/ci.env:ro", args[args.indexOf("-e") - 1])
    val inside = commandLine(Runner.Docker("docker", "img"), envFile = ".env").toGeneralCommandLine().getCommandLineList(null)
    assertFalse(inside.any { it.endsWith(":ro") })
  }

  @Test
  fun `env files are parsed like dotenv`() {
    assertEquals(mapOf("A" to "1", "B" to "two words", "C" to "x=y", "D" to "'q'"),
                 GiteaWorkflowCommandLine.parseEnv("# comment\n\nA=1\nexport B=\"two words\"\nC=x=y\nD=\"'q'\"\n=nope\nno-equals"))
  }
}
