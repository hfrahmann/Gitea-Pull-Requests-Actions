package de.hendrikfrahmann.giteaplugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** The plugin collects no usage statistics – no FUS collectors or statistics extensions, no telemetry API. */
class GiteaNoTelemetryTest {
  @Test
  fun `no usage statistics extensions in the plugin descriptors`() {
    // statistics.* extension points (counterUsagesCollector, applicationUsagesCollector, validation rules, …) and collector classes
    val forbidden = Regex("""<statistics\.|UsagesCollector|eventLogGroup""", RegexOption.IGNORE_CASE)
    val hits = File("src/main/resources/META-INF").listFiles { file -> file.extension == "xml" }.orEmpty()
      .flatMap { file -> file.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${file.name}:${it.index + 1}: ${it.value.trim()}" } }
    assertEquals(emptyList<String>(), hits)
  }

  @Test
  fun `no usage statistics or telemetry API in the code`() {
    // FUS (com.intellij.internal.statistics.*) and the platform's OpenTelemetry wrapper
    val forbidden = Regex("""com\.intellij\.internal\.statistics|com\.intellij\.platform\.diagnostic\.telemetry|io\.opentelemetry|UsagesCollector""")
    val hits = File("src/main").walk().filter { it.extension == "kt" || it.extension == "java" }
      .flatMap { file -> file.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${file.name}:${it.index + 1}: ${it.value.trim()}" } }
      .toList()
    assertEquals(emptyList<String>(), hits)
  }
}
