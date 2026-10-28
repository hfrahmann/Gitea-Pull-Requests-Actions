package de.hendrikfrahmann.giteaplugin.pullrequest

import de.hendrikfrahmann.giteaplugin.GiteaTestEnv
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.getPullRequest
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestShort
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestState
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestsLoader
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue.UserFilterValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.http.HttpClient

/**
 * Against `intellij-dev/pr-list-playground` seeded by `.github/scripts/seed-pr-list.sh` (220 PRs, numbering rules in the script).
 * `./gradlew integrationTest`; skipped without gitea-test.local.env.
 */
@Tag("integration")
class GiteaPullRequestsLoaderIntegrationTest {
  private val env = GiteaTestEnv.load()
  private val repo = GiteaRepositoryPath("intellij-dev", "pr-list-playground")

  private lateinit var api: GiteaApiImpl

  private fun loader(tokenKey: String, login: String): GiteaPullRequestsLoader {
    api = GiteaApiImpl(GiteaServerPath(env.getProperty("GITEA_URL")), HttpClient.newHttpClient(),
                           { GiteaCredentials.AccessToken(env.getProperty(tokenKey)) }, "Gitea-IntelliJ/test", { it.loadServerMetadata() })
    return GiteaPullRequestsLoader(api, repo, login)
  }

  private suspend fun GiteaPullRequestsLoader.loadAll(filter: GiteaPullRequestsFiltersValue): List<GiteaPullRequestShort> {
    val pager = createPager(filter)
    val all = mutableListOf<GiteaPullRequestShort>()
    while (pager.hasMore) {
      all += pager.loadMore()
    }
    return all
  }

  @Test
  fun `all pages and state filters`() = runBlocking {
    val loader = loader("INTELLIJ_DEV_TOKEN", "intellij-dev")
    val all = loader.loadAll(GiteaPullRequestsFiltersValue.EMPTY)
    assumeTrue(all.size >= 220, "run .github/scripts/seed-pr-list.sh first")
    assertEquals(all.size, all.map { it.number }.distinct().size)

    val merged = loader.loadAll(GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.MERGED))
    // seeded PRs (#1…#220) follow the seed rule; later ones are merged by probes and GiteaMergeIntegrationTest
    assertTrue(merged.isNotEmpty() && merged.filter { it.number <= 220 }.all { it.number % 13 == 0L })
    val closed = loader.loadAll(GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.CLOSED))
    assertTrue(closed.isNotEmpty() && closed.none { it.state == GiteaPullRequestState.MERGED })
    assertTrue(all.filter { it.number % 10 == 0L && it.number <= 220 }.all { it.isDraft })
  }

  @Test
  fun `review requested from me and search`() = runBlocking {
    val loader = loader("INTELLIJ_REVIEWER_TOKEN", "intellij-reviewer")
    val me = UserFilterValue("intellij-reviewer", null)
    val requested = loader.loadAll(GiteaPullRequestsFiltersValue(state = GiteaPullRequestState.OPEN, reviewer = me))
    assertTrue(requested.isNotEmpty())
    // seeded on number % 6 == 1, but manual tests add more: check each hit against the PR itself
    for (pr in requested.take(10)) {
      val details = api.getPullRequest(repo, pr.number)
      assertTrue(details.requestedReviewers.any { it.login == "intellij-reviewer" }, "#${pr.number}")
    }

    val avatar = loader.loadAll(GiteaPullRequestsFiltersValue(searchQuery = "avatar", author = me))
    assertTrue(avatar.isNotEmpty() && avatar.all { "avatar" in it.title && it.author?.login == "intellij-reviewer" })
  }
}
