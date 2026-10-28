package de.hendrikfrahmann.giteaplugin.api

import de.hendrikfrahmann.giteaplugin.GiteaTestEnv
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentUser
import de.hendrikfrahmann.giteaplugin.api.request.getRepository
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpClient

/** Against a test Gitea (`.github/scripts/ci-gitea.sh`, see README.md): `./gradlew integrationTest`. Skipped without gitea-test.local.env. */
@Tag("integration")
class GiteaApiIntegrationTest {
  private val env = GiteaTestEnv.load()
  private val server = GiteaServerPath(env.getProperty("GITEA_URL"))
  private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  private fun api(credentials: GiteaCredentials?) =
    GiteaApiImpl(server, httpClient, credentials?.let { c -> { c } }, "Gitea-IntelliJ/test", { it.loadServerMetadata() })

  @Test
  fun `version and current user`() = runBlocking {
    val metadata = api(null).getMetadata()
    assertTrue(metadata.isSupported, "dev Gitea is ${metadata.version}")

    val api = api(GiteaCredentials.AccessToken(env.getProperty("INTELLIJ_DEV_TOKEN")))
    assertEquals("intellij-dev", api.getCurrentUser().login)
    val repo = api.getRepository(GiteaRepositoryPath("intellij-dev", "plugin-playground"))
    assertTrue(repo.permissions!!.admin)
  }

  @Test
  fun `invalid token is unauthorized`() {
    val error = assertThrows<GiteaHttpStatusError> { runBlocking { api(GiteaCredentials.AccessToken("invalid")).getCurrentUser() } }
    assertEquals(GiteaHttpStatusError.Kind.UNAUTHORIZED, error.kind)
  }
}
