package de.hendrikfrahmann.giteaplugin.pullrequest

import com.intellij.diff.util.Side
import com.intellij.openapi.diff.impl.patch.PatchHunkUtil
import com.intellij.openapi.diff.impl.patch.PatchReader
import de.hendrikfrahmann.giteaplugin.api.GiteaJson
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewCommentDTO
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaReviewDTO
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaReviewThread
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.diff.GiteaReviewThreadComponentFactory
import git4idea.changes.GitFileHistory
import git4idea.changes.GitTextFilePatchWithHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GiteaReviewThreadTest {
  private fun review(id: Long, state: String, user: String = "intellij-reviewer") =
    GiteaJson.mapper.readValue("""{"id":$id,"state":"$state","user":{"id":4,"login":"$user"}}""", GiteaReviewDTO::class.java)

  private fun comment(id: Long, review: Long, user: String, at: String, position: Int, original: Int = 0, resolver: String? = null,
                      path: String = "probe/wp08.txt", commit: String = "58298823") =
    GiteaJson.mapper.readValue("""{"id":$id,"body":"c$id","user":{"id":1,"login":"$user"},"pull_request_review_id":$review,
      "path":"$path","position":$position,"original_position":$original,"commit_id":"$commit","created_at":"$at"
      ${resolver?.let { ""","resolver":{"id":2,"login":"$it"}""" } ?: ""}}""", GiteaReviewCommentDTO::class.java)

  @Test
  fun `threads group replies of one review per line, oldest first`() {
    // as verified on 28.1 (PR #221): the author's reply was added to the reviewer's review 85 at the same line
    val reviews = listOf(review(85, "COMMENT"), review(86, "PENDING", "intellij-dev"), review(87, "REQUEST_REVIEW"))
    val comments = mapOf(
      85L to listOf(
        comment(622, 85, "intellij-dev", "2026-10-21T10:05:00Z", position = 2),
        comment(615, 85, "intellij-reviewer", "2026-10-21T10:00:00Z", position = 2, resolver = "intellij-reviewer"),
        comment(617, 85, "intellij-reviewer", "2026-10-21T10:01:00Z", position = 3),
        comment(630, 85, "intellij-reviewer", "2026-10-21T10:02:00Z", position = 0, original = 1, path = "README.md"),
        comment(631, 85, "intellij-reviewer", "2026-10-21T10:03:00Z", position = 0, original = 0, path = "README.md"),
      ),
      86L to listOf(comment(640, 86, "intellij-dev", "2026-10-21T11:00:00Z", position = 2)),
    )
    val threads = GiteaReviewThread.build(reviews, comments)

    assertEquals(4, threads.size, "file-level comment skipped, REQUEST_REVIEW ignored")
    val readmeOld = threads.single { it.path == "README.md" }
    assertEquals(Side.LEFT, readmeOld.side)
    assertEquals(1, readmeOld.line)

    val line2 = threads.filter { it.path == "probe/wp08.txt" && it.line == 2 }
    assertEquals(2, line2.size, "the pending review's comment is a thread of its own")
    val submitted = line2.single { !it.isPending }
    assertEquals(listOf(615L, 622L), submitted.comments.map { it.id })
    assertEquals(615L, submitted.id)
    assertTrue(submitted.isResolved)
    assertEquals(Side.RIGHT, submitted.side)
    assertTrue(line2.single { it.isPending }.comments.single().isPending)
    assertFalse(threads.single { it.line == 3 }.isResolved)
  }

  @Test
  fun `suggestion blocks`() {
    val body = "Better:\n```suggestion\nval x = 1\nval y = 2\n```\nthanks"
    assertEquals("val x = 1\nval y = 2\n", GiteaReviewThreadComponentFactory.suggestionOf(body))
    assertEquals(null, GiteaReviewThreadComponentFactory.suggestionOf("```kotlin\nval x = 1\n```"))
  }

  /**
   * A comment on an unchanged line: Gitea stores the line's blame commit (here "old", from the base branch), which the PR's
   * file history doesn't contain – the line counts in the review's head (verified on 28.1).
   */
  @Test
  fun `new-side threads map from the review's head, then the comment's commit, then the PR head`() {
    val patch = PatchReader(PatchHunkUtil.createPatchFromHunk("src/Hello.kt", """
      @@ -1,3 +1,4 @@
      -fun main() {
      -    println("Hello Gitea")
      +fun main(args: Array<String>) {
      +    val name = args.firstOrNull() ?: "Gitea"
      +    println("Hello ${'$'}name")
       }
    """.trimIndent())).readTextPatches().single().apply {
      beforeVersionId = "base"
      afterVersionId = "head"
    }
    val history = object : GitFileHistory {
      private val commits = listOf("base", "head")
      override fun findStartCommit() = "base"
      override fun findFirstParent(commitSha: String) = commits.getOrNull(commits.indexOf(commitSha) - 1)
      override fun contains(commitSha: String, filePath: String) = commitSha in commits
      override fun compare(commitSha1: String, commitSha2: String) = commits.indexOf(commitSha1).compareTo(commits.indexOf(commitSha2))
      override fun getPatchesBetween(parent: String, child: String) = listOf(patch).filter { parent == "base" && child == "head" }
    }
    val withHistory = GitTextFilePatchWithHistory(patch, true, history)
    fun thread(reviewCommit: String?) = GiteaReviewThread("src/Hello.kt", Side.RIGHT, 4, "old", 1, emptyList(), null, reviewCommitSha = reviewCommit)

    assertEquals(Side.RIGHT to 3, thread("head").mapToDiff(withHistory, "base"))
    assertEquals(Side.RIGHT to 3, thread(null).mapToDiff(withHistory, "base", headSha = "head"))
    // a commit the PR doesn't know and nothing to fall back to: outdated, not attached to some line
    assertNull(thread(null).mapToDiff(withHistory, "base"))
  }
}
