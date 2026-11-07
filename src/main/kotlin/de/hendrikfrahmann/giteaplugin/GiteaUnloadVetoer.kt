package de.hendrikfrahmann.giteaplugin

import com.intellij.ide.plugins.DynamicPluginVetoer
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle
import org.jetbrains.annotations.Nls

/**
 * The plugin installs without a restart, but unloading it at runtime (disable, uninstall, update) is vetoed, so the IDE
 * asks for a restart instead. Unloading fails once pull request tabs were open: the platform keeps references to them
 * (Reopen Closed Tab, editor tab toolbar) and the plugin would be left half unloaded.
 */
internal class GiteaUnloadVetoer : DynamicPluginVetoer {
  override fun vetoPluginUnload(pluginDescriptor: IdeaPluginDescriptor): @Nls String? =
    if (pluginDescriptor.pluginId == PLUGIN_ID) GiteaBundle.message("plugin.unload.restart.required") else null

  internal companion object {
    val PLUGIN_ID: PluginId = PluginId.getId("de.hendrikfrahmann.giteaplugin")
  }
}
