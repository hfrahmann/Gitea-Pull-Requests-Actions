package de.hendrikfrahmann.giteaplugin.pullrequest

import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJobState
import com.intellij.collaboration.ui.codereview.details.data.ReviewRequestState
import com.intellij.openapi.diff.impl.patch.BinaryFilePatch
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCombinedStatusDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestChangesLoader
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestBranchesViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestDetailsViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestStatusViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaReviewerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class GiteaPullRequestDetailsTest {
  private inline fun <reified T> json(text: String): T = GiteaJson.mapper.readValue(text, T::class.java)

  private fun pr(extra: String = "") = json<GiteaPullRequestDTO>("""{"id":1,"number":7,"title":"t","state":"open",
    "user":{"id":3,"login":"intellij-dev"},
    "head":{"ref":"feature/x","sha":"aaaa1111bbbb","repo_id":1},"base":{"ref":"main","sha":"cccc","repo_id":1}$extra}""")

  private fun review(user: String, state: String, at: String, dismissed: Boolean = false) = json<GiteaReviewDTO>(
    """{"id":1,"user":{"id":9,"login":"$user"},"state":"$state","dismissed":$dismissed,"submitted_at":"$at"}""")

  @Test
  fun `gitea diffs parse into file patches, binaries and renames included`() {
    val diff = """
      diff --git a/changes/5.txt b/changes/5.txt
      new file mode 100644
      index 0000000..a3cac35
      --- /dev/null
      +++ b/changes/5.txt
      @@ -0,0 +1 @@
      +change 5 for search
      diff --git a/src/Old.kt b/src/New.kt
      similarity index 90%
      rename from src/Old.kt
      rename to src/New.kt
      index 16415fc..1f0832d 100644
      --- a/src/Old.kt
      +++ b/src/New.kt
      @@ -1 +1 @@
      -a
      +b
      diff --git a/logo.png b/logo.png
      new file mode 100644
      index 0000000..e69de29
      Binary files /dev/null and b/logo.png differ
    """.trimIndent() + "\n"
    val patches = GiteaPullRequestChangesLoader.parsePatches(diff)
    assertEquals(listOf("changes/5.txt", "src/New.kt", "logo.png"), patches.map { it.afterName })
    assertNull(patches[0].beforeName.takeIf { (patches[0] as TextFilePatch).isNewFile.not() })
    assertEquals("src/Old.kt", patches[1].beforeName)
    assertTrue(patches[2] is BinaryFilePatch || (patches[2] as? TextFilePatch)?.hunks?.isEmpty() == true)
    assertTrue(GiteaPullRequestChangesLoader.parsePatches("").isEmpty())
  }

  @Test
  fun `request state`() {
    assertEquals(ReviewRequestState.OPENED, GiteaPullRequestDetailsViewModel.requestState(pr()))
    assertEquals(ReviewRequestState.DRAFT, GiteaPullRequestDetailsViewModel.requestState(pr(""","draft":true""")))
    assertEquals(ReviewRequestState.MERGED, GiteaPullRequestDetailsViewModel.requestState(pr(""","state":"closed","merged":true""")))
    assertEquals(ReviewRequestState.CLOSED, GiteaPullRequestDetailsViewModel.requestState(pr(""","state":"closed"""")))
  }

  @Test
  fun `reviewer states use the latest review and pending requests`() {
    val pr = pr(""","requested_reviewers":[{"id":5,"login":"carol"}]""")
    val states = GiteaPullRequestDetailsViewModel.reviewerStates(pr, listOf(
      review("alice", "REQUEST_CHANGES", "2026-10-16T10:00:00Z"),
      review("alice", "APPROVED", "2026-10-16T11:00:00Z"),
      review("alice", "COMMENT", "2026-10-16T12:00:00Z"), // a comment doesn't undo the approval
      review("bob", "APPROVED", "2026-10-16T10:00:00Z", dismissed = true),
      review("bob", "COMMENT", "2026-10-16T11:00:00Z"),
      review("intellij-dev", "COMMENT", "2026-10-16T11:00:00Z"), // the author is no reviewer
      review("dave", "PENDING", "2026-10-16T11:00:00Z"),
    )).mapKeys { it.key.login }
    assertEquals(mapOf("alice" to GiteaReviewerState.APPROVED, "bob" to GiteaReviewerState.COMMENTED, "carol" to GiteaReviewerState.PENDING), states)
  }

  @Test
  fun `ci job links keep Gitea's subpath once`() {
    val status = json<GiteaCombinedStatusDTO>("""{"statuses":[
      {"id":1,"status":"success","context":"CI","target_url":"/gitea/o/r/actions/runs/13/jobs/25"},
      {"id":2,"status":"success","context":"abs","target_url":"http://internal:3000/gitea/o/r/actions/runs/14/jobs/26"}]}""")
    val jobs = GiteaPullRequestStatusViewModel.ciJobs(status, GiteaServerPath("https://proxy.example.com/git"), URI("http://internal:3000/gitea/"))
    assertEquals("https://proxy.example.com/git/o/r/actions/runs/13/jobs/25", jobs[0].detailsUrl)
    assertEquals("https://proxy.example.com/git/o/r/actions/runs/14/jobs/26", jobs[1].detailsUrl)
  }

  @Test
  fun `ci jobs from commit statuses with relative links`() {
    val status = json<GiteaCombinedStatusDTO>("""{"state":"failure","statuses":[
      {"id":1,"status":"failure","context":"CI / build (push)","target_url":"/intellij-dev/plugin-playground/actions/runs/13/jobs/25"},
      {"id":2,"status":"success","context":"ext","target_url":"https://ci.example.com/1"},
      {"id":3,"status":"pending","context":"slow","target_url":""}]}""")
    val jobs = GiteaPullRequestStatusViewModel.ciJobs(status, GiteaServerPath("https://git.example.com/gitea"))
    assertEquals(listOf(CodeReviewCIJobState.FAILED, CodeReviewCIJobState.SUCCESS, CodeReviewCIJobState.PENDING), jobs.map { it.status })
    assertEquals("https://git.example.com/gitea/intellij-dev/plugin-playground/actions/runs/13/jobs/25", jobs[0].detailsUrl)
    // external CI keeps its host
    assertEquals("https://ci.example.com/1", jobs[1].detailsUrl)
    assertNull(jobs[2].detailsUrl)
    assertTrue(GiteaPullRequestStatusViewModel.ciJobs(null, GiteaServerPath("https://x")).isEmpty())
  }

  @Test
  fun `ci checks carry Gitea's status text, not the name twice`() {
    val status = json<GiteaCombinedStatusDTO>("""{"statuses":[
      {"id":1,"status":"success","context":"CI / build (push)","description":"Successful in 1m2s"},
      {"id":2,"status":"failure","context":"ext","description":" "},
      {"id":3,"status":"pending","description":"Waiting to run"}]}""")
    val checks = GiteaPullRequestStatusViewModel.ciChecks(status, GiteaServerPath("https://git.example.com"))
    assertEquals(listOf("Successful in 1m2s", null, null), checks.map { it.description })
    assertEquals("Waiting to run", checks[2].job.name)
  }

  @Test
  fun `source branch names`() {
    assertEquals("feature/x", GiteaPullRequestBranchesViewModel.sourceBranchName(pr()))
    val fork = pr().copy(head = pr().head!!.copy(repoId = 2, repo = json("""{"id":2,"name":"r","full_name":"bob/r","owner":{"id":7,"login":"bob"}}""")))
    assertEquals("bob:feature/x", GiteaPullRequestBranchesViewModel.sourceBranchName(fork))
    val gone = pr().copy(head = pr().head!!.copy(ref = "refs/pull/7/head"))
    assertEquals("aaaa1111", GiteaPullRequestBranchesViewModel.sourceBranchName(gone))
  }
}
