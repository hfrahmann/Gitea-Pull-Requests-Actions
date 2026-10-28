package de.hendrikfrahmann.giteaplugin.actions.data

/**
 * The `on.workflow_dispatch` trigger of a workflow file: whether the workflow can be started manually and with
 * which inputs. Read with a small YAML subset reader (block mappings and sequences, flow lists, quoted and block
 * scalars) – enough for the `on:` section; the platform's SnakeYAML isn't public API for plugins.
 */
data class GiteaWorkflowDispatch(val inputs: List<Input>) {
  data class Input(
    val name: String,
    val description: String?,
    val required: Boolean,
    val default: String?,
    val type: Type,
    /** For [Type.CHOICE]. */
    val options: List<String>,
  )

  enum class Type { STRING, BOOLEAN, CHOICE, NUMBER, ENVIRONMENT }

  companion object {
    /** `null` when the workflow has no `workflow_dispatch` trigger (or isn't readable). */
    fun parse(yaml: String): GiteaWorkflowDispatch? {
      val root = runCatching { GiteaMiniYaml.parse(yaml) }.getOrNull() as? Map<*, *> ?: return null
      // `on` is a boolean in YAML 1.1; workflows also quote it
      val on = root["on"] ?: root["true"] ?: return null
      return when (on) {
        is String -> when {
          on == TRIGGER -> GiteaWorkflowDispatch(emptyList())
          else -> null
        }
        is List<*> -> when {
          on.any { it == TRIGGER } -> GiteaWorkflowDispatch(emptyList())
          else -> null
        }
        is Map<*, *> -> {
          if (!on.containsKey(TRIGGER)) {
            return null
          }
          val inputs = ((on[TRIGGER] as? Map<*, *>)?.get("inputs") as? Map<*, *>).orEmpty()
          GiteaWorkflowDispatch(inputs.mapNotNull { (name, value) -> input(name as? String ?: return@mapNotNull null, value as? Map<*, *>) })
        }
        else -> null
      }
    }

    private fun input(name: String, spec: Map<*, *>?): Input {
      val options = when (val o = spec?.get("options")) {
        is List<*> -> o.mapNotNull { it?.toString() }
        is String -> listOf(o)
        else -> emptyList()
      }
      val type = when ((spec?.get("type") as? String)?.lowercase()) {
        "boolean" -> Type.BOOLEAN
        "choice" -> Type.CHOICE
        "number" -> Type.NUMBER
        "environment" -> Type.ENVIRONMENT
        else -> when {
          options.isNotEmpty() -> Type.CHOICE
          else -> Type.STRING
        }
      }
      return Input(
        name = name,
        description = (spec?.get("description") as? String)?.trim()?.takeIf { it.isNotEmpty() },
        required = spec?.get("required")?.toString()?.equals("true", ignoreCase = true) == true,
        default = spec?.get("default")?.toString(),
        type = type,
        options = options,
      )
    }

    private const val TRIGGER = "workflow_dispatch"
  }
}

/**
 * YAML subset → `Map<String, Any?>` / `List<Any?>` / `String` / `null`. Supports block mappings and sequences
 * (also a sequence at the same indent as its key), `- key: value` items, flow sequences/mappings without nesting,
 * single/double quoted scalars, `|` and `>` block scalars and comments. Anchors, tags and multi-documents aren't.
 */
internal object GiteaMiniYaml {
  private class Line(val indent: Int, val text: String, val number: Int)

  fun parse(yaml: String): Any? {
    val raw = yaml.replace("\r\n", "\n").replace('\t', ' ').lines()
    val lines = raw.mapIndexedNotNull { i, l ->
      val text = stripComment(l).trimEnd()
      if (text.isBlank() || text.trim() == "---") {
        null
      }
      else {
        Line(text.length - text.trimStart().length, text.trim(), i)
      }
    }
    if (lines.isEmpty()) {
      return null
    }
    return Parser(lines, raw).parseBlock(0, lines.first().indent).first
  }

  private class Parser(private val lines: List<Line>, private val raw: List<String>) {
    /** Parses the block starting at [index] with [indent]; returns the value and the next unparsed index. */
    fun parseBlock(index: Int, indent: Int): Pair<Any?, Int> {
      val line = lines.getOrNull(index) ?: return null to index
      return when {
        isItem(line.text) -> parseSequence(index, indent)
        else -> parseMapping(index, indent)
      }
    }

