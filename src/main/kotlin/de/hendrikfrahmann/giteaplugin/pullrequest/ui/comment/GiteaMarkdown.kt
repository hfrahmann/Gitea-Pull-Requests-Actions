package de.hendrikfrahmann.giteaplugin.pullrequest.ui.comment

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import de.hendrikfrahmann.giteaplugin.api.request.renderMarkdown
import de.hendrikfrahmann.giteaplugin.repository.GiteaProjectConnection

/**
 * Markdown → HTML for descriptions and comments: Gitea's own rendering (`POST /markdown`, so `#1`/`@user` links match
 * the web UI), or [renderLocally] for anonymous connections – Gitea answers `401` there. Code blocks are highlighted
 * with the editor's colours ([GiteaCodeBlocks]).
 */
object GiteaMarkdown {
  suspend fun render(project: Project, connection: GiteaProjectConnection, text: String): String {
    val html = when {
      connection.isAnonymous -> renderLocally(text)
      else -> connection.api.renderMarkdown(text, connection.repo.repository.path)
    }
    return GiteaCodeBlocks.highlight(project, html)
  }

  /**
   * A small subset of markdown, enough to read PRs without a login: headings, paragraphs, lists, quotes, fenced code,
   * rules; inline code, images, links, autolinks, bold, italic, strikethrough. No tables, task lists or raw HTML (shown as
   * text). Everything is escaped first; links and images only keep `http(s)`, `mailto` and relative URLs.
   * (The platform's markdown library is internal, bundling it trips the Plugin Verifier.)
   */
  fun renderLocally(text: String): String = BlockRenderer(text.replace("\r\n", "\n").split('\n')).render()

  /** Renders the blocks line by line; every block function moves [index] past its last line. */
  private class BlockRenderer(private val lines: List<String>) {
    private val out = StringBuilder()
    private val paragraph = mutableListOf<String>()
    private var index = 0

    fun render(): String {
      while (index < lines.size) {
        renderBlock(lines[index])
      }
      flushParagraph()
      return out.toString()
    }

    private fun renderBlock(line: String) {
      val trimmed = line.trim()
      val heading = HEADING.matchEntire(trimmed)
      when {
        trimmed.startsWith("```") || trimmed.startsWith("~~~") -> codeBlock(fence = trimmed.take(3), info = trimmed.drop(3))
        trimmed.isEmpty() -> skipLine()
        heading != null -> heading(heading)
        RULE.matches(trimmed) -> rule()
        trimmed.startsWith(">") -> quote()
        NUMBERED.matches(line) -> list(NUMBERED, "ol")
        BULLET.matches(line) -> list(BULLET, "ul")
        else -> {
          paragraph += line
          index++
        }
      }
    }

    /** Consecutive text lines form one paragraph, joined with line breaks. */
    private fun flushParagraph() {
      if (paragraph.isNotEmpty()) {
        out.append("<p>").append(paragraph.joinToString("<br/>") { inline(it.trim()) }).append("</p>")
      }
      paragraph.clear()
    }

    private fun skipLine() {
      flushParagraph()
      index++
    }

    /** Up to the closing [fence] (or the end), not rendered; the first word of [info] is the language, like Gitea's. */
    private fun codeBlock(fence: String, info: String) {
      flushParagraph()
      val code = mutableListOf<String>()
      index++
      while (index < lines.size && !lines[index].trim().startsWith(fence)) {
        code += lines[index++]
      }
      val language = info.trim().substringBefore(' ').takeIf { LANGUAGE_NAME.matches(it) }
      val attributes = language?.let { " class=\"language-$it\"" }.orEmpty()
      out.append("<pre><code$attributes>").append(escape(code.joinToString("\n"))).append("</code></pre>")
      index++
    }

    private fun heading(match: MatchResult) {
      flushParagraph()
      val (hashes, title) = match.destructured
      out.append("<h${hashes.length}>").append(inline(title.trimEnd('#', ' '))).append("</h${hashes.length}>")
      index++
    }

    private fun rule() {
      flushParagraph()
      out.append("<hr/>")
      index++
    }

