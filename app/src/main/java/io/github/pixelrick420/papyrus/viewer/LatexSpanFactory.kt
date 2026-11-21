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
        val regex = Regex("(?s)\\$\\$(.+?)\\$\\$")
        return regex.findAll(source).take(LATEX_MAX_RANGES).map { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val latex = match.groupValues[1].trim()
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
