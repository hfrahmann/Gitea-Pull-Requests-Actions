package de.hendrikfrahmann.giteaplugin.pullrequest

import de.hendrikfrahmann.giteaplugin.GiteaTestEnv
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.jsonBody
import de.hendrikfrahmann.giteaplugin.api.repoApiUri
import de.hendrikfrahmann.giteaplugin.api.request.GiteaCreatePullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.GiteaEditPullRequestRequest
import de.hendrikfrahmann.giteaplugin.api.request.createPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.editPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.findOpenPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.labelsFlow
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.api.request.milestonesFlow
import de.hendrikfrahmann.giteaplugin.api.sendIgnoringBody
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestCreateDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.util.Base64

/**
 * Creating PRs against the dev Gitea: create with metadata as draft, find it again (also next to a closed PR for the same
 * branches), duplicate refused, template from a branch, PR from the reviewer's fork of `plugin-playground`.
 * Throwaway branches in `pr-list-playground` / the fork, PRs closed afterwards. `./gradlew integrationTest`.
 */
@Tag("integration")
class GiteaPullRequestCreateIntegrationTest {
  private val env = GiteaTestEnv.load()
  private val repo = GiteaRepositoryPath("intellij-dev", "pr-list-playground")
  private val dev = api("INTELLIJ_DEV_TOKEN")
  private val reviewer = api("INTELLIJ_REVIEWER_TOKEN")
  private val stamp = System.currentTimeMillis()

  private fun api(tokenKey: String): GiteaApi =
    GiteaApiImpl(GiteaServerPath(env.getProperty("GITEA_URL")), HttpClient.newHttpClient(),
                 { GiteaCredentials.AccessToken(env.getProperty(tokenKey)) }, "Gitea-IntelliJ/test", { it.loadServerMetadata() })

  private data class NewFile(val branch: String, val new_branch: String, val message: String, val content: String)

  /** New branch [branch] from `main` of [repository] with one commit adding [path]. */
  private suspend fun GiteaApi.branchWithFile(repository: GiteaRepositoryPath, branch: String, path: String, text: String) {
    val content = Base64.getEncoder().encodeToString(text.toByteArray())
    sendIgnoringBody(request(server.repoApiUri(repository, "contents", *path.split('/').toTypedArray()))
                       .jsonBody("POST", NewFile("main", branch, "create it $stamp", content)).build())
  }

  private suspend fun GiteaApi.deleteBranch(repository: GiteaRepositoryPath, branch: String) {
    sendIgnoringBody(request(server.repoApiUri(repository, "branches", branch)).DELETE().build())
  }

  @Test
  fun `create as draft with metadata, find it, refuse a duplicate`() = runBlocking {
    val branch = "probe/create-it-$stamp"
    dev.branchWithFile(repo, branch, "probe/create-it-$stamp.txt", "hi\n")
    val label = dev.labelsFlow(repo).first().first()
    val milestone = dev.milestonesFlow(repo).first().first()
    assertNull(dev.findOpenPullRequest(repo, "main", repo, branch))

    val closed = dev.createPullRequest(repo, GiteaCreatePullRequestRequest("Create IT closed", head = branch, base = "main"))
    dev.editPullRequest(repo, closed.number, GiteaEditPullRequestRequest(state = "closed"))
    val pr = dev.createPullRequest(repo, GiteaCreatePullRequestRequest(
      title = GiteaPullRequestCreateDefaults.withDraft("Create IT", true), body = "Body", head = branch, base = "main",
      assignees = listOf("intellij-dev"), reviewers = listOf("intellij-reviewer"), labels = listOf(label.id), milestone = milestone.id))
    try {
      assertTrue(pr.draft)
      assertEquals(listOf("intellij-reviewer"), pr.requestedReviewers.map { it.login })
      assertEquals(listOf("intellij-dev"), pr.assignees.map { it.login })
      assertEquals(listOf(label.id), pr.labels.map { it.id })
      assertEquals(milestone.id, pr.milestone?.id)
      // the direct lookup may answer with the closed PR (verified on 28.1) – the fallback must still find the open one
      assertEquals(pr.number, dev.findOpenPullRequest(repo, "main", repo, branch)?.number)

      val duplicate = assertThrows(GiteaHttpStatusError::class.java) {
        runBlocking { dev.createPullRequest(repo, GiteaCreatePullRequestRequest("dup", head = branch, base = "main")) }
      }
      assertEquals(409, duplicate.statusCode)
      val nothing = assertThrows(GiteaHttpStatusError::class.java) {
        runBlocking { dev.createPullRequest(repo, GiteaCreatePullRequestRequest("nothing", head = "main", base = "main")) }
      }
      assertEquals(422, nothing.statusCode)
    }
    finally {
      dev.editPullRequest(repo, pr.number, GiteaEditPullRequestRequest(state = "closed"))
      dev.deleteBranch(repo, branch)
    }
  }

  @Test
  fun `template is read from the given branch`() = runBlocking {
    val branch = "probe/create-it-template-$stamp"
    assertNull(GiteaPullRequestCreateDefaults.loadTemplate(dev, repo, "main"))
    dev.branchWithFile(repo, branch, ".gitea/pull_request_template.md", "## Summary\n")
    try {
      assertEquals("## Summary\n", GiteaPullRequestCreateDefaults.loadTemplate(dev, repo, branch))
    }
    finally {
      dev.deleteBranch(repo, branch)
    }
  }

  @Test
  fun `PR from a fork into its upstream`() = runBlocking {
    val upstream = GiteaRepositoryPath("intellij-dev", "plugin-playground")
    val fork = GiteaRepositoryPath("intellij-reviewer", "plugin-playground")
    val branch = "probe/create-it-fork-$stamp"
    reviewer.branchWithFile(fork, branch, "probe/create-it-fork-$stamp.txt", "from the fork\n")
    val pr = reviewer.createPullRequest(upstream, GiteaCreatePullRequestRequest("Create IT fork", head = "${fork.owner}:$branch", base = "main"))
    try {
      assertEquals(fork.toString(), pr.head?.repo?.fullName)
      assertEquals(pr.number, dev.findOpenPullRequest(upstream, "main", fork, branch)?.number)
      assertNull(dev.findOpenPullRequest(upstream, "main", upstream, branch))
    }
    finally {
      reviewer.editPullRequest(upstream, pr.number, GiteaEditPullRequestRequest(state = "closed"))
      reviewer.deleteBranch(fork, branch)
    }
  }
}
