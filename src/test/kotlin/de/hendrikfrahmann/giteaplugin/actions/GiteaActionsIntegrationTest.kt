package de.hendrikfrahmann.giteaplugin.actions

import de.hendrikfrahmann.giteaplugin.GiteaTestEnv
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaArtifact
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJobLog
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogLine
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogSection
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaRun
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflow
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflowDispatch
import de.hendrikfrahmann.giteaplugin.actions.secrets.GiteaRunners
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaActionRunsFilter
import de.hendrikfrahmann.giteaplugin.api.request.GiteaActionsScope
import de.hendrikfrahmann.giteaplugin.api.request.artifactZipUri
import de.hendrikfrahmann.giteaplugin.api.request.createVariable
import de.hendrikfrahmann.giteaplugin.api.request.deleteSecret
import de.hendrikfrahmann.giteaplugin.api.request.deleteVariable
import de.hendrikfrahmann.giteaplugin.api.request.download
import de.hendrikfrahmann.giteaplugin.api.request.getRun
import de.hendrikfrahmann.giteaplugin.api.request.loadJobLog
import de.hendrikfrahmann.giteaplugin.api.request.loadJobLogFrom
import de.hendrikfrahmann.giteaplugin.api.request.loadOrganizationPermissions
import de.hendrikfrahmann.giteaplugin.api.request.loadRawFile
import de.hendrikfrahmann.giteaplugin.api.request.loadRunArtifacts
import de.hendrikfrahmann.giteaplugin.api.request.loadRunAttemptJobs
import de.hendrikfrahmann.giteaplugin.api.request.loadRunJobs
import de.hendrikfrahmann.giteaplugin.api.request.loadRuns
import de.hendrikfrahmann.giteaplugin.api.request.loadSecrets
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.api.request.loadVariables
import de.hendrikfrahmann.giteaplugin.api.request.loadWorkflows
import de.hendrikfrahmann.giteaplugin.api.request.putSecret
import de.hendrikfrahmann.giteaplugin.api.request.rerunFailedJobs
import de.hendrikfrahmann.giteaplugin.api.request.runLogsZipUri
import de.hendrikfrahmann.giteaplugin.api.request.updateVariable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path

/**
 * Actions against the dev Gitea's `plugin-playground` (seeded by `.github/scripts/seed-gitea.sh`: `ci.yml` with `build`, which
 * uploads `build-info`, and `flaky`, which fails off `main`). Re-runs failed jobs as the write collaborator, so each
 * run adds an attempt to an old run. `./gradlew integrationTest`.
 */
@Tag("integration")
class GiteaActionsIntegrationTest {
  private val env = GiteaTestEnv.load()
  private val repo = GiteaRepositoryPath("intellij-dev", "plugin-playground")
  private val repoScope = GiteaActionsScope.Repository(repo)

  private fun api(tokenKey: String): GiteaApi =
    GiteaApiImpl(GiteaServerPath(env.getProperty("GITEA_URL")), HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                 { GiteaCredentials.AccessToken(env.getProperty(tokenKey)) }, "Gitea-IntelliJ/test", { it.loadServerMetadata() })

  private val dev = api("INTELLIJ_DEV_TOKEN")
  private val reviewer = api("INTELLIJ_REVIEWER_TOKEN")

  @Test
  fun `workflow file can be dispatched and pull request runs carry the PR head`() = runBlocking {
    val workflow = dev.loadWorkflows(repo).map(GiteaWorkflow::from).single { it.id == "ci.yml" }
    val yaml = dev.loadRawFile(repo, workflow.path!!, "main")!!
    assertEquals(emptyList<GiteaWorkflowDispatch.Input>(), GiteaWorkflowDispatch.parse(yaml)?.inputs, "on: [push, pull_request, workflow_dispatch]")

    // inputs.yml (seed script): every input type
    val inputsYaml = dev.loadRawFile(repo, ".gitea/workflows/inputs.yml", "main")!!
    val inputs = GiteaWorkflowDispatch.parse(inputsYaml)!!.inputs
    assertEquals(listOf("greeting", "target", "dry-run", "log-level", "retries", "environment", "notes", "sleep-seconds"), inputs.map { it.name })
    assertEquals(listOf(GiteaWorkflowDispatch.Type.STRING, GiteaWorkflowDispatch.Type.STRING, GiteaWorkflowDispatch.Type.BOOLEAN,
                        GiteaWorkflowDispatch.Type.CHOICE, GiteaWorkflowDispatch.Type.NUMBER, GiteaWorkflowDispatch.Type.ENVIRONMENT,
                        GiteaWorkflowDispatch.Type.STRING, GiteaWorkflowDispatch.Type.NUMBER), inputs.map { it.type })
    assertEquals(listOf("debug", "info", "warning", "error"), inputs.single { it.name == "log-level" }.options)
    assertTrue(inputs.single { it.name == "target" }.required)
    assertEquals("Multi-line description:\nno type given, so it is a string", inputs.single { it.name == "notes" }.description)

    val prRun = dev.loadRuns(repo, GiteaActionRunsFilter(event = "pull_request"), 1, 1).workflowRuns.map(GiteaRun::from).first()
    assertNotNull(prRun.pullRequestNumber)
    val byHead = dev.loadRuns(repo, GiteaActionRunsFilter(headSha = prRun.headSha), 1, 10).workflowRuns.map { it.id }
    assertTrue(prRun.id in byHead)
  }

