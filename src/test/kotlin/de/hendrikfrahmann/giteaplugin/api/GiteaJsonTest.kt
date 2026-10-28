package de.hendrikfrahmann.giteaplugin.api

import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionJobsDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaActionRunsDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaBranchDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaChangedFileDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCombinedStatusDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCommentDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaCommitDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaPullRequestDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaRepositoryDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewCommentDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaSecretDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaServerVersionDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaTimelineEventDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaVariableDTO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** DTO mapping against real responses captured from Gitea 28.1 (src/test/resources/fixtures). */
class GiteaJsonTest {
  private fun fixture(name: String): String =
    javaClass.getResource("/fixtures/gitea-28.1/$name")!!.readText()

  private inline fun <reified T> value(name: String): T = GiteaJson.fromJson(fixture(name))

  private inline fun <reified T> list(name: String): List<T> =
    GiteaJson.mapper.readValue(fixture(name), GiteaJson.listType(T::class.java))

  @Test
  fun `dates with offset and nulls for empty lists`() {
    val pr = GiteaJson.fromJson<GiteaPullRequestDTO>(
      """{"id":1,"number":2,"title":"t","state":"open","labels":null,"assignees":null,"mergeable":null,
         |"created_at":"2026-10-16T16:59:07+02:00","merged_at":null,"some_new_field":{"x":1}}""".trimMargin()
    )
    assertEquals(Instant.parse("2026-10-16T14:59:07Z"), pr.createdAt!!.toInstant())
    assertEquals(emptyList<Any>(), pr.labels)
    assertEquals(emptyList<Any>(), pr.assignees)
    assertNull(pr.mergeable)
    assertNull(pr.mergedAt)
  }

  @Test
  fun `zero date of gitea parses`() {
    val user = GiteaJson.fromJson<GiteaUserDTO>("""{"id":1,"login":"u","last_login":"0001-01-01T00:00:00Z"}""")
    assertNotNull(user.lastLogin)
  }

  @Test
  fun `serialization uses snake case and skips nulls`() {
    assertEquals("""{"name":"X","data":"v","owner_id":0,"repo_id":0}""", GiteaJson.toJsonString(GiteaVariableDTO("X", "v")))
  }

  @Test
  fun `server and user`() {
    assertEquals("28.1.0", value<GiteaServerVersionDTO>("version.json").version)
    val user = value<GiteaUserDTO>("user.json")
    assertEquals("intellij-dev", user.login)
    assertFalse(user.isAdmin)
  }

  @Test
  fun `repository with permissions and merge settings`() {
    val repo = value<GiteaRepositoryDTO>("repository.json")
    assertEquals("intellij-dev/plugin-playground", repo.fullName)
    assertTrue(repo.isPrivate)
    assertTrue(repo.permissions!!.admin)
    assertTrue(repo.hasActions)
    assertTrue(repo.hasPullRequests)
    assertTrue(repo.allowMergeCommits)
    assertEquals("main", repo.defaultBranch)
    assertNotNull(repo.defaultMergeStyle)
    assertEquals("intellij-dev", repo.owner!!.login)
  }

  @Test
  fun `pull request and its parts`() {
    val pr = value<GiteaPullRequestDTO>("pull.json")
    assertEquals(1, pr.number)
    assertEquals("open", pr.state)
    assertEquals("feature/greeting", pr.head!!.ref)
    assertEquals("main", pr.base!!.ref)
    assertEquals(40, pr.head.sha.length)
    assertTrue(pr.requestedReviewers.any { it.login == "intellij-reviewer" })
    assertNotNull(pr.createdAt)

    assertTrue(list<GiteaChangedFileDTO>("pull-files.json").isNotEmpty())
    val commits = list<GiteaCommitDTO>("pull-commits.json")
    assertTrue(commits.isNotEmpty())
    assertTrue(commits.all { it.commit!!.message.isNotEmpty() })
    assertTrue(list<GiteaBranchDTO>("branches.json").any { it.name == "main" })
  }

  @Test
  fun `reviews, review comments, comments and timeline`() {
    val reviews = list<GiteaReviewDTO>("reviews.json")
    assertTrue(reviews.any { it.state == "REQUEST_CHANGES" })
    val comments = list<GiteaReviewCommentDTO>("review-comments.json")
    assertTrue(comments.all { it.path.isNotEmpty() && it.pullRequestReviewId != 0L })
    assertTrue(list<GiteaCommentDTO>("comments.json").isNotEmpty())

    val timeline = list<GiteaTimelineEventDTO>("timeline.json")
    assertTrue(timeline.any { it.type == "review" && it.reviewId != 0L })
    assertTrue(timeline.any { it.type == "pull_push" && it.body!!.contains("commit_ids") })
  }

  @Test
  fun `commit status`() {
    val status = value<GiteaCombinedStatusDTO>("combined-status.json")
    assertNotNull(status.state)
    assertEquals(status.totalCount.toInt(), status.statuses.size)
  }

  @Test
  fun `actions`() {
    val runs = value<GiteaActionRunsDTO>("action-runs.json")
    assertTrue(runs.totalCount > 0)
    assertTrue(runs.workflowRuns.all { it.id > 0 && it.status != null })
    val jobs = value<GiteaActionJobsDTO>("action-jobs.json")
    assertTrue(jobs.jobs.isNotEmpty())
    assertTrue(jobs.jobs.flatMap { it.steps }.all { it.name.isNotEmpty() })
    assertTrue(list<GiteaSecretDTO>("secrets.json").any { it.name == "DEMO_SECRET" })
    assertTrue(list<GiteaVariableDTO>("variables.json").any { it.name == "DEMO_VAR" })
  }
}
