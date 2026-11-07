package de.hendrikfrahmann.giteaplugin

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GiteaUnloadVetoerTest : BasePlatformTestCase() {
  fun `test unloading this plugin is vetoed`() {
    val descriptor = PluginManagerCore.getPlugin(GiteaUnloadVetoer.PLUGIN_ID)
    assertNotNull("Gitea plugin descriptor not found", descriptor)
    assertNotNull(GiteaUnloadVetoer().vetoPluginUnload(descriptor!!))
  }

  fun `test unloading other plugins is not vetoed`() {
    val descriptor = PluginManagerCore.getPlugin(PluginId.getId("Git4Idea"))
    assertNotNull("Git4Idea descriptor not found", descriptor)
    assertNull(GiteaUnloadVetoer().vetoPluginUnload(descriptor!!))
  }
}
