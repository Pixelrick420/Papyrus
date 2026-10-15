package com.papyrus.app.viewer

import com.tom_roush.pdfbox.pdmodel.PDPage

/**
 * A page's stored rectangle frame: the crop box's origin and size, and the displayed rotation. Turns
 * a PDF rectangle (y up) into the stage the viewer draws in: page-relative fractions ordered
 * left/top/right/bottom, the same unit [NormRect] that find highlights and selection rectangles use.
 *
 * A page is rendered as its crop box, with that box's lower-left at the stage's top-left (see
 * [PdfPageSource], which takes the page's aspect from the renderer). So the crop box's origin is
 * folded out before dividing by its size — a page whose crop box starts away from (0, 0) would
 * otherwise put every link rectangle offset from the words it belongs to.
 *
 * Glyph boxes and link rectangles reach this class in different frames, and both are right. PdfBox
 * hands [PdfTextExtractor] glyph matrices that are already shifted by the crop box's lower-left (its
 * `LegacyPDFStreamEngine` translates by `-cropBox.lowerLeft` before it builds a `TextPosition`), so
 * glyph coordinates are crop-relative and [forGlyphs] folds out nothing. A link annotation's `/Rect`
 * is raw page space, so [of] folds the origin out. Both then share [include], the one copy of the
 * normalise-then-rotate arithmetic, so a glyph and a link rectangle over the same point of the page
 * agree on every rotation and on any crop-box origin.
 */
internal class PdfPageFrame(
    private val cropWidth: Float,
    private val cropHeight: Float,
    private val cropX: Float,
    private val cropY: Float,
    private val rotation: Int,
) {

    /** [x0], [y0], [x1], [y1] as page-relative fractions, or null if the rectangle is empty or not finite. */
    fun toNorm(x0: Float, y0: Float, x1: Float, y1: Float): NormRect? {
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) return null
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        // Every corner of the rectangle, folded in: rotation can swap width and height.
        include(x0, y0, box)
        include(x1, y0, box)
        include(x0, y1, box)
        include(x1, y1, box)
        val (left, top, right, bottom) = box
        if (right <= left || bottom <= top) return null
        return NormRect(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
    }

    /**
     * Folds the point ([x], [y]) (y up, in this frame's input space) into [box] as `left, top, right,
     * bottom` page fractions: [box]'s min and max are widened, nothing is allocated. Callers seed [box]
     * with `MAX, MAX, -MAX, -MAX`. The text extractor calls this once per glyph corner, so it must stay
     * allocation-free.
     */
    fun include(x: Float, y: Float, box: FloatArray) {
        // Into the unrotated page's top-left frame first, then turn clockwise a quarter at a time.
        val nx = (x - cropX) / cropWidth
        val ny = (cropHeight - (y - cropY)) / cropHeight
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

    companion object {
        /** The frame of [page] for raw page-space input such as a link's `/Rect`: its crop box (clamped to at least a point) and `/Rotate`. */
        fun of(page: PDPage): PdfPageFrame {
            val crop = page.cropBox
            return PdfPageFrame(
                cropWidth = crop.width.coerceAtLeast(1f),
                cropHeight = crop.height.coerceAtLeast(1f),
                cropX = crop.lowerLeftX,
                cropY = crop.lowerLeftY,
                rotation = ((page.rotation % 360) + 360) % 360,
            )
        }

        /**
         * The frame of [page] for glyph coordinates as PdfBox's text engine reports them, which are
         * already relative to the crop box's lower-left (see the class comment), so the origin is zero.
         * Passing raw page-space coordinates here would be wrong on a page whose crop box does not
         * start at (0, 0); use [of] for those.
         */
        fun forGlyphs(page: PDPage): PdfPageFrame {
            val crop = page.cropBox
            return PdfPageFrame(
                cropWidth = crop.width.coerceAtLeast(1f),
                cropHeight = crop.height.coerceAtLeast(1f),
                cropX = 0f,
                cropY = 0f,
                rotation = ((page.rotation % 360) + 360) % 360,
            )
        }
    }
}
