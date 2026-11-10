package com.papyrus.app.viewer

// Markwon 4.6.2 knows no `[^id]` syntax, so the source is rewritten before parsing: markers become superscript links to a list at the end, each entry linking back through in-document anchors the viewer scrolls to itself ([target]).
// Entries are numbered in the order first referenced.
object MarkdownFootnotes {

    private const val DEFAULT_HEADING = "Footnotes"
    private const val FOOTNOTE_PREFIX = "fn-"
    private const val BACK_PREFIX = "fnref-"

    // A real superscript needs HTML, which the renderer does not take.
    private const val SUPERSCRIPT_DIGITS = "\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079"

    private const val BACK_LABEL = "\u2191"

    private val DEFINITION = Regex("""^ {0,3}\[\^([^\]\s^]+)]:[ \t]*(.*)$""")
    private val REFERENCE = Regex("""\[\^([^\]\s^]+)]""")
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    private val LINE_BREAK = Regex("\r?\n")

    // Two labels differing only in characters this replaces would share an anchor, and a tap would land on the first.
    private val UNSAFE_ANCHOR = Regex("[^A-Za-z0-9_.:-]")

    private class BodyLine(val text: String, val scan: Boolean)

    // A source with no definitions is returned as it is, line endings and all.
    fun transform(source: String, heading: String = DEFAULT_HEADING): String {
        if (!source.contains("[^")) return source
        val lines = source.split(LINE_BREAK)
        val body = ArrayList<BodyLine>(lines.size)
        val definitions = LinkedHashMap<String, List<String>>()

        var fence: String? = null
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val open = fence
            if (open != null) {
                body += BodyLine(line, scan = false)
                if (closesFence(line, open)) fence = null
                index++
                continue
            }
            val opening = FENCE.matchEntire(line)
            // A backtick fence's info string cannot hold a backtick; ```x``` is inline code.
            if (opening != null && !(opening.groupValues[1][0] == '`' && opening.groupValues[2].contains('`'))) {
                fence = opening.groupValues[1]
                body += BodyLine(line, scan = false)
                index++
                continue
            }
            val definition = DEFINITION.matchEntire(line)
            if (definition == null) {
                body += BodyLine(line, scan = true)
                index++
                continue
            }
            val id = definition.groupValues[1]
            if (id in definitions) {
                body += BodyLine(line, scan = false)
                index++
                continue
            }
            val text = arrayListOf(definition.groupValues[2].trimEnd())
            // `end` follows the last indented line taken, so blank lines after the entry stay in the body.
            var end = index + 1
            var next = end
            while (next < lines.size) {
                val candidate = lines[next]
                if (candidate.isBlank()) {
                    next++
                    continue
                }
                if (!candidate.startsWith(' ') && !candidate.startsWith('\t')) break
                repeat(next - end) { text += "" }
                text += dedented(candidate).trimEnd()
                next++
                end = next
            }
            definitions[id] = text
            index = end
        }
        if (definitions.isEmpty()) return source
        // A fence left open runs to the end of the document, and the list must not end up inside it.
        fence?.let { marker ->
            while (body.lastOrNull()?.text?.isBlank() == true) body.removeAt(body.lastIndex)
            body += BodyLine(marker, scan = false)
        }

        val order = ArrayList<String>()
        val numbers = HashMap<String, Int>()
        fun number(id: String): Int? {
            if (id !in definitions) return null
            return numbers.getOrPut(id) {
                order += id
                order.size
            }
        }

        val rewritten = body.map { if (it.scan) markReferences(it.text, ::number) else it.text }
        // An entry can refer to another, so the list is read as it grows; what nothing refers to comes last.
        val entries = HashMap<String, List<String>>()
        var done = 0
        while (true) {
            while (done < order.size) {
                val id = order[done++]
                entries[id] = definitions.getValue(id).map { markReferences(it, ::number) }
            }
            val unreferenced = definitions.keys.firstOrNull { it !in numbers } ?: break
            number(unreferenced)
        }

