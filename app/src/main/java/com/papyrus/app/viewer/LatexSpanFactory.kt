package com.papyrus.app.viewer

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXSpan

/**
 * Scans a Markdown source for $$...$$ LaTeX formulas and prepares asynchronous [RaTeXSpan] creation.
 *
 * Markwon produces a [Spanned] synchronously; [RaTeXSpan.create] is suspend and returns a
 * [ReplacementSpan], so this factory holds detected ranges and launches span creation per range
 * on a caller-supplied coroutine scope. Block vs inline is decided by whether $$...$$ occupies
 * an entire line. Graceful fallback: if [RaTeXSpan.create] throws or returns null, the caller keeps
 * the visible placeholder text - no crash, no missing formula.
 */
class LatexSpanFactory(
    /** The Android context, passed to [RaTeXSpan.create]. Nullable so range-detection can be unit-tested without a real Context. */
    private val context: Context?,
    source: String,
) {
    /** All detected $$...$$ ranges in [source], in order of appearance. */
    val ranges: List<LatexRange>

    init {
        ranges = detectRanges(source)
    }

    /**
     * Launches [RaTeXSpan.create] for each range on [scope], returning one deferred per range.
     * Each deferred resolves to [Pair] of the [LatexRange] and its [RaTeXSpan] (null if creation
     * failed - the caller keeps the visible placeholder text as graceful fallback).
     */
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

    /** True when the $$...$$ occupies an entire line (block formula), false when inline within text. */
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

/** A detected $$...$$ formula range in the Markdown source. */
data class LatexRange(
    /** Start index (inclusive) of the $$...$$ in the source. */
    val start: Int,
    /** End index (exclusive) of the $$...$$ in the source. */
    val end: Int,
    /** The LaTeX body between the $$ delimiters. */
    val latex: String,
    /** True when the formula is on its own line (block), false when inline within text. */
    val isBlock: Boolean,
)
