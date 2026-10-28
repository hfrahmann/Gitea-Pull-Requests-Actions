package de.hendrikfrahmann.giteaplugin.util

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

@NonNls
private const val BUNDLE = "messages.GiteaBundle"

object GiteaBundle : DynamicBundle(GiteaBundle::class.java, BUNDLE) {
  @JvmStatic
  fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any): @Nls String =
    getMessage(key, *params)

  @JvmStatic
  fun messagePointer(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any): Supplier<@Nls String> =
    getLazyMessage(key, *params)
}
