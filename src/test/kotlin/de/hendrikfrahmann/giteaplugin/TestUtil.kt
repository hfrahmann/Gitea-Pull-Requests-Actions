package de.hendrikfrahmann.giteaplugin

/** Like JUnit 5 `assertThrows`, usable in JUnit 3 style platform tests. */
inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
  try {
    block()
  }
  catch (e: Throwable) {
    if (e is T) {
      return e
    }
    throw AssertionError("Expected ${T::class.java.simpleName} but got $e", e)
  }
  throw AssertionError("Expected ${T::class.java.simpleName}, nothing was thrown")
}

/**
 * Settings of the Gitea the integration tests run against: `gitea-test.local.env` in the repository root, or the file named by the
 * `gitea.test.env` system property (`GITEA_TEST_ENV` for `./gradlew integrationTest`, used by CI with a fresh Gitea).
 * Tests are skipped when the file is missing.
 */
object GiteaTestEnv {
  fun load(): java.util.Properties {
    val path = System.getProperty("gitea.test.env")?.takeIf { it.isNotBlank() } ?: "gitea-test.local.env"
    val file = java.io.File(path)
    org.junit.jupiter.api.Assumptions.assumeTrue(file.isFile, "$path missing")
    return java.util.Properties().apply { file.reader().use { load(it) } }
  }

  /** The Gitea has no Actions runner (CI): tests that need workflow runs are skipped. */
  val hasRunner: Boolean get() = System.getProperty("gitea.test.no.runner").isNullOrBlank()
}

/**
 * Runs [block] with slow operations on the EDT reported as errors (which fail the test), also outside actions.
 * Unit-test mode allows them otherwise; the platform's own test setup needs that, so this is per test, not global.
 */
fun <T> forbidSlowOperations(block: () -> T): T {
  val keys = listOf(com.intellij.util.SlowOperations.FORBID_SLOW_OPS_PROPERTY, "ide.slow.operations.assertion.other")
  val previous = keys.associateWith { System.getProperty(it) }
  keys.forEach { System.setProperty(it, "true") }
  try {
    return block()
  }
  finally {
    previous.forEach { (key, value) ->
      if (value == null) {
        System.clearProperty(key)
      }
      else {
        System.setProperty(key, value)
      }
    }
  }
}
