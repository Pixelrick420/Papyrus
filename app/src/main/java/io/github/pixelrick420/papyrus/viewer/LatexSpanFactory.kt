package io.github.pixelrick420.papyrus.viewer

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXSpan

// Markwon produces a [Spanned] synchronously but [RaTeXSpan.create] is suspend, so ranges are detected up front and spans are made in small batches as the caller consumes them.
// A failed or oversized formula keeps its visible placeholder text.
internal const val LATEX_MAX_RANGES = 128
internal const val LATEX_MAX_FORMULA_CHARS = 4096

private const val RENDER_BATCH = 8

internal val LATEX_RANGE_REGEX = Regex("""(?s)\$\$(.+?)\$\$""")

private fun maskNonMathDollars(source: String): String {
    val masked = source.toCharArray()
    fun blank(from: Int, until: Int) {
        for (i in from until until) if (masked[i] == '$') masked[i] = ' '
    }

    var pos = 0
    var fence: Char? = null
    var fenceLength = 0
    while (pos < source.length) {
        val lineEnd = source.indexOf('\n', pos).let { if (it < 0) source.length else it }
        var indent = pos
        while (indent < lineEnd && source[indent] == ' ') indent++
        val marker = source.getOrNull(indent)
        if (fence != null) {
            var end = indent
            while (end < lineEnd && source[end] == fence) end++
            if (end - indent >= fenceLength && (end == lineEnd || source[end] == '\r')) {
                fence = null
                fenceLength = 0
            } else {
                blank(pos, lineEnd)
            }
        } else if (indent - pos >= 4) {
            blank(pos, lineEnd)
        } else if (marker == '`' || marker == '~') {
            var end = indent
            while (end < lineEnd && source[end] == marker) end++
            if (end - indent >= 3) {
                fence = marker
                fenceLength = end - indent
                blank(pos, lineEnd)
            }
        }
        pos = if (lineEnd < source.length) lineEnd + 1 else source.length
    }

    var i = 0
    while (i < source.length) {
        when {
            source.startsWith("<!--", i) -> {
                val end = source.indexOf("-->", i + 4)
                val stop = if (end < 0) source.length else end + 3
                blank(i, stop)
                i = stop
            }
            source[i] == '`' -> {
                var run = 1
                while (i + run < source.length && source[i + run] == '`') run++
                var close = i + run
                while (close < source.length) {
                    if (source[close] == '`' && (close == 0 || source[close - 1] != '`') &&
                        (close + run == source.length || source[close + run] != '`')
                    ) break
                    close++
                }
                if (close < source.length) {
                    blank(i, close + run)
                    i = close + run
                } else {
                    i += run
                }
            }
            source[i] == '[' -> {
                val labelEnd = labelEnd(source, i)
                if (labelEnd >= 0 && source.getOrNull(labelEnd) == '(') {
                    val destinationEnd = destinationEnd(source, labelEnd + 1)
                    blank(labelEnd, destinationEnd)
                    i = destinationEnd
                } else {
                    i++
                }
            }
            else -> i++
        }
    }
    return String(masked)
}

private fun labelEnd(source: String, start: Int): Int {
    var depth = 0
    var i = start
    while (i < source.length) {
        when (source[i]) {
            '\\' -> i++
            '[' -> depth++
            ']' -> if (--depth == 0) return i + 1
            '\n', '\r' -> return -1
        }
        i++
    }
    return -1
}

private fun destinationEnd(source: String, start: Int): Int {
    var i = start
    if (source.getOrNull(i) == '<') {
        val close = source.indexOf('>', i + 1)
        if (close < 0) return source.length
        i = close + 1
    }
    var depth = 1
    while (i < source.length) {
        when (source[i]) {
            '\\' -> i++
            '(' -> depth++
            ')' -> if (--depth == 0) return i + 1
        }
        i++
    }
    return source.length
}

class LatexSpanFactory(
    private val context: Context?,
    source: String,
) {
    val ranges: List<LatexRange>

    init {
        ranges = detectRanges(source)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun createSpans(scope: CoroutineScope, fontSizePx: Float, color: Int): List<Deferred<Pair<LatexRange, RaTeXSpan?>>> {
        // Load fonts from assets before creating spans; RaTeX needs them available to render.
        val ctx = requireNotNull(context)
        try {
            RaTeXFontLoader.loadFromAssets(ctx, "fonts")
        } catch (e: Exception) {
            // Fonts may already be loaded or unavailable; span creation is still attempted.
        }

        val results = List(ranges.size) { CompletableDeferred<Pair<LatexRange, RaTeXSpan?>>() }
        val started = BooleanArray((ranges.size + RENDER_BATCH - 1) / RENDER_BATCH)
        return object : AbstractList<Deferred<Pair<LatexRange, RaTeXSpan?>>>() {
            override val size: Int get() = results.size

            override fun get(index: Int): Deferred<Pair<LatexRange, RaTeXSpan?>> {
                if (index !in results.indices) throw IndexOutOfBoundsException("span $index of ${results.size}")
                val batch = index / RENDER_BATCH
                synchronized(started) {
                    if (!started[batch]) {
                        started[batch] = true
                        val from = batch * RENDER_BATCH
                        val to = minOf(from + RENDER_BATCH, ranges.size)
                        scope.launch {
                            for (i in from until to) results[i].complete(render(ctx, ranges[i], fontSizePx, color))
                        }
                    }
                }
                return results[index]
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun render(ctx: Context, range: LatexRange, fontSizePx: Float, color: Int): Pair<LatexRange, RaTeXSpan?> {
        val span = if (!range.renderable) null else try {
            RaTeXSpan.create(ctx, range.latex, fontSizePx * 0.65f, color, range.isBlock)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return range to span
    }

    private fun detectRanges(source: String): List<LatexRange> {
        return LATEX_RANGE_REGEX.findAll(maskNonMathDollars(source)).take(LATEX_MAX_RANGES).map { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val latex = match.groups[1]?.let { source.substring(it.range) }.orEmpty().trim()
            LatexRange(start, end, latex, isBlockFormula(source, match.range), latex.length <= LATEX_MAX_FORMULA_CHARS)
        }.toList()
    }

    private fun isBlockFormula(source: String, range: IntRange): Boolean {
        // If the formula is the entire document and a single line, treat as inline:
        // there's no surrounding line context. Multi-line documents fall through to line checks.
        if (range.first == 0 && range.last + 1 == source.length) {
            val newline = source.indexOf('\n', range.first)
            if (newline < 0 || newline >= range.last + 1) return false
        }

        val start = range.first
        val endExclusive = range.last + 1

        var lineStart = start
        while (lineStart > 0 && source[lineStart - 1] != '\n') {
            lineStart--
        }
        val beforeIsBlank = source.substring(lineStart, start).all { it.isWhitespace() }

        var lineEnd = endExclusive
        while (lineEnd < source.length && source[lineEnd] != '\n') {
            lineEnd++
        }
        val afterIsBlank = source.substring(endExclusive, lineEnd).all { it.isWhitespace() }

        return beforeIsBlank && afterIsBlank
    }
}

data class LatexRange(
    val start: Int,
    val end: Int,
    val latex: String,
    val isBlock: Boolean,
    val renderable: Boolean = true,
)