        val out = StringBuilder(rewritten.joinToString("\n").trimEnd())
        if (out.isNotEmpty()) out.append("\n\n")
        out.append("## ").append(heading).append("\n\n")
        for ((position, id) in order.withIndex()) {
            if (position > 0) out.append('\n')
            out.append(entry(position + 1, id, entries.getValue(id)))
        }
        return out.toString()
    }

    fun counterpart(url: String): String? = when {
        url.startsWith("#$BACK_PREFIX") -> "#$FOOTNOTE_PREFIX" + url.removePrefix("#$BACK_PREFIX")
        url.startsWith("#$FOOTNOTE_PREFIX") -> "#$BACK_PREFIX" + url.removePrefix("#$FOOTNOTE_PREFIX")
        else -> null
    }

    fun isAnchor(url: String): Boolean = counterpart(url) != null

    // The first answering link wins if several do (a footnote cited twice has one way back and two markers).
    fun target(links: List<Pair<Int, String>>, url: String): Int? {
        val answer = counterpart(url) ?: return null
        return links.filter { it.second == answer }.minOfOrNull { it.first }
    }

    private fun anchorOf(id: String): String = id.replace(UNSAFE_ANCHOR, "_")

    private fun superscript(number: Int): String =
        number.toString().map { SUPERSCRIPT_DIGITS[it - '0'] }.joinToString("")

    private fun closesFence(line: String, open: String): Boolean {
        val trimmed = line.trimStart(' ')
        if (line.length - trimmed.length > 3) return false
        val run = trimmed.takeWhile { it == open[0] }
        return run.length >= open.length && trimmed.substring(run.length).isBlank()
    }

    private fun dedented(line: String): String {
        val spaces = line.takeWhile { it == ' ' }.length
        return if (spaces > 0) line.substring(minOf(spaces, 4)) else line.removePrefix("\t")
    }

    private fun markReferences(text: String, number: (String) -> Int?): String {
        if (!text.contains("[^")) return text
        val out = StringBuilder(text.length + 16)
        var at = 0
        while (at < text.length) {
            val c = text[at]
            if (c == '\\' && at + 1 < text.length) {
                out.append(c).append(text[at + 1])
                at += 2
                continue
            }
            if (c == '`') {
                var run = 0
                while (at + run < text.length && text[at + run] == '`') run++
                val close = closingRun(text, at + run, run)
                // An unmatched run is literal backticks, and the text after it is not code.
                val stop = if (close < 0) at + run else close + run
                out.append(text, at, stop)
                at = stop
                continue
            }
            if (c == '[') {
                val match = REFERENCE.matchAt(text, at)
                val id = match?.groupValues?.get(1)
                val n = id?.let(number)
                if (match != null && id != null && n != null) {
                    out.append('[').append(superscript(n)).append("](#").append(FOOTNOTE_PREFIX).append(anchorOf(id)).append(')')
                    at += match.value.length
                    continue
                }
            }
            out.append(c)
            at++
        }
        return out.toString()
    }

    private fun closingRun(text: String, from: Int, length: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] != '`') {
                i++
                continue
            }
            var j = i
            while (j < text.length && text[j] == '`') j++
            if (j - i == length) return i
            i = j
        }
        return -1
    }

    private fun entry(number: Int, id: String, text: List<String>): String {
        val marker = "$number. "
        val back = "[$BACK_LABEL](#$BACK_PREFIX${anchorOf(id)})"
        val lines = text.toMutableList()
        val last = lines.last()
        val fenced = last.trimStart().let { it.startsWith("```") || it.startsWith("~~~") }
        // Text after a closing fence would stop it closing, and the code would swallow the rest of the document.
        if (fenced) lines += back else lines[lines.lastIndex] = if (last.isEmpty()) back else "$last $back"
        return lines.withIndex().joinToString("\n") { (i, line) ->
            when {
                i == 0 -> if (line.isEmpty()) marker.trimEnd() else marker + line
                line.isEmpty() -> ""
                else -> " ".repeat(marker.length) + line
            }
        }
    }
}
