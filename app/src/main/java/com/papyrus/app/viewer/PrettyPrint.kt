package com.papyrus.app.viewer

enum class PrettyKind { JSON, XML, HTML }

class PrettyOutcome(val text: String, val failed: Boolean)

// Only whitespace outside strings, comments and text nodes is re-laid, so the stored file's content is never changed.
// Hand-written scanners rather than a parser library: they only re-lay whitespace, handle JSONC comments and non-well-formed HTML alike, and never throw.
object PrettyPrint {

    const val DEFAULT_INDENT = 2

    val INDENT_CHOICES = listOf(2, 3, 4, 8)

    fun indentOrDefault(spaces: Int): Int = if (spaces in INDENT_CHOICES) spaces else DEFAULT_INDENT

    fun kindFor(displayName: String?): PrettyKind? {
        val name = displayName?.lowercase().orEmpty()
        if (name.isEmpty()) return null
        val extension = if (name.contains('.')) name.substringAfterLast('.') else name
        return when (extension) {
            "json", "jsonc", "json5" -> PrettyKind.JSON
            "xml", "svg", "xhtml" -> PrettyKind.XML
            "html", "htm" -> PrettyKind.HTML
            else -> null
        }
    }

    // A scanner needs the whole file as one string and builds a second for the result; this is the limit the plain-text reader had before it streamed.
    const val MAX_FORMAT_BYTES = 32L * 1024 * 1024

    // A size of zero or less means unknown, which passes; the read stops at the limit.
    fun kindToApply(displayName: String?, sizeBytes: Long, enabled: Boolean): PrettyKind? {
        if (!enabled || sizeBytes > MAX_FORMAT_BYTES) return null
        return kindFor(displayName)
    }

    fun formatOnOpen(kind: PrettyKind, text: String, indent: Int = DEFAULT_INDENT): PrettyOutcome {
        val formatted = format(kind, text, indent) ?: return PrettyOutcome(text, failed = true)
        return PrettyOutcome(formatted, failed = false)
    }

    fun format(kind: PrettyKind, text: String, indent: Int = DEFAULT_INDENT): String? {
        val step = " ".repeat(indentOrDefault(indent))
        return when (kind) {
            PrettyKind.JSON -> formatJson(text, step)
            PrettyKind.XML -> formatMarkup(strict = true, text, step)
            PrettyKind.HTML -> formatMarkup(strict = false, text, step)
        }
    }
}

// A newline is kept off a preceding `//` comment's line, so the comma never lands inside the comment.
// Strings and comments are copied byte-for-byte, so escapes, number lexemes (1E+3, 12345678901234567890) and key order are untouched.
private fun formatJson(text: String, step: String): String? {
    // Nothing to format; the stored bytes pass through, no toast.
    if (text.isBlank()) return text
    val layout = JsonLayout(step)
    val out = layout.out
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isWhitespace() -> i++
            c == '"' -> {
                val end = jsonStringEnd(text, i) ?: return null
                layout.startToken()
                out.append(text, i, end)
                i = end
            }
            c == '{' || c == '[' -> {
                layout.startToken()
                out.append(c)
                layout.opened.append(c)
                layout.indent++
                i++
            }
            c == '}' || c == ']' -> {
                if (!layout.closeBracket(c)) return null
                i++
            }
            c == ',' -> {
                // After a // comment the next line starts here: break first so the comma is
                // structure, not comment text.
                if (layout.pendingBreak) layout.startToken()
                out.append(',')
                layout.pendingBreak = true
                i++
            }
            c == ':' -> {
                out.append(": ")
                layout.attach = true
                i++
            }
            c == '/' && text.nextIs(i, '/') -> {
                val end = lineCommentEnd(text, i)
                layout.startToken()
                out.append(text, i, end)
                layout.pendingBreak = true
                i = end
            }
            c == '/' && text.nextIs(i, '*') -> {
                val end = blockCommentEnd(text, i) ?: return null
                layout.startToken()
                out.append(text, i, end)
                i = end
            }
            else -> {
                // A run of literal characters (number, true/false/null, stray garbage): one token,
                // copied verbatim.
                val end = jsonLiteralEnd(text, i)
                layout.startToken()
                out.append(text, i, end)
                i = end
            }
        }
    }
    if (layout.opened.isNotEmpty()) return null
    return out.toString()
}

