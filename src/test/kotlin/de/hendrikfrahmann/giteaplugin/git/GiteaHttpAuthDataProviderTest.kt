package de.hendrikfrahmann.giteaplugin.git

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.AuthData
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountManager
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaProjectDefaultAccountHolder
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import git4idea.remote.GitHttpAuthDataProvider
import kotlinx.coroutines.runBlocking

class GiteaHttpAuthDataProviderTest : BasePlatformTestCase() {
  private val server = GiteaServerPath("http://127.0.0.1:3000")
  private val dev = GiteaAccount("intellij-dev", server)
  private val reviewer = GiteaAccount("intellij-reviewer", server)
  private val provider = GiteaHttpAuthDataProvider()
  private val manager get() = GiteaAccountManager.getInstance()

  override fun tearDown() {
    try {
      runBlocking { manager.updateAccounts(emptyMap()) }
      GiteaSettings.getInstance().hostMappingsText = ""
      GiteaProjectDefaultAccountHolder.getInstance(project).account = null
    }
    finally {
      super.tearDown()
    }
  }

  fun `test provider is registered and silent`() {
    assertTrue(GitHttpAuthDataProvider.EP_NAME.extensionList.any { it is GiteaHttpAuthDataProvider && it.isSilent })
    assertTrue(GitHttpAuthDataProvider.EP_NAME.extensionList.any { it is GiteaInteractiveHttpAuthDataProvider && !it.isSilent })
  }

  fun `test single account gives login and token`() {
    runBlocking { manager.updateAccount(dev, GiteaAccountCredentials.Token("pat-dev")) }
    val authData = provider.getAuthData(project, "http://127.0.0.1:3000")!!
    assertEquals("intellij-dev", authData.login)
    assertEquals("pat-dev", authData.password)
    assertNull(provider.getAuthData(project, "https://github.com"))
    assertNull(provider.getAuthData(project, "http://127.0.0.1:3000", "someone-else"))
  }

  fun `test several accounts need the default or the login`() {
    runBlocking {
      manager.updateAccount(dev, GiteaAccountCredentials.Token("pat-dev"))
      manager.updateAccount(reviewer, GiteaAccountCredentials.Token("pat-reviewer"))
    }
    assertNull(provider.getAuthData(project, "http://127.0.0.1:3000"))
    assertEquals("pat-reviewer", provider.getAuthData(project, "http://127.0.0.1:3000", "intellij-reviewer")!!.password)
    GiteaProjectDefaultAccountHolder.getInstance(project).account = dev
    assertEquals("pat-dev", provider.getAuthData(project, "http://127.0.0.1:3000")!!.password)
  }

  fun `test host mapping connects root url host to the account server`() {
    runBlocking { manager.updateAccount(dev, GiteaAccountCredentials.Token("pat-dev")) }
    assertNull(provider.getAuthData(project, "https://one.intranet"))
    GiteaSettings.getInstance().hostMappingsText = "one.intranet=http://127.0.0.1:3000"
    assertEquals("pat-dev", provider.getAuthData(project, "https://one.intranet")!!.password)
  }

  fun `test rejected token is not offered again`() {
    runBlocking { manager.updateAccount(dev, GiteaAccountCredentials.Token("pat-dev")) }
    val authData = provider.getAuthData(project, "http://127.0.0.1:3000")!!
    provider.forgetPassword(project, "http://127.0.0.1:3000", authData)
    assertNull(provider.getAuthData(project, "http://127.0.0.1:3000"))
    // New credentials for the account lift the block. The platform's failure holder listens for them in a coroutine it
    // only starts in ignoreAccount(); an update before that coroutine subscribed is missed (seen on slow CI), so the
    // update is repeated until the block is gone.
    val deadline = System.currentTimeMillis() + 10_000
    var unblocked: AuthData? = null
    while (unblocked == null && System.currentTimeMillis() < deadline) {
      runBlocking { manager.updateAccount(dev, GiteaAccountCredentials.Token("pat-dev-2")) }
      repeat(10) {
        if (unblocked == null) {
          PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
          Thread.sleep(20)
          unblocked = provider.getAuthData(project, "http://127.0.0.1:3000")
        }
      }
    }
    assertEquals("pat-dev-2", unblocked!!.password)
  }
}
