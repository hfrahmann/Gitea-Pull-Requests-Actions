package de.hendrikfrahmann.giteaplugin.actions.workflow

import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaJobLog
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogLine
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaLogSection
import de.hendrikfrahmann.giteaplugin.actions.workflow.run.GiteaLocalRunOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `gitea-runner exec --json` output sorted into jobs, steps and sections (lines trimmed from a run of runner 5.0). */
class GiteaLocalRunOutputTest {
  private fun job(jobId: String, name: String, matrix: String, fields: String) =
    """{"dryrun":false,"job":"CI/$name","jobID":"$jobId","matrix":$matrix,"time":"2026-11-07T19:08:38Z",$fields}"""

  /** Lines of the matrix job `v: a`, `v: b` and of the job `fail` (whose name the runner pads). */
  private fun a(fields: String) = job("build", "Build & test-1", """{"v":"a"}""", fields)
  private fun b(fields: String) = job("build", "Build & test-2", """{"v":"b"}""", fields)
  private fun fail(fields: String) = job("fail", "fail          ", "{}", fields)

  private fun raw(msg: String) = """"level":"info","msg":"$msg","raw_output":true"""
  private fun main(step: String, number: Int, fields: String) = """$fields,"stage":"Main","step":"$step","stepNumber":$number"""
  private fun checkout(fields: String) = main("actions/checkout@v4", 0, fields)
  private fun sayHi(fields: String) = main("Say hi", 1, fields)
  private fun failStep(fields: String) = main("Fail", 0, fields)