  @Test
  fun `failed job log is split into steps with the error lines`() = runBlocking {
    assumeTrue(GiteaTestEnv.hasRunner, "needs finished workflow runs")
    val failed = dev.loadRuns(repo, GiteaActionRunsFilter(event = "pull_request"), 1, 10).workflowRuns.map(GiteaRun::from)
      .first { it.status == GiteaActionStatus.FAILURE }
    val job = dev.loadRunJobs(repo, failed.id).map(GiteaJob::from).first { it.name == "flaky" && it.status == GiteaActionStatus.FAILURE }
    val log = GiteaJobLog.parse(dev.loadJobLog(repo, job.id), job.steps)
    val step = log.sections.single { it.type == GiteaLogSection.Type.STEP }
    assertEquals("Fails on feature branches", step.title)
    assertTrue(step.lines.any { log.lines[it].kind == GiteaLogLine.Kind.ERROR && log.lines[it].text == "intentional failure" })
    assertEquals(GiteaLogSection.Type.COMPLETE, log.sections.last().type)

    // incremental reading (the log panel's 1 s polling)
    val whole = dev.loadJobLogFrom(repo, job.id, 0)!!
    assertNull(dev.loadJobLogFrom(repo, job.id, whole.totalBytes), "416 at the end")
    val tail = dev.loadJobLogFrom(repo, job.id, whole.totalBytes - 10)!!
    assertEquals(whole.text.toByteArray().takeLast(10).toByteArray().toString(Charsets.UTF_8), tail.text)
    assertEquals(whole.totalBytes, tail.totalBytes)
  }

  @Test
  fun `re-running failed jobs creates a new attempt with new job ids`() = runBlocking {
    assumeTrue(GiteaTestEnv.hasRunner, "needs finished workflow runs")
    val run = dev.loadRuns(repo, GiteaActionRunsFilter(branch = "feature/greeting"), 1, 10).workflowRuns.map(GiteaRun::from)
      .first { it.status == GiteaActionStatus.FAILURE }
    val oldJobs = dev.loadRunJobs(repo, run.id).map { it.id }.toSet()

    reviewer.rerunFailedJobs(repo, run.id)

    var after = GiteaRun.from(dev.getRun(repo, run.id))
    repeat(20) {
      if (after.attempt > run.attempt) {
        return@repeat
      }
      delay(250)
      after = GiteaRun.from(dev.getRun(repo, run.id))
    }
    assertEquals(run.attempt + 1, after.attempt)
    val newJobs = dev.loadRunJobs(repo, run.id)
    assertTrue(newJobs.none { it.id in oldJobs }, "the latest attempt has new job ids")
    assertEquals(oldJobs, dev.loadRunAttemptJobs(repo, run.id, run.attempt).map { it.id }.toSet())
  }

  @Test
  fun `artifact and run logs download through the signed redirect`(@TempDir dir: Path) = runBlocking {
    assumeTrue(GiteaTestEnv.hasRunner, "needs finished workflow runs")
    val runs = dev.loadRuns(repo, GiteaActionRunsFilter(), 1, 30).workflowRuns.map(GiteaRun::from)
    val (run, artifact) = runs.firstNotNullOf { run ->
      dev.loadRunArtifacts(repo, run.id).map(GiteaArtifact::from).firstOrNull { !it.expired }?.let { run to it }
    }
    val zip = dir.resolve("artifact.zip")
    dev.download(dev.artifactZipUri(repo, artifact.id), zip)
    assertEquals("PK", String(Files.readAllBytes(zip).copyOf(2)))
    assertTrue(Files.isRegularFile(GiteaActionsDownloads.unzip(zip).resolve("build-info.txt")))

    val logs = dir.resolve("logs.zip")
    dev.download(dev.runLogsZipUri(repo, run.id), logs)
    assertEquals("PK", String(Files.readAllBytes(logs).copyOf(2)))
  }

