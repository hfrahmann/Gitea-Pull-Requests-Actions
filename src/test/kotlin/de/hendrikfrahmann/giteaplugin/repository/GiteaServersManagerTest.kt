package de.hendrikfrahmann.giteaplugin.repository

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.util.concurrent.atomic.AtomicInteger

class GiteaServersManagerTest : BasePlatformTestCase() {
  private val manager get() = GiteaServersManager.getInstance()
  override fun setUp() {
    super.setUp()
    manager.resetProbe()
  }

  override fun tearDown() {
    try {
      manager.resetProbe()
    }
    finally {
      super.tearDown()
    }
  }

  fun `test probe results are cached, negative ones too`() {
    val probes = AtomicInteger()
    manager.probe = { server -> probes.incrementAndGet(); server.host == "gitea.example" }
    runBlocking {
      assertEquals(GiteaServerPath("https://gitea.example"), manager.discoverServer("git@gitea.example:o/r.git"))
      assertEquals(GiteaServerPath("https://gitea.example"), manager.discoverServer("https://gitea.example/o/other.git"))
      assertNull(manager.discoverServer("https://not-gitea.example/o/r.git"))
      assertNull(manager.discoverServer("https://not-gitea.example/o/r.git"))
    }
    // gitea.example: 1 (https hit); not-gitea.example: 2 candidates (https, http), each once
    assertEquals(3, probes.get())
  }

  fun `test well-known hosts are never probed`() {
    val probes = AtomicInteger()
    manager.probe = { probes.incrementAndGet(); true }
    runBlocking {
      assertNull(manager.discoverServer("https://github.com/JetBrains/intellij-community.git"))
      assertNull(manager.discoverServer("git@gitlab.com:o/r.git"))
    }
    assertEquals(0, probes.get())
  }

  fun `test real probe against a fake gitea`() {
    MockWebServer().use { mock ->
      mock.start()
      mock.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"version":"28.1.0"}""").build())
      val url = mock.url("/o/r.git").toString()
      assertEquals(GiteaServerPath(mock.url("/").toString()), runBlocking { manager.discoverServer(url) })
      assertEquals("/api/v1/version", mock.takeRequest().target)
    }
  }
}