private class JsonLayout(private val step: String) {
    val out = StringBuilder()
    var indent = 0

    var attach = false

    var pendingBreak = false

    val opened = StringBuilder()

    fun startToken() {
        if (attach) {
            attach = false
            return
        }
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        repeat(indent) { out.append(step) }
        pendingBreak = false
    }

    // Null on a mismatch or a close with none open: both cover truncated and hand-broken files.
    fun closeBracket(c: Char): Boolean {
        val expected = if (c == '}') '{' else '['
        if (opened.isEmpty() || opened.last() != expected) return false
        opened.setLength(opened.length - 1)
        indent--
        if (out.lastOrNull() == expected) {
            // An empty {} / [] never took a line of its own; it stays on one.
            out.append(c)
        } else {
            startToken()
            out.append(c)
        }
        attach = false
        return true
    }
}

private fun jsonStringEnd(text: String, start: Int): Int? {
    var i = start + 1
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i += 2 // an escaped character cannot end the string, whatever it is
            '"' -> return i + 1
            else -> i++
        }
    }
    return null
}

private fun lineCommentEnd(text: String, start: Int): Int {
    var i = start
    while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
    return i
}

private fun blockCommentEnd(text: String, start: Int): Int? {
    val end = text.indexOf("*/", start + 2)
    return if (end < 0) null else end + 2
}

private fun jsonLiteralEnd(text: String, start: Int): Int {
    var i = start
    while (i < text.length && text[i].let { !it.isWhitespace() && it !in ",]}:[{\"/" }) i++
    return i
}

private fun String.nextIs(at: Int, c: Char) = at + 1 < length && this[at + 1] == c

// Every construct starts a line of its own unless it directly follows non-blank text, which keeps mixed content (`<p>a <em>b</em> c</p>`) inline.
// Tag interiors, text nodes and comment/CDATA/PI/doctype bodies are copied verbatim.
private fun formatMarkup(strict: Boolean, text: String, step: String): String? {
    // Nothing to format; the stored bytes pass through, no toast.
    if (text.isBlank()) return text
    val out = StringBuilder(text.length + text.length / 8)
    val stack = ArrayList<String>()
    var inline = false
    var i = 0
    while (i < text.length) {
        if (text[i] != '<') {
            val end = text.indexOf('<', i).let { if (it < 0) text.length else it }
            val node = text.substring(i, end)
            // Blank text between constructs is the whitespace being re-laid; real text is content
            // and stays byte-for-byte, and a construct right after it stays on its line.
            if (node.isNotBlank()) {
                out.append(node)
                inline = true
            }
            i = end
            continue
        }
        if (!text.startsConstruct(i)) {
            // A bare `<` (`a < b`): text, not a tag.
            out.append('<')
            i++
            continue
        }
        when {
            text.startsWith("<!--", i) -> {
                val end = delimitedEnd(text, i, "-->", 4) ?: return null
                i = emit(out, stack.size, inline, text, i, end, step)
                inline = false
            }
            text.startsWith("<![CDATA[", i) -> {
                val end = delimitedEnd(text, i, "]]>", 9) ?: return null
                i = emit(out, stack.size, inline, text, i, end, step)
                inline = false
            }
            text.startsWith("<?", i) -> {
                val end = delimitedEnd(text, i, "?>", 2) ?: return null
                i = emit(out, stack.size, inline, text, i, end, step)
                inline = false
            }
            text.startsWith("<!", i) -> {
                val end = doctypeEnd(text, i) ?: return null
                i = emit(out, stack.size, inline, text, i, end, step)
                inline = false
            }
            else -> {
                val construct = tagConstruct(text, i) ?: return null
                // A close tag is indented at its own level, which is one above its content.
                val depth = if (construct.closing) (stack.size - 1).coerceAtLeast(0) else stack.size
                i = emit(out, depth, inline, text, i, construct.end, step)
                if (!applyToStack(stack, construct, strict)) return null
                inline = false
                if (!strict && !construct.closing && !construct.selfClosing &&
                    construct.name.orEmpty().lowercase() in RAW_TEXT_ELEMENTS
                ) {
                    val body = rawBodyEnd(text, i, construct.name.orEmpty()) ?: return null
                    if (body > i) {
                        out.append(text, i, body)
                        inline = true
                    }
                    i = body
                }
            }
        }
    }
    if (strict && stack.isNotEmpty()) return null
    return out.toString()
}

