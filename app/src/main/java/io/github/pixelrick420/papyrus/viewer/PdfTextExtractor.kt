package io.github.pixelrick420.papyrus.viewer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.text.Normalizer
import java.util.BitSet
import kotlin.math.abs
import kotlin.coroutines.cancellation.CancellationException

// Rebuilt from glyphs because [PDFTextStripper.getText] normalises and positions stop lining up.
object PdfTextExtractor {

    // Characters document-wide whose boxes are kept (16 bytes each, about 32 MB); past it pages stay
    // searchable but not highlightable.
    private const val MAX_BOXED_CHARS = 2_000_000

    // Box height as a fraction of font size; PdfBox's `heightDir` is ~20% short of the real ascender.
    private const val ASCENT_FRACTION = 0.8f
    private const val DESCENT_FRACTION = 0.22f

    /** A gap past this fraction of font size is a word space (a space is 0.25 em). */
    private const val WORD_GAP_FRACTION = 0.2f

    /** A shift off the baseline past this fraction of the font size starts a new line. */
    private const val LINE_SHIFT_FRACTION = 0.5f

    // A page [known] already has is taken as it is and does not count against the box budget, which is for what this call itself reads.
    fun extract(document: PDDocument, known: (Int) -> PdfPageText? = { null }): List<PdfPageText> {
        val collector = PageCollector()
        var boxedChars = 0
        return List(document.numberOfPages) { page ->
            known(page) ?: collect(collector, document, page, trackBoxes = boxedChars < MAX_BOXED_CHARS).also {
                boxedChars += if (collector.trackingBoxes) it.text.length else 0
            }
        }
    }

    fun extractPage(document: PDDocument, pageIndex: Int): PdfPageText =
        collect(PageCollector(), document, pageIndex, trackBoxes = true)

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

        private val box = FloatArray(4)
        private val current = Placement()
        private val previous = Placement()
        private var hasPrevious = false

        private var frame = PdfPageFrame(1f, 1f, 0f, 0f, 0)

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
            frame = PdfPageFrame.forGlyphs(page)
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

        private fun place(position: TextPosition) {
            val matrix = position.textMatrix
            current.originX = matrix.translateX
            current.originY = matrix.translateY
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
            val across = -dx * current.dirY + dy * current.dirX
            val size = maxOf(previous.size, current.size)
            if (abs(across) > size * LINE_SHIFT_FRACTION) return LINE_BREAK
            val gap = along - previous.advance
            // A large jump backwards is a new run of text too, not a kerning pair.
            return if (gap < -size) LINE_BREAK else if (gap > size * WORD_GAP_FRACTION) WORD_BREAK else NO_BREAK
        }

        private fun markLineBreak() {
            if (chars.isEmpty()) return
            appendSeparator()
            lineBreaks.set(chars.length - 1)
        }

        // Built from the text matrix, not `xDirAdj`/`yDirAdj`, which follow the text's direction and misplace boxes on a rotated page.
        private fun boxOfCurrent(): FloatArray {
            val size = current.size
            val upX = -current.dirY
            val upY = current.dirX
            val ascent = maxOf(current.height, size * ASCENT_FRACTION)
            val descent = size * DESCENT_FRACTION
            box[0] = Float.MAX_VALUE
            box[1] = Float.MAX_VALUE
            box[2] = -Float.MAX_VALUE
            box[3] = -Float.MAX_VALUE
            corner(upX, upY, 0f, -descent)
            corner(upX, upY, 0f, ascent)
            corner(upX, upY, current.advance, -descent)
            corner(upX, upY, current.advance, ascent)
            return box
        }

        private fun corner(upX: Float, upY: Float, along: Float, up: Float) {
            val x = current.originX + current.dirX * along + upX * up
            val y = current.originY + current.dirY * along + upY * up
            // The text matrix is already crop-relative (PdfBox shifts it by the crop box's origin), so the
            // glyph frame has no origin to fold out; the same arithmetic places a link's raw `/Rect`.
            frame.include(x, y, box)
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