  private val lines = listOf(
    """time="2026-11-07T19:08:37Z" level=info msg="Using the only detected workflow event: push"""",
    """time="2026-11-07T19:08:37Z" level=warning msg="unable to get git repo (repoPath: /work): exit status 1: """",
    a(raw("::group::Starting job container")),
    a(raw("image: runner-images:ubuntu-latest")),
    a(""""level":"info","msg":"docker pull image=runner-images:ubuntu-latest""""),
    a(raw("::endgroup::")),
    a(raw("Complete job name: Build & test")),
    a(checkout(raw("::group::Run actions/checkout@v4"))),
    a(checkout(raw("::endgroup::"))),
    a(checkout(""""level":"info","msg":"docker cp src=/work"""")),
    a(checkout(""""level":"info","msg":"Success - Main actions/checkout@v4","stepResult":"success"""")),
    // a matrix sibling interleaves
    b(raw("::group::Starting job container")),
    a(sayHi(raw("""::group::Run echo \"hello a\""""))),
    a(sayHi(raw("""echo \"hello a\"""") + ""","script_line_cyan":true""")),
    a(sayHi(raw("::endgroup::"))),
    a(sayHi(raw("hello a\\n"))),
    a(sayHi(raw("::group::inner\\n"))),
    a(sayHi(raw("inside\\n"))),
    a(sayHi(raw("::endgroup::\\n"))),
    a(sayHi(""""level":"info","msg":"Success - Main Say hi","stepResult":"success"""")),
    // "if: false": the step only shows up in the runner's warnings
    a(main("Skip me", 2, """"level":"warning","msg":"unable to get git repo"""")),
    a(""""level":"info","msg":"Cleaning up container for job Build & test","stage":"Complete""""),
    a(""""level":"info","msg":"Job succeeded","stage":"Complete","jobResult":"success""""),
    fail(failStep(raw("""::group::Run echo \"::error::broken\"; exit 1"""))),
    fail(failStep(raw("::endgroup::"))),
    fail(failStep(raw("::warning::careful"))),
    fail(failStep(raw("::error::broken"))),
    fail(failStep(""""level":"info","msg":"Failure - Main Fail","stepResult":"failure"""")),
    fail(failStep(""""level":"error","msg":"##[error]Process completed with exit code 1."""")),
    fail(main("After", 1, """"level":"warning","msg":"unable to get git repo"""")),
    fail(""""level":"info","msg":"Job failed","stage":"Complete","jobResult":"failure""""),
    "Error: job 'fail' failed",
  )

  private fun output() = GiteaLocalRunOutput().apply { lines.forEach(::addLine) }

  private fun texts(log: GiteaJobLog, section: GiteaLogSection) = section.lines.map { log.lines[it].text }

  @Test
  fun `jobs are split by job id and matrix, named like Gitea names them`() {
    val jobs = output().jobs()
    assertEquals(listOf("Build & test (a)", "Build & test (b)", "fail"), jobs.map { it.name })
    assertEquals(listOf(GiteaActionStatus.SUCCESS, GiteaActionStatus.RUNNING, GiteaActionStatus.FAILURE), jobs.map { it.status })
  }

  @Test
  fun `a job's log has set up, its steps and complete job, without the runner's noise inside steps`() {
    val output = output()
    val job = output.jobs().first()
    val log = output.jobLog(job.id)
    assertEquals(listOf(GiteaLogSection.Type.SET_UP, GiteaLogSection.Type.STEP, GiteaLogSection.Type.STEP, GiteaLogSection.Type.COMPLETE),
                 log.sections.map { it.type })
    assertEquals(listOf(null, "actions/checkout@v4", "Say hi", null), log.sections.map { it.title })
    assertEquals(listOf("Starting job container", "image: runner-images:ubuntu-latest", "docker pull image=runner-images:ubuntu-latest",
                        "Complete job name: Build & test"), texts(log, log.sections[0]))
    // the step's output without the trailing line break; "docker cp" and "Success - Main" are not output
    assertEquals(listOf("Run echo \"hello a\"", "echo \"hello a\"", "hello a", "inner", "inside"), texts(log, log.sections[2]))
    assertEquals(listOf("Cleaning up container for job Build & test", "Job succeeded"), texts(log, log.sections[3]))
    // "Starting job container", "Run echo …" and the step's own "inner" group fold; the empty checkout group doesn't count
    assertEquals(listOf("Starting job container", "Run echo \"hello a\"", "inner"),
                 log.groups.filter { it.last > it.first }.map { log.lines[it.first].text })

    assertEquals(listOf(GiteaActionStatus.SUCCESS, GiteaActionStatus.SUCCESS, GiteaActionStatus.SKIPPED), job.steps.map { it.status })
  }

  @Test
  fun `errors stay in their step, steps after a failure are skipped`() {
    val output = output()
    val job = output.jobs().last()
    val log = output.jobLog(job.id)
    val step = log.sections.single { it.type == GiteaLogSection.Type.STEP }
    assertEquals("Fail", step.title)
    assertEquals(GiteaActionStatus.FAILURE, step.step?.status)
    assertEquals(listOf(GiteaLogLine.Kind.GROUP, GiteaLogLine.Kind.WARNING, GiteaLogLine.Kind.ERROR, GiteaLogLine.Kind.ERROR),
                 step.lines.map { log.lines[it].kind })
    assertEquals(listOf(GiteaActionStatus.FAILURE, GiteaActionStatus.SKIPPED), job.steps.map { it.status })
  }

  @Test
  fun `the runner's own lines form a log without sections, warnings marked`() {
    val output = output()
    val log = output.runnerLog()
    assertTrue(log.sections.isEmpty())
    assertEquals(listOf("Using the only detected workflow event: push", "unable to get git repo (repoPath: /work): exit status 1: ",
                        "Error: job 'fail' failed"), log.lines.map { it.text })
    assertEquals(GiteaLogLine.Kind.WARNING, log.lines[1].kind)
    assertNotNull(log.lines[0].timestamp)
    assertEquals(GiteaActionStatus.RUNNING, output.runnerJob("Runner").status)
  }

  @Test
  fun `jobs the process leaves unfinished end with it`() {
    val stopped = output().apply { processTerminated(exitCode = 130, stopped = true) }
    assertEquals(GiteaActionStatus.CANCELLED, stopped.jobs()[1].status)
    assertEquals(GiteaActionStatus.SUCCESS, stopped.jobs()[0].status)
    assertEquals(GiteaActionStatus.CANCELLED, stopped.runnerJob("Runner").status)

    val failed = output().apply { processTerminated(exitCode = 1, stopped = false) }
    assertEquals(GiteaActionStatus.FAILURE, failed.jobs()[1].status)
  }
}
