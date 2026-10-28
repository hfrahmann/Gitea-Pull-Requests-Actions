package de.hendrikfrahmann.giteaplugin.actions

import de.hendrikfrahmann.giteaplugin.actions.secrets.GiteaRunner
import de.hendrikfrahmann.giteaplugin.actions.secrets.GiteaRunnerLevel
import de.hendrikfrahmann.giteaplugin.actions.secrets.GiteaRunnerState
import de.hendrikfrahmann.giteaplugin.actions.secrets.GiteaRunners
import de.hendrikfrahmann.giteaplugin.actions.secrets.GiteaSecretNames
import de.hendrikfrahmann.giteaplugin.api.GiteaApi
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.api.GiteaRepositoryPath
import de.hendrikfrahmann.giteaplugin.api.GiteaServerMetadataCache
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.dto.GiteaUserDTO
import de.hendrikfrahmann.giteaplugin.api.request.GiteaActionsScope
import de.hendrikfrahmann.giteaplugin.api.request.createVariable
import de.hendrikfrahmann.giteaplugin.api.request.loadSecrets
import de.hendrikfrahmann.giteaplugin.api.request.putSecret
import de.hendrikfrahmann.giteaplugin.api.request.updateVariable
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.http.HttpClient

/** Secrets and variables without a server: name rules and request shapes. */
class GiteaSecretsTest {
  private lateinit var mock: MockWebServer
  private val repository = GiteaRepositoryPath("intellij-dev", "plugin-playground")
  private val scope = GiteaActionsScope.Repository(repository)

  @BeforeEach
  fun setUp() {
    mock = MockWebServer()
    mock.start()
  }

  @AfterEach
  fun tearDown() {
    mock.close()
  }

  private fun api(): GiteaApi = GiteaApiImpl(GiteaServerPath(mock.url("/").toString()), HttpClient.newHttpClient(),
                                             { GiteaCredentials.AccessToken("secret-pat") }, "Gitea-IntelliJ/test",
                                             GiteaServerMetadataCache()::getMetadata)

  @Test
  fun `names follow gitea's rules`() {
    assertNull(GiteaSecretNames.validate("DEPLOY_KEY"))
    assertNull(GiteaSecretNames.validate("_x1"))
    assertNull(GiteaSecretNames.validate("lower_case"), "Gitea stores it upper-case")
    assertNotNull(GiteaSecretNames.validate(""))
    assertNotNull(GiteaSecretNames.validate("1ABC"))
    assertNotNull(GiteaSecretNames.validate("A-B"))
    assertNotNull(GiteaSecretNames.validate("GITEA_TOKEN"))
    assertNotNull(GiteaSecretNames.validate("github_token"))
  }

  @Test
  fun `secret put reports whether it was created and sends data and description`() = runBlocking {
    val api = api()
    mock.enqueue(MockResponse.Builder().code(201).build())
    assertTrue(api.putSecret(scope, "DEPLOY_KEY", "v4lue", "for deploys"))
    val request = mock.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("/api/v1/repos/intellij-dev/plugin-playground/actions/secrets/DEPLOY_KEY", request.target)
    assertEquals("""{"data":"v4lue","description":"for deploys"}""", request.body?.utf8())

    mock.enqueue(MockResponse.Builder().code(204).build())
    assertFalse(api.putSecret(scope, "DEPLOY_KEY", "new", null))
    assertEquals("""{"data":"new"}""", mock.takeRequest().body?.utf8())
  }

  @Test
  fun `variables are created with value and renamed only for a different name`() = runBlocking {
    val api = api()
    mock.enqueue(MockResponse.Builder().code(201).build())
    api.createVariable(scope, "ENV", "staging", null)
    val create = mock.takeRequest()
    assertEquals("POST", create.method)
    assertEquals("""{"value":"staging"}""", create.body?.utf8())

    mock.enqueue(MockResponse.Builder().code(204).build())
    api.updateVariable(scope, "ENV", "TARGET_ENV", "prod", "where")
    assertEquals("""{"value":"prod","description":"where","name":"TARGET_ENV"}""", mock.takeRequest().body?.utf8())

    mock.enqueue(MockResponse.Builder().code(204).build())
    api.updateVariable(scope, "ENV", "env", "prod", null)
    assertEquals("""{"value":"prod"}""", mock.takeRequest().body?.utf8(), "names are case-insensitive: no rename")
  }

