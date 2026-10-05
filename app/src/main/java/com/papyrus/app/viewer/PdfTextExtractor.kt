package com.papyrus.app.viewer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.text.Normalizer
import java.util.BitSet
import kotlin.math.abs
import kotlin.coroutines.cancellation.CancellationException

/**
 * Extracts each page's text and the box of every character, so find-in-file can highlight matches on
 * the rasterised page. Text is rebuilt from glyphs rather than taken from [PDFTextStripper.getText],
 * because the stripper normalises (a ligature becomes two letters) and positions stop lining up.
 */
object PdfTextExtractor {

    /** Characters document-wide whose boxes are kept; 16 bytes each, about 32 MB. Past it pages are searchable but not highlightable. */
    private const val MAX_BOXED_CHARS = 2_000_000

    /** Box height as fractions of the font size, sized from the font because PdfBox's `heightDir` is its nominal glyph height, about 20% short of the real ascender (6.9pt for 12pt Helvetica, where 'd' rises to 8.6pt). */
    private const val ASCENT_FRACTION = 0.8f
    private const val DESCENT_FRACTION = 0.22f

    /** A gap wider than this fraction of the font size is a word space; a space is 0.25 em and kerning under 0.1 em. */
    private const val WORD_GAP_FRACTION = 0.2f

    /** A shift off the baseline past this fraction of the font size starts a new line. */
    private const val LINE_SHIFT_FRACTION = 0.5f

    /** One [PdfPageText] per page; a page that fails to parse yields an empty page rather than aborting the document. */
    fun extract(document: PDDocument): List<PdfPageText> {
        val collector = PageCollector()
        var boxedChars = 0
        return List(document.numberOfPages) { page ->
            collect(collector, document, page, trackBoxes = boxedChars < MAX_BOXED_CHARS).also {
                boxedChars += if (collector.trackingBoxes) it.text.length else 0
            }
        }
    }

    /**
     * Just page [pageIndex] (from 0), for a reader pointing at one page of a document nobody has
     * searched. Boxes are always kept: the budget in [extract] is for a whole document read at once,
     * and this is called once per page the reader touches.
     */
    fun extractPage(document: PDDocument, pageIndex: Int): PdfPageText =
        collect(PageCollector(), document, pageIndex, trackBoxes = true)

    /** Reads one page into [collector]; a page that fails to parse is empty, not an error. */
    private fun collect(collector: PageCollector, document: PDDocument, page: Int, trackBoxes: Boolean): PdfPageText {
        collector.reset(trackBoxes)
        collector.startPage = page + 1
        collector.endPage = page + 1
        return try {
            collector.getText(document)
            collector.toPage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PdfPageText("", FloatArray(0))
        }
    }

    /** One glyph's origin, writing direction and advance, in the unrotated page frame (y up). */
    private class Placement {
        var originX = 0f
        var originY = 0f
        var dirX = 1f
        var dirY = 0f
        var advance = 0f
        var size = 0f
        var height = 0f

        fun copyFrom(other: Placement) {
            originX = other.originX
            originY = other.originY
            dirX = other.dirX
            dirY = other.dirY
            advance = other.advance
            size = other.size
            height = other.height
        }
    }

    private class PageCollector : PDFTextStripper() {
        private val chars = StringBuilder()
        private val lineBreaks = BitSet()
        private var boxes = FloatArray(INITIAL_BOXES)

        // Scratch for the glyph being measured, reused so extraction does not allocate per glyph.
        private val box = FloatArray(4)
        private val current = Placement()
        private val previous = Placement()
        private var hasPrevious = false

        // The page as stored (crop box, before rotation), and how it is turned for display.
        private var cropWidth = 1f
        private var cropHeight = 1f
        private var rotation = 0

        var trackingBoxes = true
            private set

        fun reset(trackBoxes: Boolean) {
            chars.setLength(0)
            lineBreaks.clear()
            trackingBoxes = trackBoxes
            hasPrevious = false
        }

        fun toPage(): PdfPageText = PdfPageText(
            text = chars.toString(),
            boxes = if (trackingBoxes) boxes.copyOf(chars.length * 4) else FloatArray(0),
            lineBreaks = BitSet.valueOf(lineBreaks.toLongArray()),
        )

        override fun startPage(page: PDPage) {
            val crop = page.cropBox
            cropWidth = crop.width.coerceAtLeast(1f)
            cropHeight = crop.height.coerceAtLeast(1f)
            rotation = ((page.rotation % 360) + 360) % 360
            super.startPage(page)
        }

        // Only the glyphs are used: the stripper's word boundaries are untrusted, so neither
        // [writeWordSeparator] nor [writeLineSeparator] is overridden.
        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            for (position in textPositions) {
                val unicode = position.unicode
                if (unicode.isNullOrEmpty()) continue
                if (unicode.isBlank()) {
                    appendSeparator()
                    continue
                }
                place(position)
                if (hasPrevious) {
                    when (breakBefore()) {
                        LINE_BREAK -> markLineBreak()
                        WORD_BREAK -> appendSeparator()
                    }
                }
                val box = if (trackingBoxes) boxOfCurrent() else null
                for (ch in Normalizer.normalize(unicode, Normalizer.Form.NFKC)) {
                    if (box == null) append(ch, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
                    else append(ch, box[0], box[1], box[2], box[3])
                }
                previous.copyFrom(current)
                hasPrevious = true
            }
        }

