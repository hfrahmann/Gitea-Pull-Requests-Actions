package de.hendrikfrahmann.giteaplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.SlowOperations
import de.hendrikfrahmann.giteaplugin.actions.GiteaActionsDownloads
import java.nio.file.Files

/** Tests can forbid slow work on the EDT and report leaks; blocking helpers refuse to run on the EDT. */
class GiteaThreadingTest : BasePlatformTestCase() {
  fun `test slow operations on the EDT fail tests inside forbidSlowOperations, disposer debug is on`() {
    assertTrue("platform tests run on the EDT", ApplicationManager.getApplication().isDispatchThread)
    expectThrows<Throwable> { forbidSlowOperations { SlowOperations.assertSlowOperationsAreAllowed() } }
    SlowOperations.assertSlowOperationsAreAllowed() // allowed again afterwards
    assertTrue("idea.disposer.debug=on: leaks report where they were created", Disposer.isDebugDisposerOn())
  }

  fun `test blocking helpers refuse the EDT`() {
    val zip = Files.createTempFile("gitea-threading", ".zip")
    try {
      expectThrows<Throwable> { GiteaActionsDownloads.unzip(zip) }
    }
    finally {
      Files.deleteIfExists(zip)
    }
  }
}
