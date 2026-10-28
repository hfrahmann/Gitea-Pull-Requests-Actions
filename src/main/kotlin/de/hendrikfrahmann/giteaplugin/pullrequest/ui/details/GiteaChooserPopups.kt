package de.hendrikfrahmann.giteaplugin.pullrequest.ui.details

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.swing.Icon
import javax.swing.JList
import kotlin.coroutines.resume

/**
 * Simple choosers for the edit links in the details: collaboration-tools' multi-choosers are internal API, so these are
 * built from public popup/list components.
 */
object GiteaChooserPopups {
  data class Item(val text: @NlsContexts.Label String, val secondary: String? = null, val icon: Icon? = null)

  /** Check boxes; the selection is applied when the popup closes (Esc or click outside). `null` if nothing changed. */
  suspend fun <T> chooseMultiple(point: RelativePoint, title: @NlsContexts.PopupTitle String, items: List<T>, selected: Set<T>,
                                 presenter: (T) -> Item): List<T>? = suspendCancellableCoroutine { cont ->
    val list = CheckBoxList<T>()
    for (item in items) {
      list.addItem(item, presenter(item).let { it.secondary?.let { s -> "${it.text}  ($s)" } ?: it.text }, item in selected)
    }
    val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(
      ScrollPaneFactory.createScrollPane(list, true).apply { border = JBUI.Borders.empty(4) }, list)
      .setTitle(title)
      .setRequestFocus(true)
      .setMovable(true)
      .setResizable(true)
      .setCancelOnClickOutside(true)
      .createPopup()
    popup.addListener(object : JBPopupListener {
      override fun onClosed(event: LightweightWindowEvent) {
        val chosen = items.filter { list.isItemSelected(it) }
        cont.resumeOnce(chosen.takeIf { it.toSet() != selected })
      }
    })
    cont.invokeOnCancellation { popup.cancel() }
    popup.show(point)
  }

  /** Single choice; `null` if cancelled. */
  suspend fun <T> chooseOne(point: RelativePoint, title: @NlsContexts.PopupTitle String, items: List<T>, presenter: (T) -> Item): T? =
    suspendCancellableCoroutine { cont ->
      val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
        .setTitle(title)
        .setRenderer(object : ColoredListCellRenderer<T>() {
          override fun customizeCellRenderer(list: JList<out T>, value: T, index: Int, selected: Boolean, hasFocus: Boolean) {
            val item = presenter(value)
            icon = item.icon
            append(item.text)
            item.secondary?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
          }
        })
        .setItemChosenCallback { cont.resumeOnce(it) }
        .addListener(object : JBPopupListener {
          override fun onClosed(event: LightweightWindowEvent) {
            if (!event.isOk) {
              cont.resumeOnce(null)
            }
          }
        })
        .createPopup()
      cont.invokeOnCancellation { popup.cancel() }
      popup.show(point)
    }

  private fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
    if (isActive) {
      resume(value)
    }
  }
}
