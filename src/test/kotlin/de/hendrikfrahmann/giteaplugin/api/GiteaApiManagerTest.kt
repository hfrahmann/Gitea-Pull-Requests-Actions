package de.hendrikfrahmann.giteaplugin.api

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.api.request.getCurrentUser
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/** The manager's client is built from platform services (proxy, certificates), so this needs an application. */
class GiteaApiManagerTest : BasePlatformTestCase() {
  fun `test client from manager talks to server with credentials and user agent`() {
    MockWebServer().use { mock ->
      mock.start()
      mock.enqueue(MockResponse.Builder().body("""{"id":3,"login":"intellij-dev"}""").build())
      mock.enqueue(MockResponse.Builder().body("""{"version":"28.1.0"}""").build())
      val server = GiteaServerPath(mock.url("/").toString())
      val api = GiteaApiManager.getInstance().getClient(server, GiteaCredentials.AccessToken("pat"))

      runBlocking {
        assertEquals("intellij-dev", api.getCurrentUser().login)
        assertTrue(api.getMetadata().isSupported)
        // cached: no third request
        assertTrue(GiteaApiManager.getInstance().getUnauthenticatedClient(server).getMetadata().isSupported)
      }

      val request = mock.takeRequest()
      assertEquals("token pat", request.headers["Authorization"])
      assertTrue(request.headers["User-Agent"]!!.startsWith("Gitea-IntelliJ/"))
      assertEquals(2, mock.requestCount)
      GiteaApiManager.getInstance().invalidateMetadata(server)
    }
  }
}
