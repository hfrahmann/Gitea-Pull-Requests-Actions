package de.hendrikfrahmann.giteaplugin.authentication

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.expectThrows
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException

/** A login may fail or be cancelled, but it must not fail without telling the user. */
class GiteaLoginUtilTest : BasePlatformTestCase() {
  private val reported = mutableListOf<Throwable>()

  private fun guarded(failure: Throwable): GiteaLoginResult = guardLogin({ reported += it }) { throw failure }

  fun `test exception is reported`() {
    val failure = IOException("no route")
    assertEquals(GiteaLoginResult.Failure, guarded(failure))
    assertEquals(listOf<Throwable>(failure), reported)
  }

  fun `test class missing at runtime is reported`() {
    val failure = NoClassDefFoundError("com/fasterxml/jackson/core/JacksonException")
    assertEquals(GiteaLoginResult.Failure, guarded(failure))
    assertEquals(listOf<Throwable>(failure), reported)
  }

  fun `test cancelling is not reported`() {
    assertEquals(GiteaLoginResult.Failure, guarded(CancellationException("cancelled")))
    assertEquals(emptyList<Throwable>(), reported)
  }

  fun `test other errors are not swallowed`() {
    expectThrows<OutOfMemoryError> { guarded(OutOfMemoryError("test")) }
    assertEquals(emptyList<Throwable>(), reported)
  }

  fun `test failure messages`() {
    assertEquals("no route", loginFailureMessage(IOException("no route")))
    assertEquals("ConnectException", loginFailureMessage(ConnectException()))
    assertTrue(loginFailureMessage(NoClassDefFoundError("a/b/C")).contains("a/b/C"))
  }
}