  @Test
  fun `secrets and variables round trip as repo admin, write collaborators are refused`() = runBlocking {
    val name = "IT_${System.currentTimeMillis()}"
    try {
      assertTrue(dev.putSecret(repoScope, name.lowercase(), "first", "integration test"), "created")
      assertFalse(dev.putSecret(repoScope, name, "second", "integration test"), "overwritten")
      val secret = dev.loadSecrets(repoScope).single { it.name == name }
      assertEquals("integration test", secret.description)

      dev.createVariable(repoScope, name, "line1\nline2", null)
      assertEquals("line1\nline2", dev.loadVariables(repoScope).single { it.name == name }.data)
      dev.updateVariable(repoScope, name, "${name}_R", "changed", "renamed")
      assertEquals("changed", dev.loadVariables(repoScope).single { it.name == "${name}_R" }.data)

      val reserved = assertThrows(GiteaHttpStatusError::class.java) { runBlocking { dev.putSecret(repoScope, "GITHUB_X", "x", null) } }
      assertEquals(400, reserved.statusCode)
      assertTrue(reserved.apiMessage!!.contains("GITHUB_"))

      val forbidden = assertThrows(GiteaHttpStatusError::class.java) { runBlocking { reviewer.loadSecrets(repoScope) } }
      assertEquals(GiteaHttpStatusError.Kind.FORBIDDEN, forbidden.kind)

      // runners: the dev runner is registered for the whole instance, so a repo admin (not site admin) sees none
      val owner = GiteaUserDTO(3, "intellij-dev")
      assertEquals(emptyList<Any>(), GiteaRunners.load(dev, repo, owner, owner))
      assertThrows(GiteaHttpStatusError::class.java) { runBlocking { GiteaRunners.load(reviewer, repo, owner, GiteaUserDTO(4, "intellij-reviewer")) } }
    }
    finally {
      runCatching { dev.deleteSecret(repoScope, name) }
      runCatching { dev.deleteVariable(repoScope, "${name}_R") }
      runCatching { dev.deleteVariable(repoScope, name) }
    }
    assertTrue(dev.loadSecrets(repoScope).none { it.name == name })
  }

  /** Org secrets and variables; needs the `intellij-dev-org` organization from `.github/scripts/seed-gitea.sh` (owner intellij-dev, reviewer in a write team). */
  @Test
  fun `organization and user level - org owners only`() = runBlocking {
    val name = "IT_${System.currentTimeMillis()}"
    val org = GiteaActionsScope.Organization("intellij-dev-org")
    assertTrue(dev.loadOrganizationPermissions("intellij-dev", org.name).isOwner)
    assertFalse(reviewer.loadOrganizationPermissions("intellij-reviewer", org.name).isOwner)
    try {
      assertTrue(dev.putSecret(org, name, "first", null), "created")
      assertFalse(dev.putSecret(org, name, "second", null), "overwritten")
      for (scope in listOf(org, GiteaActionsScope.User)) {
        dev.createVariable(scope, name, "v", "integration test")
        assertEquals("v", dev.loadVariables(scope).single { it.name == name }.data)
        val duplicate = assertThrows(GiteaHttpStatusError::class.java) { runBlocking { dev.createVariable(scope, name, "v", null) } }
        assertEquals(GiteaHttpStatusError.Kind.CONFLICT, duplicate.kind)
      }
      assertTrue(dev.loadSecrets(org).any { it.name == name })
      // members can't even read: no read-only org level for non-owners
      val notOwner = assertThrows(GiteaHttpStatusError::class.java) { runBlocking { reviewer.loadVariables(org) } }
      assertEquals(GiteaHttpStatusError.Kind.FORBIDDEN, notOwner.kind)
    }
    finally {
      runCatching { dev.deleteSecret(org, name) }
      for (scope in listOf(org, GiteaActionsScope.User)) {
        runCatching { dev.deleteVariable(scope, name) }
      }
    }
  }
}
