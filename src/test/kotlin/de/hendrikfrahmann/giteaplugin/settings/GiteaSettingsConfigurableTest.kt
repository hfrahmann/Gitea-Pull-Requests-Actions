package de.hendrikfrahmann.giteaplugin.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.NoAutomaticReset
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.GiteaOAuthService
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccountCredentials
import de.hendrikfrahmann.giteaplugin.forbidSlowOperations

class GiteaSettingsConfigurableTest : BasePlatformTestCase() {
  fun `test accounts are under version control like github and gitlab, the options under tools with child pages`() {
    val ep = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).single { it.id == "de.hendrikfrahmann.giteaplugin.settings" }
    assertEquals("project.propVCSSupport.Mappings", ep.parentId)
    assertEquals(-500, ep.groupWeight)
    assertEquals("Gitea", ep.displayName)
    assertEmpty(Configurable.PROJECT_CONFIGURABLE.getExtensions(project).filter { it.parentId == ep.id })
    val general = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).single { it.id == "de.hendrikfrahmann.giteaplugin.settings.general" }
    assertEquals("tools", general.parentId)
    assertEquals("settings.general.display.name", general.key)
    val children = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).filter { it.parentId == general.id }
    assertEquals(listOf("settings.pull.requests.display.name", "settings.actions.display.name", "settings.mcp.display.name"),
                 children.sortedByDescending { it.groupWeight }.map { it.key })
    assertNotNull(ActionManager.getInstance().getAction("Gitea.Open.Settings"))
    assertNotNull(ActionManager.getInstance().getAction("Gitea.Open.GeneralSettings"))
    assertNotNull(ActionManager.getInstance().getAction("Gitea.Open.ActionsSettings"))
  }

  fun `test pages can be built and disposed`(): Unit = forbidSlowOperations {
    for (configurable in listOf(GiteaSettingsConfigurable(project), GiteaGeneralSettingsConfigurable(),
                                GiteaPullRequestsSettingsConfigurable(), GiteaActionsSettingsConfigurable(project),
                                GiteaMcpSettingsConfigurable())) {
      try {
        assertNotNull(configurable.createComponent())
        configurable.reset()
        assertFalse(configurable.isModified)
      }
      finally {
        configurable.disposeUIResources()
      }
    }
  }

  fun `test window focus doesn't reset the page and drop a login that is not applied yet`() {
    assertInstanceOf(GiteaSettingsConfigurable(project), NoAutomaticReset::class.java)
  }

  fun `test reset by the user discards a login that is not applied yet`() = withPendingLogin { configurable ->
    configurable.reset()
    assertEmpty(configurable.accountsModel.accounts)
  }

  private fun withPendingLogin(test: (GiteaSettingsConfigurable) -> Unit) {
    val configurable = GiteaSettingsConfigurable(project)
    try {
      configurable.createComponent()
      val account = GiteaAccount("user", GiteaServerPath("http://192.168.1.41:3000"), "id")
      configurable.accountsModel.add(account, GiteaAccountCredentials.Token("token"))
      test(configurable)
    }
    finally {
      configurable.disposeUIResources()
    }
  }

  fun `test custom oauth client ids`() {
    val settings = GiteaSettings.getInstance()
    val old = settings.oauthClientIdsText
    try {
      settings.oauthClientIdsText = "git.example.com/=abc-123\nbroken line\nhttps://other.example.com = def"
      assertEquals("abc-123", settings.oauthClientIdFor(GiteaServerPath("https://git.example.com")))
      assertEquals("def", settings.oauthClientIdFor(GiteaServerPath("https://other.example.com")))
      assertEquals(GiteaOAuthService.BUILT_IN_CLIENT_ID, settings.oauthClientIdFor(GiteaServerPath("https://gitea.com")))
    }
    finally {
      settings.oauthClientIdsText = old
    }
  }

  fun `test refresh, paging and default server options are kept in range`() {
    val settings = GiteaSettings.getInstance()
    val old = settings.state.let { Triple(it.refreshFastSeconds, it.runsPageSize, it.defaultServerUrl) }
    try {
      settings.refreshFastSeconds = 0
      assertEquals(GiteaSettings.REFRESH_FAST_RANGE.first, settings.refreshFastSeconds)
      settings.runsPageSize = 500
      assertEquals(50, settings.runsPageSize)
      settings.defaultServerUrl = ""
      assertEquals(GiteaServerPath.DEFAULT_SERVER_URL, settings.defaultServer)
      settings.defaultServerUrl = " http://127.0.0.1:3000 "
      assertEquals("http://127.0.0.1:3000", settings.defaultServer)
      settings.pauseRefreshInBackground = false
      assertFalse(settings.pauseRefreshInBackgroundState.value)
    }
    finally {
      settings.refreshFastSeconds = old.first
      settings.runsPageSize = old.second
      settings.defaultServerUrl = old.third
      settings.pauseRefreshInBackground = true
    }
  }
}
