package de.hendrikfrahmann.giteaplugin.pullrequest

import com.intellij.collaboration.ui.SimpleHtmlPane
import com.intellij.collaboration.ui.setHtmlBody
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaCodeBlocks
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaMarkdown
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Code blocks from Gitea (chroma classes) and the local renderer, highlighted with the editor's colours. */
class GiteaCodeBlocksTest : BasePlatformTestCase() {
  fun `test gitea's highlighting is replaced by the editor's, without the empty last line`() {
    val html = highlight(GITEA_HTML)

    assertFalse(html, html.contains("chroma"))
    assertFalse(html, html.contains("code-block-container"))
    assertTrue("entities decoded and escaped again: $html", html.contains("&quot;age&quot;") || html.contains("\"age\""))
    assertTrue("lines kept, none empty at the end: $html", html.contains("{</span>\n") && html.contains("}</span></code></pre>"))
    assertFalse(html, html.contains("<br"))
    assertTrue("text around the block kept: $html", html.contains("Hallo welt.") && html.contains("<code>Module\\Demo\\demo.java</code>"))
    paint("gitea", html)
  }

  fun `test empty lines inside a block are kept`() {
    val html = highlight("<pre><code class=\"language-json\">{\n\n\n  &#34;a&#34;: 1\n}\n</code></pre>")

    assertFalse("no line break at the end of a span (Swing drops it): $html", html.contains("\n</span>"))
    assertEquals(html, 4, html.count { it == '\n' })
  }

  fun `test unknown languages stay plain text, special characters survive`() {
    val html = highlight("""<pre class="code-block"><code class="chroma language-nosuchlang">a &lt; b &amp;&amp; c &#39;x&#39;
</code></pre>""").replace("&#32;", " ")

    assertTrue(html, html.contains("a &lt; b &amp;&amp; c 'x'</span></code></pre>"))
  }

  fun `test the local renderer passes the fence language on`() {
    val html = GiteaMarkdown.renderLocally("```json\n{\"a\": 1}\n```")

    assertTrue(html, html.contains("""<code class="language-json">"""))
  }

  private fun highlight(html: String) = runReadActionBlocking { GiteaCodeBlocks.highlightNow(project, html) }

  /** Rendered next to the source for a look by eye (build/tmp), not compared. */
  private fun paint(name: String, html: String) {
    val pane = SimpleHtmlPane(addBrowserListener = false).apply {
      setHtmlBody(html)
      setSize(500, Int.MAX_VALUE / 2)
      setSize(500, preferredSize.height)
    }
    val image = BufferedImage(pane.width, pane.height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = pane.background
    g.fillRect(0, 0, pane.width, pane.height)
    pane.paint(g)
    g.dispose()
    File("build/tmp/code-blocks").mkdirs()
    ImageIO.write(image, "png", File("build/tmp/code-blocks/$name.png"))
    File("build/tmp/code-blocks/$name.html").writeText(html)
  }

  private companion object {
    // Gitea 28.1 `POST /markdown`, shortened
    const val GITEA_HTML = """<p dir="auto">Hallo welt.</p>
<div class="code-block-container code-overflow-scroll"><pre class="code-block"><code class="chroma language-json"><span class="p">{</span>
  <span class="nt">&#34;age&#34;</span><span class="p">:</span> <span class="mi">123</span><span class="p">,</span>
  <span class="nt">&#34;name&#34;</span><span class="p">:</span> <span class="s2">&#34;Max Muster&#34;</span>
<span class="p">}</span>
</code></pre></div><p dir="auto">Das ist ein Pfad <code>Module\Demo\demo.java</code> la.</p>"""
  }
}
