package de.hendrikfrahmann.giteaplugin.pullrequest

import de.hendrikfrahmann.giteaplugin.GiteaTestEnv
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.GiteaEditPullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaReactionTarget
import de.hendrikfrahmann.giteaplugin.api.request.addReaction
import de.hendrikfrahmann.giteaplugin.api.request.editPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.getIssue
import de.hendrikfrahmann.giteaplugin.api.request.getPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.labelsFlow
import de.hendrikfrahmann.giteaplugin.api.request.loadAllowedReactions
import de.hendrikfrahmann.giteaplugin.api.request.loadCombinedStatus
import de.hendrikfrahmann.giteaplugin.api.request.loadCommitDiff
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestCommits
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestDiff
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestReviews
import de.hendrikfrahmann.giteaplugin.api.request.loadReactions
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.api.request.milestonesFlow
import de.hendrikfrahmann.giteaplugin.api.request.removeReaction
import de.hendrikfrahmann.giteaplugin.api.request.removeReviewRequests
import de.hendrikfrahmann.giteaplugin.api.request.renderMarkdown
import de.hendrikfrahmann.giteaplugin.api.request.requestReviewers
import de.hendrikfrahmann.giteaplugin.api.request.searchIssues
import de.hendrikfrahmann.giteaplugin.api.request.setIssueMilestone
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestChangesLoader
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaPullRequestDetailsViewModel
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.details.model.GiteaReviewerState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.http.HttpClient

/**
 * Details requests against the dev Gitea: PR #1 of `plugin-playground` (`.github/scripts/seed-gitea.sh`: 3 commits, a
 * REQUEST_CHANGES review, failing CI) and PR #2 of `pr-list-playground` (`.github/scripts/seed-pr-list.sh`) for edits, which are
 * reverted. `./gradlew integrationTest`; skipped without gitea-test.local.env.
 */
@Tag("integration")
class GiteaPullRequestDetailsIntegrationTest {
  private val env = GiteaTestEnv.load()
  private val api = GiteaApiImpl(GiteaServerPath(env.getProperty("GITEA_URL")), HttpClient.newHttpClient(),
                                 { GiteaCredentials.AccessToken(env.getProperty("INTELLIJ_DEV_TOKEN")) }, "Gitea-IntelliJ/test",
                                 { it.loadServerMetadata() })
  private val playground = GiteaRepositoryPath("intellij-dev", "plugin-playground")
  private val prList = GiteaRepositoryPath("intellij-dev", "pr-list-playground")

  @Test
  fun `commits, diffs, reviews, status and markdown`() = runBlocking {
    val pr = api.getPullRequest(playground, 1)
    val commits = api.loadPullRequestCommits(playground, 1)
    assertTrue(commits.isNotEmpty())
    assertEquals(pr.head!!.sha, commits.first().sha, "Gitea sends newest first")
    assertTrue(commits.all { it.parents.isNotEmpty() })

    val prPatches = GiteaPullRequestChangesLoader.parsePatches(api.loadPullRequestDiff(playground, 1))
    assertEquals(listOf("src/Hello.kt"), prPatches.map { it.afterName })
    // the seed's first commit changes Hello.kt, later re-runs only add empty commits
    val firstCommitPatches = GiteaPullRequestChangesLoader.parsePatches(api.loadCommitDiff(playground, commits.last().sha))
    assertEquals(listOf("src/Hello.kt"), firstCommitPatches.map { it.afterName })

    val reviews = api.loadPullRequestReviews(playground, 1)
    val states = GiteaPullRequestDetailsViewModel.reviewerStates(pr, reviews).mapKeys { it.key.login }
    assertEquals(GiteaReviewerState.CHANGES_REQUESTED, states["intellij-reviewer"])

    val status = api.loadCombinedStatus(playground, pr.head.sha)
    assertTrue(status.statuses.isNotEmpty(), "run the playground CI once (seed-gitea.sh)")

    val html = api.renderMarkdown("**bold** #1", playground)
    assertTrue("<strong>bold</strong>" in html, html)
  }

  @Test
  fun `edit labels, assignees, milestone and reviewers, then revert`() = runBlocking {
    val before = api.getPullRequest(prList, 2)
    assumeTrue(before.state == "open", "run .github/scripts/seed-pr-list.sh first")
    val labels = api.labelsFlow(prList).toList().flatten()
    val docs = labels.first { it.name == "docs" }
    val milestone = api.milestonesFlow(prList).toList().flatten().first()
    try {
      api.editPullRequest(prList, 2, GiteaEditPullRequestRequest(labels = listOf(docs.id), assignees = listOf("intellij-reviewer"),
                                                                  milestone = milestone.id))
      api.requestReviewers(prList, 2, listOf("intellij-reviewer"))
      val edited = api.getPullRequest(prList, 2)
      assertEquals(listOf("docs"), edited.labels.map { it.name })
      assertEquals(listOf("intellij-reviewer"), edited.assignees.map { it.login })
      assertEquals(milestone.id, edited.milestone?.id)
      assertTrue(edited.requestedReviewers.any { it.login == "intellij-reviewer" })

      api.removeReviewRequests(prList, 2, listOf("intellij-reviewer"))
      api.editPullRequest(prList, 2, GiteaEditPullRequestRequest(labels = emptyList(), assignees = emptyList()))
      api.setIssueMilestone(prList, 2, null)
      val cleared = api.getPullRequest(prList, 2)
      assertTrue(cleared.labels.isEmpty() && cleared.assignees.isEmpty())
      assertNull(cleared.milestone)
      assertTrue(cleared.requestedReviewers.none { it.login == "intellij-reviewer" })
    }
    finally {
      api.editPullRequest(prList, 2, GiteaEditPullRequestRequest(labels = before.labels.map { it.id }, assignees = before.assignees.map { it.login }))
      api.setIssueMilestone(prList, 2, before.milestone?.id)
      api.requestReviewers(prList, 2, before.requestedReviewers.map { it.login })
    }
  }

  @Test
  fun `issue search, lookup by number and reactions`(): Unit = runBlocking {
    val found = api.searchIssues(prList, "avatar")
    assertTrue(found.isNotEmpty() && found.all { it.pullRequest != null }, "pr-list-playground only has PRs")
    assertEquals(2L, api.getIssue(prList, 2).number)

    assertTrue("heart" in api.loadAllowedReactions())
    val target = GiteaReactionTarget.Issue(2)
    suspend fun own() = api.loadReactions(prList, target).filter { it.content == "heart" && it.user?.login == "intellij-dev" }
    try {
      api.addReaction(prList, target, "heart")
      api.addReaction(prList, target, "heart") // a second add is a no-op
      assertEquals(1, own().size)
    }
    finally {
      api.removeReaction(prList, target, "heart")
    }
    assertTrue(own().isEmpty())
  }
}
