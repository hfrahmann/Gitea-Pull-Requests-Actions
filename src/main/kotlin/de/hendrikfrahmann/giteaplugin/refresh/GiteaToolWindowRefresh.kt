package de.hendrikfrahmann.giteaplugin.refresh

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Emits whenever this tool window is shown (opened, or brought back from hidden), for a refresh. */
fun ToolWindow.shownEvents(): Flow<Unit> = callbackFlow {
  val connection = project.messageBus.connect()
  connection.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
    override fun toolWindowShown(toolWindow: ToolWindow) {
      if (toolWindow.id == id) {
        trySend(Unit)
      }
    }
  })
  awaitClose { connection.disconnect() }
}
