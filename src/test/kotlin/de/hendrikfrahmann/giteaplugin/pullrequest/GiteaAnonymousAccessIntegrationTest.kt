package de.hendrikfrahmann.giteaplugin.pullrequest

import de.hendrikfrahmann.giteaplugin.GiteaTestEnv
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.isGiteaUnauthorized
import de.hendrikfrahmann.giteaplugin.api.request.GiteaPullRequestListQuery
import de.hendrikfrahmann.giteaplugin.api.request.getRepository
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestDiff
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestReviews
import de.hendrikfrahmann.giteaplugin.api.request.loadPullRequestsPage
import de.hendrikfrahmann.giteaplugin.api.request.loadReviewComments
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.api.request.loadTimeline
import de.hendrikfrahmann.giteaplugin.api.request.renderMarkdown
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.http.HttpClient

/**
 * What an anonymous connection can read on the dev Gitea: the public `public-playground` (`.github/scripts/seed-public.sh`)
 * yes, the private `plugin-playground` no (404); `POST /markdown` needs a login. `./gradlew integrationTest`.
 */
@Tag("integration")
class GiteaAnonymousAccessIntegrationTest {
  private val env = GiteaTestEnv.load()
  private val api: GiteaApi = GiteaApiImpl(GiteaServerPath(env.getProperty("GITEA_URL")), HttpClient.newHttpClient(), null,
                                           "Gitea-IntelliJ/test", { it.loadServerMetadata() })
  private val public = GiteaRepositoryPath("intellij-dev", "public-playground")

  @Test
  fun `public repository is readable without credentials`() = runBlocking {
    val repository = try {
      api.getRepository(public)
    }
    catch (e: GiteaHttpStatusError) {
      assumeTrue(e.kind != GiteaHttpStatusError.Kind.NOT_FOUND, "run .github/scripts/seed-public.sh first")
      throw e
    }
    assertFalse(repository.permissions?.push ?: false, "anonymous must not have push")

    val pr = api.loadPullRequestsPage(public, GiteaPullRequestListQuery(state = "all"), 1, 10).body.first()
    assertTrue(api.loadPullRequestDiff(public, pr.number).contains("src/Main.kt"))
    assertTrue(api.loadTimeline(public, pr.number).any { it.type == "comment" })
    val review = api.loadPullRequestReviews(public, pr.number).first { it.commentsCount > 0 }
    assertTrue(api.loadReviewComments(public, pr.number, review.id).isNotEmpty())
  }

  @Test
  fun `private repository is not found and markdown needs a login`() = runBlocking {
    val error = assertThrows(GiteaHttpStatusError::class.java) { runBlocking { api.getRepository(GiteaRepositoryPath("intellij-dev", "plugin-playground")) } }
    assertEquals(GiteaHttpStatusError.Kind.NOT_FOUND, error.kind)

    val markdown = assertThrows(GiteaHttpStatusError::class.java) { runBlocking { api.renderMarkdown("**x**", public) } }
    assertTrue(markdown.isGiteaUnauthorized)
  }
}