private fun emit(
    out: StringBuilder,
    depth: Int,
    inline: Boolean,
    text: String,
    from: Int,
    to: Int,
    step: String,
): Int {
    if (!inline) {
        if (out.isNotEmpty()) out.append('\n')
        repeat(depth) { out.append(step) }
    }
    out.append(text, from, to)
    return to
}

private class MarkupConstruct(val end: Int, val name: String?, val closing: Boolean, val selfClosing: Boolean)

private fun String.startsConstruct(at: Int): Boolean {
    if (at + 1 >= length) return false
    val next = this[at + 1]
    return next.isTagNameStart() || next == '/' || next == '!' || next == '?'
}

private fun delimitedEnd(text: String, start: Int, terminator: String, bodyOffset: Int): Int? {
    val close = text.indexOf(terminator, start + bodyOffset)
    return if (close < 0) null else close + terminator.length
}

private fun doctypeEnd(text: String, start: Int): Int? {
    var depth = 0
    var i = start + 2
    while (i < text.length) {
        when (text[i]) {
            '[' -> depth++
            ']' -> depth--
            '>' -> if (depth <= 0) return i + 1
        }
        i++
    }
    return null
}

private fun tagConstruct(text: String, start: Int): MarkupConstruct? {
    val closing = text.startsWith("</", start)
    var i = if (closing) start + 2 else start + 1
    val nameStart = i
    while (i < text.length && text[i].isTagNameChar()) i++
    val name = text.substring(nameStart, i)
    if (name.isEmpty()) return null
    var quote = NO_QUOTE
    while (i < text.length) {
        val c = text[i]
        when {
            quote != NO_QUOTE -> if (c == quote) quote = NO_QUOTE
            c == '"' || c == '\'' -> quote = c
            c == '>' -> return MarkupConstruct(
                end = i + 1,
                name = name,
                closing = closing,
                selfClosing = !closing && text.getOrNull(i - 1) == '/',
            )
        }
        i++
    }
    return null
}

// XML matches strictly and fails on a mismatch or stray close; HTML never fails: a close drops its element and anything an implied close (`<li>a<li>b`) left open above it.
private fun applyToStack(stack: MutableList<String>, construct: MarkupConstruct, strict: Boolean): Boolean {
    val name = construct.name.orEmpty()
    if (construct.closing) {
        if (strict) {
            if (stack.isEmpty() || stack.last() != name) return false
            stack.removeAt(stack.lastIndex)
        } else {
            val at = stack.indexOfLast { it.equals(name, ignoreCase = true) }
            if (at >= 0) stack.subList(at, stack.size).clear()
        }
    } else if (!construct.selfClosing && (strict || name.lowercase() !in VOID_ELEMENTS)) {
        stack += name
    }
    return true
}

private fun rawBodyEnd(text: String, from: Int, name: String): Int? {
    val needle = "</$name"
    var i = from
    while (i <= text.length - needle.length) {
        if (text.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
        i++
    }
    return null
}

/** HTML elements with no closing tag; pushing one would drift every indent below it. */
private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
)

/** HTML elements whose bodies are code, not markup: `<` and `>` inside them are not tags. */
private val RAW_TEXT_ELEMENTS = setOf("script", "style")

private const val NO_QUOTE = ' '

private fun Char.isTagNameStart() = this in 'a'..'z' || this in 'A'..'Z' || this == '_' || this == ':'

private fun Char.isTagNameChar() = isTagNameStart() || this in '0'..'9' || this == '-' || this == '.'