        /** Reads [position]'s origin and writing direction from its text matrix into [current]. */
        private fun place(position: TextPosition) {
            val matrix = position.textMatrix
            current.originX = matrix.translateX
            current.originY = matrix.translateY
            // Writing direction, counter-clockwise from +x with y up: 0 left-to-right, 90 upward, ...
            when (position.dir.toInt()) {
                90 -> { current.dirX = 0f; current.dirY = 1f }
                180 -> { current.dirX = -1f; current.dirY = 0f }
                270 -> { current.dirX = 0f; current.dirY = -1f }
                else -> { current.dirX = 1f; current.dirY = 0f }
            }
            current.advance = position.widthDirAdj
            current.size = position.fontSizeInPt
            current.height = position.heightDir
        }

        private fun breakBefore(): Int {
            if (previous.dirX != current.dirX || previous.dirY != current.dirY) return LINE_BREAK
            val dx = current.originX - previous.originX
            val dy = current.originY - previous.originY
            val along = dx * current.dirX + dy * current.dirY
            // Perpendicular to the writing direction: how far off the previous glyph's baseline.
            val across = -dx * current.dirY + dy * current.dirX
            val size = maxOf(previous.size, current.size)
            if (abs(across) > size * LINE_SHIFT_FRACTION) return LINE_BREAK
            val gap = along - previous.advance
            // A large jump backwards is a new run of text too, not a kerning pair.
            return if (gap < -size) LINE_BREAK else if (gap > size * WORD_GAP_FRACTION) WORD_BREAK else NO_BREAK
        }

        /** Ends the line: one separator (reusing a space already there) flagged as a wrap point. */
        private fun markLineBreak() {
            if (chars.isEmpty()) return
            appendSeparator()
            lineBreaks.set(chars.length - 1)
        }

        /**
         * [current]'s bounding box as page-relative (left, top, right, bottom) in the displayed page.
         * Built from the text matrix, not `xDirAdj`/`yDirAdj`: those follow the text's own direction
         * rather than the page's `/Rotate`, so on a rotated page they place every box wrongly.
         */
        private fun boxOfCurrent(): FloatArray {
            val size = current.size
            // The glyph's "up" is the writing direction turned a quarter turn counter-clockwise.
            val upX = -current.dirY
            val upY = current.dirX
            val ascent = maxOf(current.height, size * ASCENT_FRACTION)
            val descent = size * DESCENT_FRACTION
            box[0] = Float.MAX_VALUE
            box[1] = Float.MAX_VALUE
            box[2] = -Float.MAX_VALUE
            box[3] = -Float.MAX_VALUE
            // Corners: [0, advance] along the writing direction by [-descent, ascent] up.
            corner(upX, upY, 0f, -descent)
            corner(upX, upY, 0f, ascent)
            corner(upX, upY, current.advance, -descent)
            corner(upX, upY, current.advance, ascent)
            return box
        }

        /** Folds one corner into [box], going from the unrotated page frame to the displayed one. */
        private fun corner(upX: Float, upY: Float, along: Float, up: Float) {
            val x = current.originX + current.dirX * along + upX * up
            val y = current.originY + current.dirY * along + upY * up
            // Normalise into the unrotated page's top-left frame first, then turn clockwise a quarter at a time.
            val nx = x / cropWidth
            val ny = (cropHeight - y) / cropHeight
            val dx: Float
            val dy: Float
            when (rotation) {
                90 -> { dx = 1f - ny; dy = nx }
                180 -> { dx = 1f - nx; dy = 1f - ny }
                270 -> { dx = ny; dy = 1f - nx }
                else -> { dx = nx; dy = ny }
            }
            box[0] = minOf(box[0], dx)
            box[1] = minOf(box[1], dy)
            box[2] = maxOf(box[2], dx)
            box[3] = maxOf(box[3], dy)
        }

        private fun appendSeparator() {
            if (chars.isEmpty() || chars.last() == ' ') return
            append(' ', Float.NaN, Float.NaN, Float.NaN, Float.NaN)
        }

        private fun append(ch: Char, left: Float, top: Float, right: Float, bottom: Float) {
            if (trackingBoxes) {
                val o = chars.length * 4
                if (o + 4 > boxes.size) boxes = boxes.copyOf(maxOf(boxes.size * 2, o + 4))
                boxes[o] = left.coerceIn(0f, 1f)
                boxes[o + 1] = top.coerceIn(0f, 1f)
                boxes[o + 2] = right.coerceIn(0f, 1f)
                boxes[o + 3] = bottom.coerceIn(0f, 1f)
                // NaN survives coerceIn as NaN, which is the "no box" marker.
            }
            chars.append(ch)
        }
    }

    private const val INITIAL_BOXES = 4096
    private const val NO_BREAK = 0
    private const val WORD_BREAK = 1
    private const val LINE_BREAK = 2
}
