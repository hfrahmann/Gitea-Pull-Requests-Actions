package de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment

import com.intellij.lang.documentation.QuickDocHighlightingHelper
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project

/**
 * Fenced code in rendered markdown, highlighted with the editor's colours like the platform's quick documentation.
 * Gitea highlights with CSS classes (chroma) that Swing can't style, and ends every block with a line break, which
 * Swing shows as an empty last line. The language comes from the block's `language-…` class; one the IDE doesn't know
 * stays plain text.
 */
internal object GiteaCodeBlocks {
  suspend fun highlight(project: Project, html: String): String = readAction { highlightNow(project, html) }

  /** Needs a read action (lexers). */
  fun highlightNow(project: Project, html: String): String = CODE_BLOCK.replace(CONTAINER.replace(html, "$1")) { block ->
    val language = LANGUAGE.find(block.groupValues[1])?.groupValues?.get(1)?.let(QuickDocHighlightingHelper::guessLanguage)
    QuickDocHighlightingHelper.getStyledCodeBlock(project, language ?: PlainTextLanguage.INSTANCE, codeText(block.groupValues[2]))
      // the quick doc pane breaks lines at <br>; inside <pre> this pane only keeps real line breaks, and only between
      // spans – one at the end of a span is lost
      .replace(BR, "\n")
      .replace(LINE_BREAKS_IN_SPAN, "$2$1")
  }

  /** The block's text: Gitea's highlighting spans removed, entities decoded, without the trailing line break. */
  private fun codeText(html: String): String = TAG.replace(html, "")
    .replace(NUMERIC_ENTITY) { entity ->
      val (hex, digits) = entity.destructured
      val radix = when {
        hex.isEmpty() -> DECIMAL
        else -> HEXADECIMAL
      }
      val codePoint = digits.toIntOrNull(radix)
      codePoint?.let { Character.toString(it) } ?: entity.value
    }
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
    // last: "&amp;lt;" is the text "&lt;"
    .replace("&amp;", "&")
    .trimEnd('\n')

  /** Gitea's wrapper around each block (for the copy button). */
  private val CONTAINER = Regex("""<div class="code-block-container[^"]*">\s*(<pre[\s>].*?</pre>)\s*</div>""", RegexOption.DOT_MATCHES_ALL)
  private val CODE_BLOCK = Regex("""<pre(?:\s[^>]*)?>\s*<code([^>]*)>(.*?)</code>\s*</pre>""", RegexOption.DOT_MATCHES_ALL)
  private val LANGUAGE = Regex("""language-([\w+#.-]+)""")
  private val TAG = Regex("<[^>]+>")
  private val BR = Regex("<br\\s*/?>")
  private val LINE_BREAKS_IN_SPAN = Regex("(\n+)((?:</span>)+)")
  private val NUMERIC_ENTITY = Regex("&#([xX]?)([0-9a-fA-F]+);")
  private const val DECIMAL = 10
  private const val HEXADECIMAL = 16
}
