package de.hendrikfrahmann.giteaplugin.pullrequest

import com.intellij.collaboration.ui.SimpleHtmlPane
import com.intellij.collaboration.ui.setHtmlBody
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaHtmlLineBreaking
import javax.swing.JEditorPane

/** A long path in `code` wraps like normal text even when the comment has an emoji (Swing's "i18n" layout). */
class GiteaHtmlLineBreakingTest : BasePlatformTestCase() {
  fun `test a long path wraps into full lines next to an emoji`() {
    val pane = pane("<h2>🤖 Agentic Code Review</h2><p><code>$PATH</code></p>", install = true)

    val lines = pathLines(pane)
    assertTrue("wrapped: $lines", lines.size > 1)
    assertTrue("no line with a single segment: $lines", lines.dropLast(1).all { it.count { c -> c == '/' } > 1 })
  }

  fun `test without the fix the emoji puts every segment on its own line`() {
    // guards the test above: if the platform fixes its layout, this fails and the workaround can go
    val lines = pathLines(pane("<h2>🤖 Agentic Code Review</h2><p><code>$PATH</code></p>", install = false))

    assertEquals("Modules/", lines.first())
  }

  fun `test right-to-left and shaped text keep their layout`() {
    assertTrue(GiteaHtmlLineBreaking.needsTextLayout("שלום"))
    assertTrue(GiteaHtmlLineBreaking.needsTextLayout("नमस्ते"))
    assertFalse(GiteaHtmlLineBreaking.needsTextLayout("🤖 ❤️ 1️⃣ Schlüssel „obsolet“ 漢字"))

    val pane = pane("<p>🤖 שלום</p>", install = true)
    assertEquals(true, pane.document.getProperty("i18n"))
  }

  private fun pane(html: String, install: Boolean): JEditorPane = SimpleHtmlPane(addBrowserListener = false).apply {
    if (install) {
      GiteaHtmlLineBreaking.install(this)
    }
    setHtmlBody(html)
    setSize(WIDTH, Int.MAX_VALUE / 2)
    setSize(WIDTH, preferredSize.height)
  }

  /** The lines the path is laid out on, without the platform's zero-width spaces. */
  private fun pathLines(pane: JEditorPane): List<String> {
    val document = pane.document
    val text = document.getText(0, document.length)
    val start = text.indexOf("Modules/")
    val lines = mutableListOf<StringBuilder>()
    var lastY = -1
    for (offset in start until text.indexOf(":80") + 3) {
      val y = pane.modelToView2D(offset)?.y?.toInt() ?: continue
      if (y != lastY) {
        lines += StringBuilder()
        lastY = y
      }
      lines.last().append(text[offset])
    }
    return lines.map { it.toString().replace("​", "") }
  }

  private companion object {
    const val WIDTH = 400
    const val PATH = "Modules/Readcard/src/main/java/de/newmediacompany/smartyserver/module/readcard/kbvstammdaten/KbvStammdatenService.java:80"
  }
}
