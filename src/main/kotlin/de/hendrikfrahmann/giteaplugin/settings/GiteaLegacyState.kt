package de.hendrikfrahmann.giteaplugin.settings

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path

/**
 * Application state from before 1.0.5, when the components were called `GiteaSettings` / `GiteaAccounts` and lived in
 * `gitea.xml`. Those names clashed with other Gitea plugins ("Conflicting component name"), so they were renamed; this reads
 * the old values once, while the new component has no state yet. The old file is left alone, another plugin may use it.
 *
 * Another plugin may have written its own state under the old name, so an entry is only taken if it is exactly what this
 * plugin would have saved: deserialized and serialized again (defaults skipped, as the platform saves) it must be unchanged.
 * Unknown options, other structures or values that don't parse are not ours, and nothing is imported.
 */
internal object GiteaLegacyState {
  const val LEGACY_FILE: String = "gitea.xml"

  fun <T : Any> read(componentName: String, stateClass: Class<T>, file: Path = PathManager.getOptionsDir().resolve(LEGACY_FILE)): T? =
    runCatching {
      if (!Files.isRegularFile(file)) {
        return null
      }
      val component = JDOMUtil.load(file).getChildren("component").firstOrNull { it.getAttributeValue("name") == componentName }
        ?: return null
      val state = XmlSerializer.deserialize(component, stateClass)
      if (!isSameContent(component, serialize(state))) {
        logger<GiteaLegacyState>().info("$componentName in $file is not this plugin's state, not imported")
        return null
      }
      state
    }.onFailure { logger<GiteaLegacyState>().info("Cannot read $componentName from $file", it) }.getOrNull()

  /** [state] as the platform saves it: options with default values are left out. */
  fun serialize(state: Any): Element? = XmlSerializer.serialize(state, SkipDefaultsSerializationFilter())

  /** Same children, ignoring the root elements themselves (`<component name=…>` vs. the bean's tag). */
  private fun isSameContent(stored: Element, serialized: Element?): Boolean {
    val expected = serialized?.children.orEmpty()
    val actual = stored.children
    return expected.size == actual.size && expected.zip(actual).all { (a, b) -> JDOMUtil.areElementsEqual(a, b) }
  }
}
