package de.hendrikfrahmann.giteaplugin

import de.hendrikfrahmann.giteaplugin.actions.data.GiteaActionStatus
import de.hendrikfrahmann.giteaplugin.actions.secrets.GiteaRunnerLevel
import de.hendrikfrahmann.giteaplugin.actions.secrets.GiteaRunnerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * i18n completeness: every key the code asks for exists, keys built from enum names exist for every value, every key
 * is used, and quotes follow the platform's rule (`MessageFormat` – and with it `''` → `'` – only runs when there are parameters).
 */
class GiteaBundleConsistencyTest {
  private val bundleFile = File("src/main/resources/messages/GiteaBundle.properties")
  private val bundle = Properties().apply { bundleFile.reader(Charsets.UTF_8).use { load(it) } }
  private val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.associateWith { it.readText() }

  @Test
  fun `every literal key in the code exists`() {
    val literal = Regex("""GiteaBundle\.message(?:Pointer)?\(\s*"([^"$]+)"""")
    val missing = sources.flatMap { (file, text) -> literal.findAll(text).map { it.groupValues[1] to file.name } }
      .filter { (key, _) -> !bundle.containsKey(key) }
    assertEquals(emptyList<Pair<String, String>>(), missing)
  }

  @Test
  fun `keys built from enum names exist for every value`() {
    val expected = GiteaActionStatus.entries.map { "actions.status.${it.name.lowercase()}" } +
                   GiteaRunnerState.entries.map { "actions.runners.state.${it.name.lowercase()}" } +
                   GiteaRunnerLevel.entries.map { "actions.runners.level.${it.name.lowercase()}" }
    assertEquals(emptyList<String>(), expected.filterNot(bundle::containsKey))
  }

  /**
   * A key counts as used when it's quoted in the code or a descriptor, is the text of a declared action/group, or starts
   * with a prefix the code completes at runtime (`"actions.details.$key"`, enum names, the tool window stripe convention).
   */
  @Test
  fun `every key is used`() {
    val descriptors = File("src/main/resources/META-INF").walk().filter { it.extension == "xml" }.map { it.readText() }.toList()
    val texts = sources.values + descriptors
    val actionIds = descriptors.flatMap { xml -> Regex("""<(?:action|group)\s[^>]*\bid="([^"]+)"""").findAll(xml).map { it.groupValues[1] } }.toSet()
    val overridePlaces = descriptors.flatMap { xml -> Regex("""<override-text place="([^"]+)"""").findAll(xml).map { it.groupValues[1] } }.toSet()
    val dynamicPrefixes = texts.flatMap { text -> Regex(""""([a-z][\w.]*\.)\$""").findAll(text).map { it.groupValues[1] } }.toSet() +
                          "toolwindow.stripe."
    val unused = bundle.stringPropertyNames().sorted().filterNot { key ->
      val action = Regex("""^(?:action|group)\.(.+)\.(?:text|description)$""").matchEntire(key)?.groupValues?.get(1)
      val isActionText = action != null &&
                         (action in actionIds || overridePlaces.any { action.endsWith(".$it") && action.removeSuffix(".$it") in actionIds })
      isActionText || texts.any { "\"$key\"" in it } || dynamicPrefixes.any(key::startsWith)
    }
    assertEquals(emptyList<String>(), unused)
  }

  @Test
  fun `quotes match whether the message has parameters`() {
    val parameter = Regex("""\{\d""")
    val loneQuote = Regex("""(?<!')'(?!')""")
    val wrong = bundle.stringPropertyNames().sorted().mapNotNull { key ->
      val value = bundle.getProperty(key)
      when {
        parameter.containsMatchIn(value) && loneQuote.containsMatchIn(value) -> "$key: single quote is dropped, use ''"
        !parameter.containsMatchIn(value) && "''" in value -> "$key: no parameters, '' is shown twice"
        else -> null
      }
    }
    assertEquals(emptyList<String>(), wrong)
  }

  @Test
  fun `no duplicate keys`() {
    val keys = bundleFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }.map { it.substringBefore('=').trim() }
    assertTrue(keys.size == keys.toSet().size, "duplicates: ${keys.groupBy { it }.filterValues { it.size > 1 }.keys}")
  }
}
