package de.hendrikfrahmann.giteaplugin.actions

import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJob
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJobLog
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogLine
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogSection
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaRun
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaStep
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflowDispatch
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunDTO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Date

/** Actions data without a server: run mapping, status, job log sections, `workflow_dispatch` inputs. */
class GiteaActionsDataTest {
  @Test
  fun `pull request runs get their number from pull_requests or the ref, push runs their branch from the path`() {
    // trimmed from GET …/actions/runs on 28.1: PR runs have no head_branch
    val pr = GiteaJson.fromJson<GiteaActionRunDTO>("""{"id":24,"run_number":24,"run_attempt":1,"path":"ci.yml@refs/pull/6/head",
      "event":"pull_request","status":"completed","conclusion":"failure","head_sha":"0e66e73","pull_requests":[{"id":297,"number":6}],
      "started_at":"2026-10-22T19:20:15+02:00","completed_at":"2026-10-22T19:20:18+02:00"}""")
    val run = GiteaRun.from(pr)
    assertEquals(6L, run.pullRequestNumber)
    assertEquals("ci.yml", run.workflowFile)
    assertEquals("refs/pull/6/head", run.ref)
    assertNull(run.branch)
    assertEquals(GiteaActionStatus.FAILURE, run.status)
    assertEquals(3000L, run.durationMillis())

    val noList = GiteaRun.from(pr.copy(pullRequests = emptyList()))
    assertEquals(6L, noList.pullRequestNumber)

    val push = GiteaRun.from(GiteaJson.fromJson<GiteaActionRunDTO>("""{"id":20,"path":"ci.yml@refs/heads/probe/x","event":"push",
      "status":"in_progress","started_at":"1970-01-01T01:00:00+01:00"}"""))
    assertEquals("probe/x", push.branch)
    assertNull(push.pullRequestNumber)
    assertNull(push.startedAt, "Gitea's zero dates mean 'never'")
    assertEquals(GiteaActionStatus.RUNNING, push.status)
  }

  @Test
  fun `status combines status and conclusion`() {
    assertEquals(GiteaActionStatus.QUEUED, GiteaActionStatus.of("queued", null))
    assertEquals(GiteaActionStatus.WAITING, GiteaActionStatus.of("waiting", null))
    assertEquals(GiteaActionStatus.CANCELLED, GiteaActionStatus.of("completed", "cancelled"))
    assertEquals(GiteaActionStatus.SKIPPED, GiteaActionStatus.of("completed", "skipped"))
    assertEquals(GiteaActionStatus.UNKNOWN, GiteaActionStatus.of(null, null))
    assertTrue(GiteaActionStatus.RUNNING.isActive)
    assertTrue(GiteaActionStatus.CANCELLED.isFinished)
  }

  @Test
  fun `failed jobs come first, otherwise in server order`() {
    fun job(id: Long, status: GiteaActionStatus) = GiteaJob(id, 1, 1, "j$id", status, null, emptyList(), null, null)
    val sorted = GiteaJob.sortForAttention(listOf(job(1, GiteaActionStatus.SUCCESS), job(2, GiteaActionStatus.FAILURE),
                                                   job(3, GiteaActionStatus.SUCCESS), job(4, GiteaActionStatus.FAILURE)))
    assertEquals(listOf(2L, 4L, 1L, 3L), sorted.map { it.id })
  }

  @Test
  fun `skipped jobs and jobs that never started did not run`() {
    fun job(status: GiteaActionStatus, started: Date?) = GiteaJob(1, 1, 1, "j", status, null, emptyList(), started, null)
    assertTrue(job(GiteaActionStatus.FAILURE, Date(1_000_000_000)).hasRun)
    assertFalse(job(GiteaActionStatus.SKIPPED, null).hasRun)
    assertFalse(job(GiteaActionStatus.SKIPPED, Date(1_000_000_000)).hasRun)
    assertFalse(job(GiteaActionStatus.CANCELLED, null).hasRun)
  }

