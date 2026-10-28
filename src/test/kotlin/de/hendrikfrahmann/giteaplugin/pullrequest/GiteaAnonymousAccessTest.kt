package de.hendrikfrahmann.giteaplugin.pullrequest

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import de.hendrikfrahmann.giteaplugin.api.GiteaServerPath
import de.hendrikfrahmann.giteaplugin.authentication.accounts.GiteaAccount
import de.hendrikfrahmann.giteaplugin.pullrequest.data.GiteaPullRequestsLoader.Source
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment.GiteaMarkdown
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue
import de.hendrikfrahmann.giteaplugin.pullrequest.ui.filters.GiteaPullRequestsFiltersValue.UserFilterValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Read-only access without an account: the stand-in account, local markdown, list sources without a login. */
class GiteaAnonymousAccessTest {
  private val server = GiteaServerPath("http://one.intranet:3000")

  @Test
  fun `anonymous account is per server and recognizable`() {
    val anonymous = GiteaAccount.anonymous(server)
    assertTrue(anonymous.isAnonymous)
    assertTrue(GiteaAccount.isAnonymousId(anonymous.id))
    assertEquals(GiteaAccount.anonymous(server), anonymous)
    assertNotEquals(GiteaAccount.anonymous(GiteaServerPath("https://gitea.com")), anonymous)

    val real = GiteaAccount("intellij-dev", server)
    assertFalse(real.isAnonymous)
    assertFalse(GiteaAccount.isAnonymousId(real.id))
    assertFalse(GiteaAccount.isAnonymousId(null))
  }

  @Test
  fun `isAnonymous is not persisted`() {
    val xml = JDOMUtil.write(XmlSerializer.serialize(GiteaAccount("intellij-dev", server)))
    assertFalse(xml.contains("anonymous", ignoreCase = true), xml)
  }

  @Test
  fun `local markdown renders the common subset`() {
    val html = GiteaMarkdown.renderLocally("""
      # Title
      **bold**, *italic*, ~~gone~~ and `a < b`
      second line

      - one
      - two [link](https://gitea.com/x)

      1. first

      > quoted

      ```kotlin
      val x = "<tag>"
      ```
      see https://one.intranet:3000/pr/1.
    """.trimIndent())
    assertTrue(html.contains("<h1>Title</h1>"), html)
    assertTrue(html.contains("<b>bold</b>, <i>italic</i>, <s>gone</s> and <code>a &lt; b</code><br/>second line"), html)
    assertTrue(html.contains("<ul><li>one</li><li>two <a href=\"https://gitea.com/x\">link</a></li></ul>"), html)
    assertTrue(html.contains("<ol><li>first</li></ol>"), html)
    assertTrue(html.contains("<blockquote><p>quoted</p></blockquote>"), html)
    assertTrue(html.contains("<pre><code class=\"language-kotlin\">val x = &quot;&lt;tag&gt;&quot;</code></pre>"), html)
    assertTrue(html.contains("<a href=\"https://one.intranet:3000/pr/1\">https://one.intranet:3000/pr/1</a>."), html)
  }

  @Test
  fun `local markdown escapes html and drops script urls`() {
    val html = GiteaMarkdown.renderLocally("<script>alert(1)</script> [click](javascript:alert(1)) ![img](/attachments/x) snake_case_name")
    assertFalse(html.contains("<script>"), html)
    assertFalse(html.contains("javascript:"), html)
    assertTrue(html.contains("&lt;script&gt;"), html)
    assertTrue(html.contains(" click"), html)
    assertTrue(html.contains("<img src=\"/attachments/x\" alt=\"img\"/>"), html)
    assertTrue(html.contains("snake_case_name"), html)
  }

  @Test
  fun `list source without a login`() {
    val someone = UserFilterValue("intellij-reviewer", "Reviewer")
    assertEquals(Source.PULLS, Source.choose(GiteaPullRequestsFiltersValue(reviewer = someone), null))
    assertEquals(Source.ISSUES, Source.choose(GiteaPullRequestsFiltersValue(searchQuery = "main"), null))
    assertEquals(Source.PULLS, Source.choose(GiteaPullRequestsFiltersValue.EMPTY, null))
  }
}
