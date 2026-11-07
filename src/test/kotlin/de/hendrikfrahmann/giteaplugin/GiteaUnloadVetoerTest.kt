package de.hendrikfrahmann.giteaplugin

import com.intellij.ide.plugins.CannotUnloadPluginException
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GiteaUnloadVetoerTest : BasePlatformTestCase() {
  fun `test unloading this plugin is vetoed`() {
    val descriptor = PluginManagerCore.getPlugin(GiteaUnloadVetoer.PLUGIN_ID)
    assertNotNull("Gitea plugin descriptor not found", descriptor)
    assertThrows(CannotUnloadPluginException::class.java) { GiteaUnloadVetoer().checkUnloadPlugin(descriptor!!) }
  }

  fun `test unloading other plugins is not vetoed`() {
    val descriptor = PluginManagerCore.getPlugin(PluginId.getId("Git4Idea"))
    assertNotNull("Git4Idea descriptor not found", descriptor)
    GiteaUnloadVetoer().checkUnloadPlugin(descriptor!!)
  }
}
