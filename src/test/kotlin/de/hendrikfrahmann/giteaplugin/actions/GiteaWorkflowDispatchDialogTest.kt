package de.hendrikfrahmann.giteaplugin.actions

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflow
import de.hendrikfrahmann.giteaplugin.actions.data.GiteaWorkflowDispatch
import de.hendrikfrahmann.giteaplugin.actions.ui.GiteaWorkflowDispatchDialog
import de.hendrikfrahmann.giteaplugin.forbidSlowOperations

/** The "Run Workflow" dialog accepts filled-in required inputs and sends typed values as strings. */
class GiteaWorkflowDispatchDialogTest : BasePlatformTestCase() {
  private val yaml = """
    on:
      workflow_dispatch:
        inputs:
          greeting: { type: string }
          target:
            required: true
            type: string
          dry-run:
            type: boolean
            default: true
          log-level:
            required: true
            type: choice
            default: info
            options: [debug, info]
          retries:
            type: number
            default: 3
  """.trimIndent()

  fun `test filled required inputs pass validation`(): Unit = forbidSlowOperations {
    val dialog = GiteaWorkflowDispatchDialog(project, GiteaWorkflow("inputs.yml", "Inputs", null, false), listOf("main", "feature/greeting"),
                                             "feature/greeting", GiteaWorkflowDispatch.parse(yaml)!!)
    try {
      assertTrue("DialogWrapper disables continuous validation for DSL panels", dialog.continuousValidation())
      assertEquals(listOf("target is required"), dialog.doValidateAll().map { it.message })
      (dialog.inputComponents.getValue("target") as JBTextField).text = "test"
      assertEquals(emptyList<String>(), dialog.doValidateAll().map { it.message })
      assertEquals("feature/greeting", dialog.ref)
      assertEquals(mapOf("greeting" to "", "target" to "test", "dry-run" to "true", "log-level" to "info", "retries" to "3"), dialog.inputs)
    }
    finally {
      Disposer.dispose(dialog.disposable)
      dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }
  }
}
