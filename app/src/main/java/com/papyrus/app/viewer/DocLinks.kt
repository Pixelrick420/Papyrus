package com.papyrus.app.viewer

import java.util.TreeSet

/** `[start, end)` of a block's text that links to [url]. */
internal class LinkRange(val start: Int, val end: Int, val url: String)

/**
 * Where a `.doc` link points. A hyperlink is a field, `0x13 HYPERLINK "target" 0x14 shown 0x15`; that
 * is the real thing, and [hyperlinkTarget] reads its instruction. Text that only looks like a link, a
 * pasted address nothing was ever attached to, is found by [find]. Either way only the schemes
 * [safeLink] lets through ever become a link.
 */
internal object DocLinks {

    /** The longest field instruction kept; a target past it is not trusted, so such a field links nowhere. */
    const val MAX_INSTRUCTION = 4096

    private val SWITCH_WITH_ARGUMENT = setOf("\\l", "\\o", "\\t")
    private val BARE_URL = Regex("(?<![\\p{L}\\p{N}])(?:https?://|mailto:|tel:)[^\\s<>\"\\u00A0]+", RegexOption.IGNORE_CASE)
    private val SCHEMES = listOf("https://", "http://", "mailto:", "tel:")
    private const val HYPERLINK = "HYPERLINK"
    private const val TRAILING_PUNCTUATION = ".,;:!?'\""

    /**
     * The target of a field instruction such as `HYPERLINK "https://x.org" \o "tip"`, or null if it is
     * not a hyperlink, has no target (a `\l` bookmark alone is a place in the document), or the target
     * is not a safe link. The target may be quoted or bare; the switches `\l`, `\o` and `\t` take an argument.
     */
    fun hyperlinkTarget(instruction: CharSequence): String? {
        val text = instruction.toString().trim()
        if (!text.startsWith(HYPERLINK, ignoreCase = true)) return null
        var pos = HYPERLINK.length
        if (pos < text.length && !text[pos].isWhitespace()) return null
        while (true) {
            pos = skipSpaces(text, pos)
            if (pos >= text.length) return null
            val token = readToken(text, pos)
            pos = token.second
            val value = token.first
            when {
                SWITCH_WITH_ARGUMENT.any { value.equals(it, ignoreCase = true) } -> {
                    pos = readToken(text, skipSpaces(text, pos)).second
                }
                value.startsWith("\\") -> Unit
                else -> return safeLink(value)
            }
        }
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var pos = from
        while (pos < text.length && text[pos].isWhitespace()) pos++
        return pos
    }

    /** The token at [from]: a quoted string without its quotes, or the run up to the next space; and where it ends. */
    private fun readToken(text: String, from: Int): Pair<String, Int> {
        if (from >= text.length) return "" to from
        if (text[from] == '"') {
            val close = text.indexOf('"', from + 1)
            return if (close < 0) text.substring(from + 1) to text.length else text.substring(from + 1, close) to close + 1
        }
        var end = from
        while (end < text.length && !text[end].isWhitespace()) end++
        return text.substring(from, end) to end
    }

    /** Addresses written out in [text]: `http://`, `https://`, `mailto:` and `tel:` followed by what makes up one. */
    fun find(text: String): List<LinkRange> {
        if (text.indexOf(':') < 0) return emptyList()
        val out = ArrayList<LinkRange>()
        for (match in BARE_URL.findAll(text)) {
            val start = match.range.first
            val end = start + trimmedLength(match.value)
            // A bare scheme is not an address: something must follow it.
            if (end - start <= schemeLength(match.value)) continue
            val url = safeLink(text.substring(start, end)) ?: continue
            out += LinkRange(start, end, url)
        }
        return out
    }

    private fun schemeLength(url: String): Int {
        val lower = url.lowercase()
        return SCHEMES.first { lower.startsWith(it) }.length
    }

    /** [url]'s length without the punctuation that ends a sentence or closes a bracket the address did not open. */
    private fun trimmedLength(url: String): Int {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            val closes = c == ')' || c == ']' || c == '}'
            val strip = c in TRAILING_PUNCTUATION || (closes && unbalanced(url, end, c))
            if (!strip) break
            end--
        }
        return end
    }

    /** True if the closer [closer] at `url[end - 1]` has no opener before it in `url[0, end)`. */
    private fun unbalanced(url: String, end: Int, closer: Char): Boolean {
        val opener = when (closer) {
            ')' -> '('
            ']' -> '['
            else -> '{'
        }
        var depth = 0
        for (i in 0 until end) {
            if (url[i] == opener) depth++ else if (url[i] == closer) depth--
        }
        return depth < 0
    }

    /**
     * [spans] with each of [links] laid over the text it covers, keeping what the spans already say: a
     * bold run that is also an address becomes bold and linked. A stretch that already links elsewhere
     * keeps its own link. Both lists are in order and do not overlap within themselves.
     */
    fun overlay(spans: List<TextSpan>, links: List<LinkRange>): List<TextSpan> {
        if (links.isEmpty()) return spans
        val cuts = TreeSet<Int>()
        for (span in spans) {
            cuts += span.start
            cuts += span.end
        }
        for (link in links) {
            cuts += link.start
            cuts += link.end
        }
        val bounds = cuts.toIntArray()
        val out = ArrayList<TextSpan>()
        var s = 0
        var l = 0
        for (i in 0 until bounds.size - 1) {
            val from = bounds[i]
            val to = bounds[i + 1]
            while (s < spans.size && spans[s].end <= from) s++
            while (l < links.size && links[l].end <= from) l++
            val span = spans.getOrNull(s)?.takeIf { it.start <= from && it.end >= to }
            val link = links.getOrNull(l)?.takeIf { it.start <= from && it.end >= to }
            var format = span?.format ?: CharFormat.PLAIN
            if (link != null && format.link == null) format = format.copy(link = link.url)
            if (format.isPlain) continue
            val last = out.lastOrNull()
            if (last != null && last.end == from && last.format == format) {
                out[out.lastIndex] = last.copy(end = to)
            } else {
                out += TextSpan(from, to, format)
            }
        }
        return out
    }
}