  @Test
  fun `job log is split into set up, steps and complete job`() {
    // GET …/actions/jobs/50/logs on 28.1, shortened
    val text = """
      2026-10-22T17:20:18.4084802Z Gitea Action Runner(version:v5.0.0)
      2026-10-22T17:20:18.4084882Z ::group::Runner Information
      2026-10-22T17:20:18.4084888Z Runner labels: ubuntu-latest
      2026-10-22T17:20:18.4084908Z ::endgroup::
      2026-10-22T17:20:18.4105093Z Complete job name: flaky
      2026-10-22T17:20:18.4108452Z ::group::Run echo "ref=${'$'}GITHUB_REF"
      2026-10-22T17:20:18.4108542Z echo "ref=${'$'}GITHUB_REF"
      2026-10-22T17:20:18.4108677Z ::endgroup::
      2026-10-22T17:20:18.4119846Z ref=refs/pull/6/head
      2026-10-22T17:20:18.4120114Z ::error::intentional failure
      2026-10-22T17:20:18.4122478Z ##[error]Process completed with exit code 1.
      2026-10-22T17:20:18.4122892Z Cleaning up container for job flaky
      2026-10-22T17:20:18.4124974Z Job failed
    """.trimIndent() + "\n"
    val step = GiteaStep(0, "Fails on feature branches", GiteaActionStatus.FAILURE, null, null)
    val log = GiteaJobLog.parse(text, listOf(step))

    assertEquals(11, log.lines.size, "::endgroup:: lines are dropped")
    assertEquals(GiteaLogLine.Kind.GROUP, log.lines[1].kind)
    assertEquals("Runner Information", log.lines[1].text)
    assertEquals(listOf(1..2, 4..5), log.groups)
    assertEquals(GiteaLogLine.Kind.ERROR, log.lines[7].kind)
    assertEquals("intentional failure", log.lines[7].text)
    assertEquals(GiteaLogLine.Kind.ERROR, log.lines[8].kind)
    assertEquals("Process completed with exit code 1.", log.lines[8].text)
    assertTrue(log.lines.all { it.timestamp != null })

    assertEquals(listOf(GiteaLogSection.Type.SET_UP, GiteaLogSection.Type.STEP, GiteaLogSection.Type.COMPLETE), log.sections.map { it.type })
    assertEquals(0..3, log.sections[0].lines)
    assertEquals(4..8, log.sections[1].lines)
    assertEquals(step, log.sections[1].step)
    assertEquals("Fails on feature branches", log.sections[1].title)
    assertEquals(9..10, log.sections[2].lines)
  }

  @Test
  fun `skipped steps are not matched to run groups and an open group ends with the log`() {
    val text = """
      2026-10-22T17:20:17.7894388Z ::group::Run first
      2026-10-22T17:20:17.8164805Z one
      2026-10-22T17:20:17.8164805Z ::endgroup::
      2026-10-22T17:20:17.8439414Z ::group::Run third
      2026-10-22T17:20:17.8439532Z still running
    """.trimIndent()
    val steps = listOf(GiteaStep(0, "First", GiteaActionStatus.SUCCESS, null, null),
                       GiteaStep(1, "Second", GiteaActionStatus.SKIPPED, null, null),
                       GiteaStep(2, "Third", GiteaActionStatus.RUNNING, null, null))
    val log = GiteaJobLog.parse(text, steps)
    assertEquals(listOf("First", "Third"), log.sections.map { it.title })
    assertEquals(listOf(0..1, 2..3), log.groups)
  }

  @Test
  fun `workflow_dispatch inputs are read from the on section`() {
    val yaml = """
      name: Deploy
      "on":
        push:
          branches: [ main ]
        workflow_dispatch:   # manual
          inputs:
            environment:
              description: 'Where to deploy'
              required: true
              type: choice
              options:
                - staging
                - "production"
            dry-run:
              type: boolean
              default: true
            notes:
              description: |
                Free text,
                two lines
      jobs:
        deploy:
          runs-on: ubuntu-latest
          steps:
            - name: Say it
              run: echo "${'$'}{{ inputs.environment }}"
    """.trimIndent()
    val dispatch = GiteaWorkflowDispatch.parse(yaml)!!
    assertEquals(listOf("environment", "dry-run", "notes"), dispatch.inputs.map { it.name })
    val env = dispatch.inputs[0]
    assertEquals(GiteaWorkflowDispatch.Type.CHOICE, env.type)
    assertEquals(listOf("staging", "production"), env.options)
    assertTrue(env.required)
    assertEquals("Where to deploy", env.description)
    assertEquals(GiteaWorkflowDispatch.Type.BOOLEAN, dispatch.inputs[1].type)
    assertEquals("true", dispatch.inputs[1].default)
    assertFalse(dispatch.inputs[1].required)
    assertEquals("Free text,\ntwo lines", dispatch.inputs[2].description)
  }

  @Test
  fun `trigger lists and scalars without inputs`() {
    assertEquals(emptyList<Any>(), GiteaWorkflowDispatch.parse("on: workflow_dispatch\njobs: {}")!!.inputs)
    assertEquals(emptyList<Any>(), GiteaWorkflowDispatch.parse("on: [push, workflow_dispatch]")!!.inputs)
    assertEquals(emptyList<Any>(), GiteaWorkflowDispatch.parse("on:\n  - push\n  - workflow_dispatch\n")!!.inputs)
    assertEquals(emptyList<Any>(), GiteaWorkflowDispatch.parse("on:\n  workflow_dispatch:\n  push:\n")!!.inputs)
    assertNull(GiteaWorkflowDispatch.parse("on:\n  push:\n    branches: [main]\n"))
    assertNull(GiteaWorkflowDispatch.parse("on: push"))
    assertNull(GiteaWorkflowDispatch.parse("not yaml: [unclosed"))
  }
}
