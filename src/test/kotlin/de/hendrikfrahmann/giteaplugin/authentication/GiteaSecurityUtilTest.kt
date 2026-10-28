package de.hendrikfrahmann.giteaplugin.authentication

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.api.GiteaApiManager
import de.hendrikfrahmann.giteaplugin.api.GiteaCredentials
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.expectThrows
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/** Login validation as used by the token dialog and OAuth login. */
class GiteaSecurityUtilTest : BasePlatformTestCase() {
  private lateinit var mock: MockWebServer
  private lateinit var server: GiteaServerPath

  override fun setUp() {
    super.setUp()
    mock = MockWebServer()
    mock.start()
    server = GiteaServerPath(mock.url("/").toString())
  }

  override fun tearDown() {
    try {
      GiteaApiManager.getInstance().invalidateMetadata(server)
      mock.close()
    }
    finally {
      super.tearDown()
    }
  }

  private fun json(body: String, code: Int = 200) =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

  private fun validate(requiredUsername: String? = null, unique: Boolean = true) = runBlocking {
    GiteaSecurityUtil.validateAndResolveUser(server, GiteaCredentials.AccessToken("t"), requiredUsername, { _, _ -> unique })
  }

  private fun loginError(block: () -> Unit): String = expectThrows<GiteaLoginException>(block).message!!

  fun `test valid token`() {
    mock.enqueue(json("""{"version":"28.1.0"}"""))
    mock.enqueue(json("""{"id":3,"login":"intellij-dev"}"""))
    assertEquals("intellij-dev", validate().login)
  }

  fun `test old server is rejected`() {
    mock.enqueue(json("""{"version":"1.26.0"}"""))
    assertTrue(loginError { validate() }.contains("1.26.0"))
  }

  fun `test not a gitea server`() {
    mock.enqueue(MockResponse.Builder().code(404).body("<html>nope</html>").build())
    assertTrue(loginError { validate() }.contains("No Gitea API"))
  }

  fun `test invalid token`() {
    mock.enqueue(json("""{"version":"28.1.0"}"""))
    mock.enqueue(json("""{"message":"invalid username, password or token"}""", 401))
    assertTrue(loginError { validate() }.contains("invalid"))
  }

  fun `test username mismatch and duplicate account`() {
    mock.enqueue(json("""{"version":"28.1.0"}"""))
    mock.enqueue(json("""{"id":3,"login":"intellij-dev"}"""))
    assertTrue(loginError { validate(requiredUsername = "someone-else") }.contains("intellij-dev"))
    mock.enqueue(json("""{"id":3,"login":"intellij-dev"}"""))
    assertTrue(loginError { validate(unique = false) }.contains("already"))
  }

  fun `test connection tester reports user version and scopes`() {
    mock.enqueue(json("""{"version":"28.1.0"}"""))
    mock.enqueue(json("""{"id":3,"login":"intellij-dev"}"""))
    mock.enqueue(json("""{"id":1,"name":"t","scopes":["read:repository","read:user"]}"""))
    val outcome = runBlocking { GiteaConnectionTester.test(GiteaApiManager.getInstance().getClient(server, GiteaCredentials.AccessToken("t"))) }
    outcome as GiteaConnectionTester.Outcome.Success
    assertEquals("intellij-dev", outcome.login)
    assertEquals("28.1.0", outcome.version)
    assertEquals(listOf("write:repository", "write:issue", "read:organization"), outcome.missingScopes)
    assertTrue(outcome.message.contains("Missing scopes"))
  }

  fun `test connection tester failure for unreachable server`() {
    val unreachable = GiteaServerPath("http://127.0.0.1:1")
    val outcome = runBlocking { GiteaConnectionTester.test(GiteaApiManager.getInstance().getClient(unreachable, GiteaCredentials.AccessToken("t"))) }
    assertTrue(outcome.toString(), outcome is GiteaConnectionTester.Outcome.Failure)
  }
}