  @Test
  fun `organization and user levels use their own paths`() = runBlocking {
    repeat(3) { mock.enqueue(MockResponse.Builder().code(201).build()) }
    api().putSecret(GiteaActionsScope.Organization("acme"), "KEY", "v", null)
    api().createVariable(GiteaActionsScope.Organization("acme"), "ENV", "v", null)
    api().createVariable(GiteaActionsScope.User, "ENV", "v", null)
    assertEquals(listOf("/api/v1/orgs/acme/actions/secrets/KEY", "/api/v1/orgs/acme/actions/variables/ENV", "/api/v1/user/actions/variables/ENV"),
                 List(3) { mock.takeRequest().target })
  }

  @Test
  fun `runners skip the repository level for non-admins`() = runBlocking {
    mock.enqueue(MockResponse.Builder().code(200).body("""{"total_count":0,"runners":[]}""").build())
    val user = GiteaUserDTO(4, "intellij-reviewer")
    assertEquals(emptyList<GiteaRunner>(), GiteaRunners.load(api(), repository, user, user, repositoryAdmin = false))
    assertEquals("/api/v1/user/actions/runners", mock.takeRequest().target)
  }

  @Test
  fun `secret list is read page by page`() = runBlocking {
    mock.enqueue(MockResponse.Builder().code(200).addHeader("X-Total-Count", "1")
                   .body("""[{"name":"DEMO_SECRET","description":"","created_at":"2026-10-16T17:05:04+02:00"}]""").build())
    assertEquals(listOf("DEMO_SECRET"), api().loadSecrets(scope).map { it.name })
    assertTrue(mock.takeRequest().target.contains("page=1"))
  }

  @Test
  fun `runners come from every readable level, the most specific level wins`() = runBlocking {
    val runner = { id: Int, name: String, extra: String -> """{"id":$id,"name":"$name",$extra"labels":[{"id":1,"name":"ubuntu-latest","type":"custom"}]}""" }
    // repository, organization (owner is an org), instance (site admin)
    mock.enqueue(MockResponse.Builder().code(200).body("""{"total_count":1,"runners":[${runner(1, "repo-runner", "\"status\":\"online\",")}]}""").build())
    mock.enqueue(MockResponse.Builder().code(200).body("""{"total_count":1,"runners":[${runner(2, "org-runner", "\"status\":\"active\",\"busy\":true,")}]}""").build())
    mock.enqueue(MockResponse.Builder().code(200).body("""{"total_count":3,"runners":[${runner(1, "repo-runner", "")},${runner(2, "org-runner", "")},${
      runner(3, "shared", "\"status\":\"offline\",\"ephemeral\":true,")}]}""").build())
    val org = GiteaUserDTO(10, "acme", type = "Organization")
    val admin = GiteaUserDTO(3, "intellij-dev", isAdmin = true)

    val runners = GiteaRunners.load(api(), repository, org, admin)
    assertEquals(listOf("repo-runner" to GiteaRunnerLevel.REPOSITORY, "org-runner" to GiteaRunnerLevel.ORGANIZATION, "shared" to GiteaRunnerLevel.INSTANCE),
                 runners.map { it.name to it.level })
    assertEquals(listOf(GiteaRunnerState.ONLINE, GiteaRunnerState.BUSY, GiteaRunnerState.OFFLINE), runners.map { it.state })
    assertEquals(listOf("ubuntu-latest"), runners[0].labels)
    assertTrue(runners[2].ephemeral)
    assertEquals(listOf("/api/v1/repos/intellij-dev/plugin-playground/actions/runners", "/api/v1/orgs/acme/actions/runners", "/api/v1/admin/actions/runners"),
                 List(3) { mock.takeRequest().target })
  }

  @Test
  fun `an organization the user doesn't administer is skipped, the repository level is required`() = runBlocking {
    mock.enqueue(MockResponse.Builder().code(200).body("""{"total_count":0,"runners":[]}""").build())
    mock.enqueue(MockResponse.Builder().code(403).body("""{"message":"forbidden"}""").build())
    assertEquals(emptyList<GiteaRunner>(), GiteaRunners.load(api(), repository, GiteaUserDTO(10, "acme", type = "Organization"), GiteaUserDTO(4, "intellij-reviewer")))

    mock.enqueue(MockResponse.Builder().code(403).body("""{"message":"user should be the owner of the repo"}""").build())
    assertThrows(GiteaHttpStatusError::class.java) { runBlocking { GiteaRunners.load(api(), repository, null, null) } }
    Unit
  }
}
