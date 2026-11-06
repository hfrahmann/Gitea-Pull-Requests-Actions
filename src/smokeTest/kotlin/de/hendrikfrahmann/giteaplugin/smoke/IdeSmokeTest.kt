package de.hendrikfrahmann.giteaplugin.smoke

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.PluginId
import com.intellij.driver.sdk.PluginManagerCore
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.forEachLine

private const val PLUGIN_ID = "de.hendrikfrahmann.giteaplugin"
private const val PLUGIN_PACKAGE = "de/hendrikfrahmann/giteaplugin/"

private val LOG_ENTRY_START = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{3} """)
private val SEVERE_LEVEL = Regex("""\] +SEVERE - """)

/**
 * Behind an optional dependency on "Issue Trackers" (com.intellij.tasks), which 2026.3 no longer bundles: classes here extend
 * what the plugin's main classloader doesn't see, and the config that would load them is skipped.
 */
private const val TASKS_PACKAGE = "de.hendrikfrahmann.giteaplugin.tasks."

/**
 * The plugin as shipped, in a real IDE: what unit tests (plugin on the test classpath) and Plugin Verifier (classes exist
 * somewhere in the IDE) can't see is whether the plugin's classloader may reach a platform module. A missing
 * `<module>` in plugin.xml only shows up here.
 */
class IdeSmokeTest {
  @Test
  fun pluginLoadsInRealIde() {
    val pluginZip = Path.of(System.getProperty("gitea.smoke.plugin.zip"))
    val classes = pluginClasses(pluginZip)
    val result = ideContext(pluginZip)
      .runIdeWithDriver()
      .useDriverAndCloseIde {
        assertTrue(isPluginLoaded(PLUGIN_ID), "$PLUGIN_ID is not loaded")
        checkGiteaJson()
        checkClassesLoad(classes.filterNot { it.startsWith(TASKS_PACKAGE) })
      }

    val errors = severeLogEntries(result.runContext.logsDir.resolve("idea.log"))
    // Plugin id and package name start alike: finds PluginException's `[Plugin: id]` and the plugin's stack frames
    val ours = errors.filter { PLUGIN_ID in it }
    println("idea.log has ${errors.size} SEVERE entries, ${ours.size} from the plugin")
    assertTrue(ours.isEmpty()) { ours.joinToString("\n\n") }
  }

  /** A log entry starts with a timestamp; the stack trace lines after it belong to it. */
  private fun severeLogEntries(log: Path): List<String> {
    val entries = mutableListOf<StringBuilder>()
    log.forEachLine { line ->
      if (LOG_ENTRY_START.containsMatchIn(line)) {
        entries += StringBuilder()
      }
      entries.lastOrNull()?.appendLine(line)
    }
    return entries.map { it.toString() }.filter { SEVERE_LEVEL.containsMatchIn(it.lineSequence().first()) }
  }

  private fun ideContext(pluginZip: Path): IDETestContext {
    val eap = System.getProperty("gitea.smoke.ide.eap").toBoolean()
    val version = System.getProperty("gitea.smoke.ide.version")
    val ide = TestCase(IdeInfo.IdeaUltimate, NoProject)
    val (testCase, testName) = when {
      eap -> ide.useEAP() to "smoke-eap"
      else -> ide.useRelease(version) to "smoke-$version"
    }
    return Starter.newContext(testName, testCase).also {
      PluginConfigurator(it).installPluginFromPath(pluginZip)
    }
  }

  /** Nothing initializes GiteaJson at IDE start. A Kotlin DTO also needs Jackson's Kotlin module and kotlin-reflect. */
  private fun Driver.checkGiteaJson() {
    val json = utility<GiteaJsonRef>()
    assertEquals("\"smoke\"", json.toJsonString("smoke"))
    val labelClass = pluginClassLoader().loadClass("de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO")
    assertEquals("bug", json.fromJson("""{"id":1,"name":"bug","color":null}""", labelClass).getName())
  }

  /** Loading resolves superclass and interfaces, so a class extending something from an undeclared module fails here. */
  private fun Driver.checkClassesLoad(classes: List<String>) {
    val loader = pluginClassLoader()
    val failures = classes.mapNotNull { name ->
      runCatching { loader.loadClass(name) }.exceptionOrNull()?.let { "$name: ${generateSequence(it, Throwable::cause).last().message}" }
    }
    println("Loaded ${classes.size - failures.size} of ${classes.size} plugin classes")
    assertTrue(failures.isEmpty()) { "${failures.size} plugin classes don't load:\n" + failures.joinToString("\n") }
  }

  private fun Driver.pluginClassLoader(): ClassLoaderRef {
    val plugin = checkNotNull(utility<PluginManagerCore>().getPlugin(utility<PluginId>().getId(PLUGIN_ID)))
    return cast(plugin, PluginDescriptorRef::class).getPluginClassLoader()
  }

  /** Every class of the plugin's own jar, read from the zip: lib/<plugin>.jar next to the jars of its bundled libraries. */
  private fun pluginClasses(pluginZip: Path): List<String> = ZipFile(pluginZip.toFile()).use { zip ->
    zip.entries().asSequence()
      .filter { it.name.endsWith(".jar") }
      .flatMap { jar ->
        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip.getInputStream(jar).readAllBytes())).use { stream ->
          generateSequence { stream.nextEntry }.forEach { entries += it.name }
        }
        entries
      }
      .filter { it.startsWith(PLUGIN_PACKAGE) && it.endsWith(".class") }
      .map { it.removeSuffix(".class").replace('/', '.') }
      .toList()
  }
}

/** `plugin = ...`: the Driver resolves the class through the plugin's classloader, as the plugin's own code does. */
@Remote("de.hendrikfrahmann.giteaplugin.api.GiteaJson", plugin = PLUGIN_ID)
interface GiteaJsonRef {
  fun toJsonString(value: Any): String

  fun fromJson(json: String, type: ClassRef): GiteaLabelRef
}

@Remote("de.hendrikfrahmann.giteaplugin.api.dto.GiteaLabelDTO", plugin = PLUGIN_ID)
interface GiteaLabelRef {
  fun getName(): String
}

@Remote("com.intellij.openapi.extensions.PluginDescriptor")
interface PluginDescriptorRef {
  fun getPluginClassLoader(): ClassLoaderRef
}

@Remote("java.lang.ClassLoader")
interface ClassLoaderRef {
  fun loadClass(name: String): ClassRef
}

@Remote("java.lang.Class")
interface ClassRef
