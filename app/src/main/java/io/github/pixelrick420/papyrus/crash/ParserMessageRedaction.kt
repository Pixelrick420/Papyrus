package io.github.pixelrick420.papyrus.crash

// Packages whose exceptions quote the document they were parsing: the XML pull and SAX parsers and PDFBox.
private val PARSER_PACKAGES = listOf(
    "org.xmlpull.",
    "org.xml.sax.",
    "javax.xml.",
    "com.android.org.kxml2.",
    "org.kxml2.",
    "com.tom_roush.",
    "org.apache.pdfbox.",
    "org.apache.fontbox.",
)

private const val MAX_MESSAGE_CHARS = 120

// `Class: message`, or `Class; lineNumber: …` as SAXParseException prints itself.
private val HEADER = Regex("""^(\s*(?:Caused by: |Suppressed: )?)([\w$.]+)(?:([:;])\s?(.*))?$""")
private val FRAME = Regex("""^\s*at\s+(\S+)""")
private val CHAIN = Regex("""^\s*(?:\.\.\. \d+ (?:more|common frames omitted)|Caused by: |Suppressed: )""")
private val WHITESPACE = Regex("""\s+""")

// kxml puts the offending text inside `(position:TEXT <text>@line:column in <reader>)`.
private val POSITION = Regex("""\(position:([A-Z_]+)\s.*?@(\d+:\d+)\s+in\s+[^)]*\)""")
private val QUOTED = Regex("""(?<!\w)(['"`])(?:(?!\1).)*\1""")
private val DANGLING_QUOTE = Regex("""(?<!\w)['"`].*$""")

/**
 * Parser exceptions quote the file being read, so their messages are cut down to the first line, with a position and
 * a short, quote-free remainder. Every other exception, and every stack frame, is left as it was.
 */
internal fun redactParserMessages(trace: String): String {
    val lines = trace.split('\n')
    val out = ArrayList<String>(lines.size)
    var i = 0
    while (i < lines.size) {
        val header = HEADER.matchEntire(lines[i])
        if (header == null) {
            out += lines[i++]
            continue
        }
        // A message can run over several lines; it ends at the first frame or chain line.
        var end = i + 1
        while (end < lines.size && !FRAME.containsMatchIn(lines[end]) && !CHAIN.containsMatchIn(lines[end])) end++
        val (lead, name, separator, firstLine) = header.destructured
        val rest = lines.subList(i + 1, end)
        val message = (listOf(firstLine) + rest).joinToString("\n")
        val firstFrame = FRAME.find(lines.getOrElse(end) { "" })?.groupValues?.get(1)
        val fromParser = isParser(name) || firstFrame?.let(::isParser) == true || PARSER_PACKAGES.any { it in message }
        if (fromParser) {
            // Later lines of a message are where a quoted snippet of the document usually sits.
            val kept = sanitize(firstLine) + if (rest.any { it.isNotBlank() }) " […]" else ""
            out += if (kept.isEmpty()) lead + name else "$lead$name$separator $kept"
        } else {
            out.addAll(lines.subList(i, end))
        }
        i = end
    }
    return out.joinToString("\n")
}

private fun isParser(className: String): Boolean = PARSER_PACKAGES.any { className.startsWith(it) }

private fun sanitize(message: String): String {
    val flat = message.replace(WHITESPACE, " ")
    val located = POSITION.replace(flat) { "(position:${it.groupValues[1]} @${it.groupValues[2]})" }
    val unquoted = DANGLING_QUOTE.replace(QUOTED.replace(located, "[quoted]"), "[quoted]")
    return if (unquoted.length > MAX_MESSAGE_CHARS) unquoted.take(MAX_MESSAGE_CHARS) + "…" else unquoted
}
