package io.github.pixelrick420.papyrus.viewer

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXSpan

// Markwon produces a [Spanned] synchronously but [RaTeXSpan.create] is suspend, so ranges are detected up front and span creation is launched per range.
// On failure the caller keeps the visible placeholder text.
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

        return ranges.map { range ->
            scope.async {
                val span = try {
                    RaTeXSpan.create(ctx, range.latex, fontSizePx * 0.65f, color, range.isBlock)
                } catch (e: Exception) {
                    null
                }
                Pair(range, span)
            }
        }
    }

    private fun detectRanges(source: String): List<LatexRange> {
        val regex = Regex("(?s)\\$\\$(.+?)\\$\\$")
        return regex.findAll(source).toList().map { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val latex = match.groupValues[1].trim()
            LatexRange(start, end, latex, isBlockFormula(source, match.range))
        }
    }

    private fun isBlockFormula(source: String, range: IntRange): Boolean {
        // If the formula is the entire document and a single line, treat as inline:
        // there's no surrounding line context. Multi-line documents fall through to line checks.
        if (range.first == 0 && range.last + 1 == source.length) {
            val formulaText = source.substring(range.first, range.last + 1)
            if (!formulaText.contains('\n')) return false
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
)