    /** Consecutive `>` lines, rendered as markdown of their own. */
    private fun quote() {
      flushParagraph()
      val quoted = mutableListOf<String>()
      while (index < lines.size && lines[index].trim().startsWith(">")) {
        quoted += lines[index++].trim().removePrefix(">").removePrefix(" ")
      }
      out.append("<blockquote>").append(renderLocally(quoted.joinToString("\n"))).append("</blockquote>")
    }

    /** Consecutive items of one kind ([pattern]) in an `<ol>` or `<ul>` ([tag]). */
    private fun list(pattern: Regex, tag: String) {
      flushParagraph()
      out.append("<$tag>")
      while (index < lines.size) {
        val item = pattern.matchEntire(lines[index]) ?: break
        out.append("<li>").append(inline(item.groupValues[1].trim())).append("</li>")
        index++
      }
      out.append("</$tag>")
    }
  }

  /** Inline markup of one escaped line; code spans and generated tags are kept in placeholders so nothing is processed twice. */
  private fun inline(raw: String): String {
    val tokens = mutableListOf<String>()
    fun keep(html: String): String = "\u0000${tokens.size}\u0000".also { tokens += html }

    var s = CODE.replace(raw) { keep("<code>${escape(it.groupValues[1])}</code>") }
    s = IMAGE.replace(s) { m ->
      val url = safeUrl(m.groupValues[2]) ?: return@replace keep(escape(m.value))
      keep("<img src=\"${escape(url)}\" alt=\"${escape(m.groupValues[1])}\"/>")
    }
    s = LINK.replace(s) { m ->
      val url = safeUrl(m.groupValues[2]) ?: return@replace keep(escape(m.groupValues[1]))
      keep("<a href=\"${escape(url)}\">${emphasis(escape(m.groupValues[1]))}</a>")
    }
    s = AUTOLINK.replace(s) { m -> keep("<a href=\"${escape(m.value)}\">${escape(m.value)}</a>") }
    s = emphasis(escape(s))
    return PLACEHOLDER.replace(s) { tokens[it.groupValues[1].toInt()] }
  }

  private fun emphasis(escaped: String): String = escaped
    .replace(BOLD) { "<b>${it.groupValues[2]}</b>" }
    .replace(ITALIC) { "${it.groupValues[1]}<i>${it.groupValues[3]}</i>" }
    .replace(STRIKE) { "<s>${it.groupValues[1]}</s>" }

  private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

  /** The URL without a title (`[a](url "title")`), `null` for schemes other than http(s) and mailto (e.g. `javascript:`). */
  private fun safeUrl(url: String): String? {
    val target = url.trim().substringBefore(' ')
    val scheme = SCHEME.find(target)?.groupValues?.get(1)?.lowercase()
    return target.takeIf { scheme == null || scheme in SAFE_SCHEMES }
  }

  private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
  private val RULE = Regex("^([-*_])(\\s*\\1){2,}$")
  private val BULLET = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
  private val NUMBERED = Regex("^\\s{0,3}\\d{1,9}[.)]\\s+(.*)$")
  private val CODE = Regex("`([^`]+)`")
  private val IMAGE = Regex("!\\[([^\\]]*)]\\(([^)\\s]+(?:\\s+\"[^\"]*\")?)\\)")
  private val LINK = Regex("\\[([^\\]]+)]\\(([^)\\s]+(?:\\s+\"[^\"]*\")?)\\)")
  private val AUTOLINK = Regex("https?://[^\\s<>\"\\u0000]+[^\\s<>\"\\u0000.,;:!?)]")
  private val BOLD = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
  private val ITALIC = Regex("(^|[^\\w*])([*_])(?=\\S)(.+?)(?<=\\S)\\2(?!\\w)")
  private val STRIKE = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
  private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
  private val PLACEHOLDER = Regex("\u0000(\\d+)\u0000")
  private val LANGUAGE_NAME = Regex("[\\w+#.-]+")
  private val SAFE_SCHEMES = setOf("http", "https", "mailto")
}
