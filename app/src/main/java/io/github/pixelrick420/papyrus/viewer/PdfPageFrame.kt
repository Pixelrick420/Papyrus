package io.github.pixelrick420.papyrus.viewer

import com.tom_roush.pdfbox.pdmodel.PDPage

// [of] folds the crop origin out; [forGlyphs] must not, since PdfBox reports glyphs crop-relative.
internal class PdfPageFrame(
    private val cropWidth: Float,
    private val cropHeight: Float,
    private val cropX: Float,
    private val cropY: Float,
    private val rotation: Int,
) {

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

    // Runs per glyph corner, so it must stay allocation-free; seed [box] with MAX, MAX, -MAX, -MAX.
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

        // Passing raw page-space coordinates here is wrong on a page whose crop box is not at (0, 0).
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
