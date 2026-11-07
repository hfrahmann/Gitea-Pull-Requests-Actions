package de.hendrikfrahmann.giteaplugin

import com.intellij.ide.plugins.CannotUnloadPluginException
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.extensions.PluginId

/**
 * The plugin installs without a restart, but unloading it at runtime (disable, uninstall, update) is vetoed, so the IDE
 * asks for a restart instead. Unloading fails once pull request tabs were open: the platform keeps references to them
 * (Reopen Closed Tab, editor tab toolbar) and the plugin would be left half unloaded.
 *
 * Registered in plugin.xml as an application listener, so it is in place before the IDE checks whether it can unload.
 */
internal class GiteaUnloadVetoer : DynamicPluginListener {
  override fun checkUnloadPlugin(pluginDescriptor: IdeaPluginDescriptor) {
    if (pluginDescriptor.pluginId == PLUGIN_ID) {
      throw CannotUnloadPluginException("Gitea Pull Requests & Actions needs an IDE restart to be unloaded")
    }
  }

  internal companion object {
    val PLUGIN_ID: PluginId = PluginId.getId("de.hendrikfrahmann.giteaplugin")
  }
}
