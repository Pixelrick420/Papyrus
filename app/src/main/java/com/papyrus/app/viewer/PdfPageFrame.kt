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
 * [PdfTextExtractor] divides by the crop box's size *without* subtracting the origin. On a page
 * whose crop box does not start at (0, 0) its boxes therefore sit offset by that origin; that is a
 * pre-existing flaw in the highlight geometry, left alone here. This class follows the render, which
 * is where the words — and so a visible link rectangle — actually are.
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
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        // Every corner of the rectangle, folded in: rotation can swap width and height.
        for (corner in CORNERS) {
            val x = if (corner and 1 == 0) x0 else x1
            val y = if (corner and 2 == 0) y0 else y1
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
            left = minOf(left, dx)
            top = minOf(top, dy)
            right = maxOf(right, dx)
            bottom = maxOf(bottom, dy)
        }
        if (right <= left || bottom <= top) return null
        return NormRect(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
    }

    companion object {
        private val CORNERS = intArrayOf(0, 1, 2, 3)

        /** The frame of [page]: its crop box (clamped to at least a point) and `/Rotate`. */
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
    }
}
