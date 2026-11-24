package de.hendrikfrahmann.giteaplugin.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import de.hendrikfrahmann.giteaplugin.util.GiteaBundle

/** `Settings | Tools | Gitea | MCP`; child of [GiteaGeneralSettingsConfigurable]. */
class GiteaMcpSettingsConfigurable : BoundConfigurable(GiteaBundle.message("settings.mcp.display.name"), HELP_TOPIC) {
  override fun createPanel(): DialogPanel {
    val settings = GiteaSettings.getInstance()
    return panel {
      mcpGroup(settings)
    }
  }

  /** Gitea tools for AI assistants connected to the IDE's MCP server; both switches off by default. */
  private fun Panel.mcpGroup(settings: GiteaSettings) {
    group(GiteaBundle.message("settings.mcp.group")) {
      lateinit var tools: Cell<JBCheckBox>
      row {
        tools = checkBox(GiteaBundle.message("settings.mcp.tools"))
          .bindSelected({ settings.mcpToolsEnabled }, { settings.mcpToolsEnabled = it })
          .comment(GiteaBundle.message("settings.mcp.tools.comment"))
      }
      indent {
        row {
          checkBox(GiteaBundle.message("settings.mcp.write.tools"))
            .bindSelected({ settings.mcpWriteToolsEnabled }, { settings.mcpWriteToolsEnabled = it })
            .comment(GiteaBundle.message("settings.mcp.write.tools.comment"))
        }.enabledIf(tools.selected)
      }
    }
  }

  companion object {
    const val HELP_TOPIC: String = "settings.gitea.mcp"
  }
}
