package de.hendrikfrahmann.giteaplugin.mcp.server

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.diagnostic.logger
import de.hendrikfrahmann.giteaplugin.api.GiteaHttpStatusError
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpEnvironment
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpException
import de.hendrikfrahmann.giteaplugin.mcp.GiteaMcpProjectEnvironment
import de.hendrikfrahmann.giteaplugin.settings.GiteaSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext

private val LOG = logger<GiteaMcpToolCalls>()

/**
 * Runs a tool of the MCP toolsets: the project of the call (the MCP server picks it from the client's working directory),
 * errors as tool errors with a text for the assistant. Everything else is in the services of the `mcp` package.
 */
internal object GiteaMcpToolCalls {
  /** Listed for new client sessions only while the first switch is on; calls check both switches again. */
  fun toolsEnabled(): Boolean = GiteaSettings.getInstance().mcpToolsEnabled

  @Suppress("TooGenericExceptionCaught") // anything else still becomes a tool error the assistant can read
  suspend fun <S> call(service: (GiteaMcpEnvironment) -> S, tool: suspend S.() -> String): String {
    val project = currentCoroutineContext().project
    try {
      return service(GiteaMcpProjectEnvironment(project)).tool()
    }
    catch (e: CancellationException) {
      throw e
    }
    catch (e: McpExpectedError) {
      throw e
    }
    catch (e: GiteaMcpException) {
      mcpFail(e.message ?: "Failed")
    }
    catch (e: GiteaHttpStatusError) {
      // "<request> failed with HTTP 404: <Gitea's message>"
      mcpFail(e.message ?: "Gitea answered HTTP ${e.statusCode}")
    }
    catch (e: Exception) {
      LOG.info("Gitea MCP tool failed", e)
      mcpFail("Gitea request failed: ${e.message ?: e.javaClass.simpleName}")
    }
  }
}
