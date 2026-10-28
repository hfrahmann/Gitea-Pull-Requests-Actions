package de.hendrikfrahmann.giteaplugin.authentication

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import de.hendrikfrahmann.giteaplugin.api.GiteaApiImpl
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentUser
import de.hendrikfrahmann.giteaplugin.api.request.loadServerMetadata
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaCredentialsRefreshException
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaMissingCredentialsException
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaPersistentAccounts
import de.hendrikfrahmann.giteaplugin.authentication.accounts.getClient
import de.hendrikfrahmann.giteaplugin.expectThrows
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.net.http.HttpClient

class GiteaAccountManagerTest : BasePlatformTestCase() {
  private lateinit var mock: MockWebServer
  private lateinit var server: GiteaServerPath
  private val manager get() = GiteaAccountManager.getInstance()

  override fun setUp() {
    super.setUp()
    mock = MockWebServer()
    mock.start()
    server = GiteaServerPath(mock.url("/").toString())
    val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    GiteaOAuthService.getInstance().apiFactory = { GiteaApiImpl(it, httpClient, null, "test", { api -> api.loadServerMetadata() }) }
  }

  override fun tearDown() {
    try {
      runBlocking { manager.updateAccounts(emptyMap()) }
      GiteaOAuthService.getInstance().apiFactory = { GiteaApiManager.getInstance().getUnauthenticatedClient(it) }
      mock.close()
    }
    finally {
      super.tearDown()
    }
  }

  private fun json(body: String, code: Int = 200) =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

  fun `test accounts are persisted without secrets`() {
    val account = GiteaAccount("intellij-dev", server)
    runBlocking { manager.updateAccount(account, GiteaAccountCredentials.Token("secret-pat")) }

    assertEquals(setOf(account), manager.accountsState.value)
    val xml = XmlSerializer.serialize(service<GiteaPersistentAccounts>().state)
    val text = com.intellij.openapi.util.JDOMUtil.write(xml)
    assertTrue(text, text.contains("intellij-dev") && text.contains(server.uri) && text.contains(account.id))
    assertFalse(text.contains("secret-pat"))

    // Load into a fresh repository: same account (id, name, server)
    val restored = GiteaPersistentAccounts().apply { loadState(XmlSerializer.deserialize(xml, GiteaPersistentAccounts.AccountsState::class.java)) }
    val restoredAccount = restored.accounts.single()
    assertEquals(account, restoredAccount)
    assertEquals("intellij-dev", restoredAccount.name)
    assertEquals(server, restoredAccount.server)

    assertEquals("secret-pat", runBlocking { manager.getAndRefreshCredentials(account) }.accessToken)
    assertFalse(manager.isAccountUnique(server, "INTELLIJ-DEV"))
    assertTrue(manager.isAccountUnique(server, "other"))
    assertTrue(manager.isAccountUnique(GiteaServerPath("https://other.example.com"), "intellij-dev"))
  }

  fun `test expired oauth token is refreshed and stored`() {
    val account = GiteaAccount("intellij-dev", server)
    runBlocking { manager.updateAccount(account, GiteaAccountCredentials.OAuth("old-at", "old-rt", "client", expiresAt = 0)) }
    mock.enqueue(json("""{"access_token":"new-at","token_type":"bearer","expires_in":3600,"refresh_token":"new-rt"}"""))

    val credentials = runBlocking { manager.getAndRefreshCredentials(account) } as GiteaAccountCredentials.OAuth
    assertEquals("new-at", credentials.accessToken)
    assertEquals("new-rt", (runBlocking { manager.findCredentials(account) } as GiteaAccountCredentials.OAuth).refreshToken)
    // Still valid → no second refresh
    runBlocking { manager.getAndRefreshCredentials(account) }
    assertEquals(1, mock.requestCount)
  }

  fun `test failed refresh and missing credentials`() {
    val account = GiteaAccount("intellij-dev", server)
    runBlocking { manager.updateAccount(account, GiteaAccountCredentials.OAuth("at", "rt", "client", expiresAt = 0)) }
    mock.enqueue(json("""{"error":"invalid_grant"}""", 400))
    expectThrows<GiteaCredentialsRefreshException> { runBlocking { manager.getAndRefreshCredentials(account) } }

    expectThrows<GiteaMissingCredentialsException> {
      runBlocking { manager.getAndRefreshCredentials(GiteaAccount("nobody", server)) }
    }
  }

  fun `test 401 refreshes the oauth token once and retries`() {
    val account = GiteaAccount("intellij-dev", server)
    runBlocking { manager.updateAccount(account, GiteaAccountCredentials.OAuth("revoked-at", "rt", "client", expiresAt = Long.MAX_VALUE / 2)) }
    mock.enqueue(json("""{"message":"token is invalid"}""", 401))
    mock.enqueue(json("""{"access_token":"fresh-at","expires_in":3600,"refresh_token":"rt-2"}"""))
    mock.enqueue(json("""{"id":3,"login":"intellij-dev"}"""))

    val user = runBlocking { GiteaApiManager.getInstance().getClient(account).getCurrentUser() }
    assertEquals("intellij-dev", user.login)
    assertEquals("Bearer revoked-at", mock.takeRequest().headers["Authorization"])
    assertEquals("/login/oauth/access_token", mock.takeRequest().target)
    assertEquals("Bearer fresh-at", mock.takeRequest().headers["Authorization"])
  }

  fun `test 401 with a personal access token is not retried`() {
    val account = GiteaAccount("intellij-dev", server)
    runBlocking { manager.updateAccount(account, GiteaAccountCredentials.Token("bad")) }
    mock.enqueue(json("""{"message":"invalid"}""", 401))
    val error = expectThrows<de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError> {
      runBlocking { GiteaApiManager.getInstance().getClient(account).getCurrentUser() }
    }
    assertEquals(401, error.statusCode)
    assertEquals(1, mock.requestCount)
  }
}