    /** [firstLine]: the first key comes from a `- key: value` item; the mapping's other lines start at [start]. */
    private fun parseMapping(start: Int, indent: Int, firstLine: Line? = null): Pair<Map<String, Any?>, Int> {
      val result = LinkedHashMap<String, Any?>()
      var i = start
      var current: Line? = firstLine ?: lines.getOrNull(i)
      while (current != null && current.indent == indent && !isItem(current.text)) {
        val colon = keySeparator(current.text) ?: break
        val key = unquote(current.text.substring(0, colon).trim())
        val rest = current.text.substring(colon + 1).trim()
        if (current !== firstLine) {
          i++
        }
        val next = lines.getOrNull(i)
        when {
          rest.startsWith("|") || rest.startsWith(">") -> {
            val (text, nextIndex) = blockScalar(current, i, rest.startsWith(">"))
            result[key] = text
            i = nextIndex
          }
          rest.isNotEmpty() -> result[key] = scalarOrFlow(rest)
          next != null && next.indent > indent -> {
            val (value, nextIndex) = parseBlock(i, next.indent)
            result[key] = value
            i = nextIndex
          }
          next != null && next.indent == indent && isItem(next.text) -> {
            val (value, nextIndex) = parseSequence(i, indent)
            result[key] = value
            i = nextIndex
          }
          else -> result[key] = null
        }
        current = lines.getOrNull(i)
      }
      return result to i
    }

    private fun parseSequence(start: Int, indent: Int): Pair<List<Any?>, Int> {
      val result = mutableListOf<Any?>()
      var i = start
      while (true) {
        val line = lines.getOrNull(i) ?: break
        if (line.indent != indent || !isItem(line.text)) {
          break
        }
        val content = line.text.removePrefix("-").trimStart()
        val contentIndent = indent + (line.text.length - content.length)
        i++
        when {
          content.isEmpty() -> {
            val next = lines.getOrNull(i)
            if (next != null && next.indent > indent) {
              val (value, nextIndex) = parseBlock(i, next.indent)
              result += value
              i = nextIndex
            }
            else {
              result += null
            }
          }
          !content.startsWith("[") && !content.startsWith("{") && !content.startsWith("\"") && !content.startsWith("'") &&
          keySeparator(content) != null -> {
            // "- key: value" starts a mapping whose keys are indented like "key"
            val (value, nextIndex) = parseMapping(i, contentIndent, Line(contentIndent, content, line.number))
            result += value
            i = nextIndex
          }
          else -> result += scalarOrFlow(content)
        }
      }
      return result to i
    }

    private fun blockScalar(header: Line, nextIndex: Int, folded: Boolean): Pair<String, Int> {
      var i = nextIndex
      val firstContent = lines.getOrNull(i)?.takeIf { it.indent > header.indent } ?: return "" to i
      val lastNumber = generateSequence(i) { it + 1 }.takeWhile { lines.getOrNull(it)?.let { l -> l.indent > header.indent } == true }.last()
      val text = raw.subList(firstContent.number, lines[lastNumber].number + 1).joinToString("\n") { l ->
        if (l.length >= firstContent.indent) {
          l.substring(firstContent.indent)
        }
        else {
          l.trim()
        }
      }
      i = lastNumber + 1
      return (when {
        folded -> text.lines().joinToString(" ").trim()
        else -> text.trimEnd()
      }) to i
    }

    private fun isItem(text: String): Boolean = text == "-" || text.startsWith("- ")
  }

  private fun scalarOrFlow(value: String): Any? = when {
    value.startsWith("[") && value.endsWith("]") -> splitFlow(value.substring(1, value.length - 1)).map { scalar(it) }
    value.startsWith("{") && value.endsWith("}") -> splitFlow(value.substring(1, value.length - 1)).mapNotNull { entry ->
      val colon = keySeparator(entry) ?: return@mapNotNull null
      unquote(entry.substring(0, colon).trim()) to scalar(entry.substring(colon + 1).trim())
    }.toMap()
    else -> scalar(value)
  }

  private fun scalar(value: String): String? = when {
    value == "~" || value == "null" -> null
    else -> unquote(value)
  }

  private fun unquote(value: String): String = when {
    value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") -> value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\n", "\n")
    value.length >= 2 && value.startsWith("'") && value.endsWith("'") -> value.substring(1, value.length - 1).replace("''", "'")
    else -> value
  }

  private fun splitFlow(content: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    for (c in content) {
      when {
        quote != null -> {
          current.append(c)
          if (c == quote) {
            quote = null
          }
        }
        c == '"' || c == '\'' -> { current.append(c); quote = c }
        c == ',' -> { parts += current.toString().trim(); current.clear() }
        else -> current.append(c)
      }
    }
    if (current.isNotBlank()) {
      parts += current.toString().trim()
    }
    return parts.filter { it.isNotEmpty() }
  }

  /** Index of the `:` that ends a mapping key (followed by a space or the end), outside quotes. */
  private fun keySeparator(text: String): Int? {
    var quote: Char? = null
    for ((i, c) in text.withIndex()) {
      when {
        quote != null -> if (c == quote) {
          quote = null
        }
        (c == '"' || c == '\'') && i == 0 -> quote = c
        c == ':' && (i == text.lastIndex || text[i + 1] == ' ') -> return i
      }
    }
    return null
  }

  private fun stripComment(line: String): String {
    var quote: Char? = null
    for ((i, c) in line.withIndex()) {
      when {
        quote != null -> if (c == quote) {
          quote = null
        }
        c == '"' || c == '\'' -> quote = c
        c == '#' && (i == 0 || line[i - 1] == ' ') -> return line.substring(0, i)
      }
    }
    return line
  }
}
