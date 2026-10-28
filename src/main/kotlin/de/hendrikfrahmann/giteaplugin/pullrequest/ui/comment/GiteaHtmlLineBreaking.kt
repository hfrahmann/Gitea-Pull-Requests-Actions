package de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment

import java.text.Bidi
import javax.swing.JEditorPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Document

/**
 * Keeps rendered markdown on Swing's plain line breaking. One character that needs `TextLayout` – an emoji is enough,
 * e.g. Gitea's `:robot:` in a bot's heading – switches the whole HTML document to "i18n" layout. Its paragraphs can't
 * break at the `<wbr>` the platform puts after every "/", so a long path in `code` ends up with one segment per line.
 * Text without right-to-left scripts or combining marks doesn't need that layout (emoji draw the same without it).
 */
internal object GiteaHtmlLineBreaking {
  /** Document listeners run newest first, so the flag is reset before the text UI builds the views of new text. */
  fun install(pane: JEditorPane) {
    pane.document.addDocumentListener(ResetI18n)
    pane.addPropertyChangeListener("document") { e ->
      (e.oldValue as? Document)?.removeDocumentListener(ResetI18n)
      (e.newValue as? Document)?.addDocumentListener(ResetI18n)
    }
  }

  /** Right-to-left text and scripts built from combining marks (Thai, Devanagari, …) do need `TextLayout`. */
  fun needsTextLayout(text: String): Boolean =
    Bidi.requiresBidi(text.toCharArray(), 0, text.length) || text.codePoints().anyMatch(::isShapingMark)

  private fun isShapingMark(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> codePoint !in EMOJI_MARKS
    else -> false
  }

  private object ResetI18n : DocumentListener {
    override fun insertUpdate(e: DocumentEvent) {
      val document = e.document
      if (document.getProperty(I18N) == true && !needsTextLayout(document.getText(0, document.length))) {
        document.putProperty(I18N, false)
      }
    }

    override fun removeUpdate(e: DocumentEvent) = Unit

    override fun changedUpdate(e: DocumentEvent) = Unit
  }

  /** `AbstractDocument.I18NProperty`, which is package-private. */
  private const val I18N = "i18n"

  /** Variation selectors (text/emoji style) and the keycap mark, which only come with emoji. */
  private val EMOJI_MARKS = setOf(0xFE0E, 0xFE0F, 0x20E3)
}
