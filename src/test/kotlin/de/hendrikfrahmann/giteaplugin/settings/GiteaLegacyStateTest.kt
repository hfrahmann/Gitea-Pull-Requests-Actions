package de.hendrikfrahmann.giteaplugin.settings

import com.intellij.openapi.components.State
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaPersistentAccounts
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaProjectDefaultAccountHolder
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestSeenState
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestViewedState
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsPersistentFiltersHistory
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectSettings
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path

class GiteaLegacyStateTest : BasePlatformTestCase() {
  private lateinit var file: Path

  override fun setUp() {
    super.setUp()
    file = Files.createTempFile("gitea", ".xml")
  }

  override fun tearDown() {
    try {
      Files.deleteIfExists(file)
    }
    finally {
      super.tearDown()
    }
  }

  fun `test component names are unique to the plugin`() {
    val classes = listOf(
      GiteaSettings::class, GiteaPersistentAccounts::class, GiteaProjectDefaultAccountHolder::class, GiteaProjectSettings::class,
      GiteaPullRequestsPersistentFiltersHistory::class, GiteaPullRequestViewedState::class, GiteaPullRequestSeenState::class,
    )
    for (cls in classes) {
      val state = cls.java.getAnnotation(State::class.java)
      assertTrue(state.name, state.name.startsWith("de.hendrikfrahmann.giteaplugin."))
      assertTrue(state.storages.none { it.value == GiteaLegacyState.LEGACY_FILE })
    }
  }

  fun `test imports settings and accounts saved by this plugin`() {
    writeLegacy(ownSettings(), ownAccounts())

    val settings = readSettings()!!
    assertTrue(settings.cloneUsingSsh)
    assertEquals(42, settings.runsPageSize)
    assertEquals(GiteaLocalRunner.BINARY, settings.localRunner)
    val accounts = readAccounts()!!.accounts
    assertEquals(listOf("abc"), accounts.map { it.id })
    assertEquals("john", accounts.single().name)
    assertEquals("https://gitea.example.com", accounts.single().server.uri)
  }

  fun `test settings with only defaults are imported as defaults`() {
    writeLegacy(component(GiteaSettings.LEGACY_COMPONENT_NAME, null), null)
    assertNotNull(readSettings())
  }

  fun `test does not import settings with an option this plugin does not have`() {
    val settings = ownSettings().addContent(option("someOtherPluginOption", "x"))
    writeLegacy(settings, null)
    assertNull(readSettings())
  }

  fun `test does not import settings with a value this plugin cannot read`() {
    for ((name, value) in listOf("runsPageSize" to "many", "localRunner" to "PODMAN", "cloneUsingSsh" to "maybe")) {
      writeLegacy(component(GiteaSettings.LEGACY_COMPONENT_NAME, null).addContent(option(name, value)), null)
      assertNull("$name=$value", readSettings())
    }
  }

  fun `test does not import accounts of another structure`() {
    val foreign = component(GiteaPersistentAccounts.LEGACY_COMPONENT_NAME, null).addContent(
      Element("option").setAttribute("name", "accounts").addContent(
        Element("GiteaAccount").setAttribute("id", "abc").setAttribute("login", "john").setAttribute("url", "https://gitea.example.com"),
      ),
    )
    writeLegacy(null, foreign)
    assertNull(readAccounts())
  }

  fun `test missing file, missing component or broken file import nothing`() {
    Files.delete(file)
    assertNull(readSettings())
    writeLegacy(null, ownAccounts())
    assertNull(readSettings())
    Files.writeString(file, "<application><component")
    assertNull(readSettings())
  }

  private fun readSettings() = GiteaLegacyState.read(GiteaSettings.LEGACY_COMPONENT_NAME, GiteaSettings.SettingsState::class.java, file)

  private fun readAccounts() =
    GiteaLegacyState.read(GiteaPersistentAccounts.LEGACY_COMPONENT_NAME, GiteaPersistentAccounts.AccountsState::class.java, file)

  private fun ownSettings(): Element = component(
    GiteaSettings.LEGACY_COMPONENT_NAME,
    GiteaSettings.SettingsState().apply {
      cloneUsingSsh = true
      runsPageSize = 42
      localRunner = GiteaLocalRunner.BINARY
    },
  )

  private fun ownAccounts(): Element = component(
    GiteaPersistentAccounts.LEGACY_COMPONENT_NAME,
    GiteaPersistentAccounts.AccountsState().apply {
      accounts = mutableListOf(GiteaAccount("john", GiteaServerPath("https://gitea.example.com"), "abc"))
    },
  )

  /** `<component name=…>` with [state] as the platform saves it. */
  private fun component(name: String, state: Any?): Element {
    val component = Element("component").setAttribute("name", name)
    state?.let(GiteaLegacyState::serialize)?.children?.toList()?.forEach { component.addContent(it.detach()) }
    return component
  }

  private fun option(name: String, value: String) = Element("option").setAttribute("name", name).setAttribute("value", value)

  private fun writeLegacy(settings: Element?, accounts: Element?) {
    val root = Element("application")
    listOfNotNull(settings, accounts).forEach { root.addContent(it) }
    JDOMUtil.write(root, file)
  }
}
