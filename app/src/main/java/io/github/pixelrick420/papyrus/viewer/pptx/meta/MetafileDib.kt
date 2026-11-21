package io.github.pixelrick420.papyrus.viewer.pptx.meta

import kotlin.math.abs
import kotlin.math.max

internal object MetafileDib {

    fun decode(
        bytes: ByteArray,
        infoOffset: Int,
        bitsOffset: Int,
        maxEdgePx: Int,
        maxPixels: Long,
    ): DibPixels? {
        if (infoOffset < 0 || bitsOffset < 0 || infoOffset + 12 > bytes.size) return null
        val info = GdiReader(bytes, infoOffset)
        val headerSize = info.u32().toInt()
        val header = when (headerSize) {
            CORE_HEADER_BYTES -> {
                val width = info.u16()
                val height = info.u16()
                info.u16()
                val depth = info.u16()
                DibHeader(width, height, depth, 0, 0, true)
            }
            in INFO_HEADER_BYTES..bytes.size -> {
                val width = info.i32()
                val height = info.i32()
                info.u16()
                val depth = info.u16()
                val compression = info.u32().toInt()
                repeat(3) { info.u32() }
                val used = info.u32().toInt()
                DibHeader(width, height, depth, compression, used, false)
            }
            else -> return null
        }
        val width = header.width
        val height = abs(header.height)
        if (width < 1 || height < 1 || header.depth !in SUPPORTED_DEPTHS) return null
        if (header.compression != BI_RGB && header.compression != BI_BITFIELDS) return null
        if (header.compression == BI_BITFIELDS && header.depth != 16 && header.depth != 32) return null

        val stride = ((width.toLong() * header.depth + 31L) / 32L) * 4L
        if (stride * height + bitsOffset > bytes.size.toLong()) return null

        val palette = paletteOf(bytes, infoOffset, header, headerSize)
        val masks = masksOf(bytes, infoOffset, headerSize, header)
        if (header.depth in HIGH_COLOUR_DEPTHS && masks == null) return null
        val budget = minOf(maxPixels, MAX_DIB_PIXELS.toLong()).coerceAtLeast(1L)
        var outWidth = minOf(width, maxOf(1, maxEdgePx))
        var outHeight = minOf(height, maxOf(1, maxEdgePx))
        val scale = minOf(1.0, kotlin.math.sqrt(budget.toDouble() / (outWidth.toLong() * outHeight)))
        outWidth = max(1, minOf(outWidth, (outWidth * scale).toInt()))
        outHeight = max(1, minOf(outHeight, (outHeight * scale).toInt()))
        while (outWidth.toLong() * outHeight > budget && outHeight > 1) outHeight--
        val argb = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val sourceY = ((y.toLong() * height + outHeight / 2) / outHeight).toInt().coerceIn(0, height - 1)
            val row = bitsOffset + (if (header.height > 0) height - 1 - sourceY else sourceY) * stride
            for (x in 0 until outWidth) {
                val sourceX = ((x.toLong() * width + outWidth / 2) / outWidth).toInt().coerceIn(0, width - 1)
                argb[y * outWidth + x] = pixelOf(bytes, row, sourceX, header.depth, palette, masks)
            }
        }
        return DibPixels(outWidth, outHeight, argb)
    }

    private fun paletteOf(
        bytes: ByteArray,
        infoOffset: Int,
        header: DibHeader,
        headerSize: Int,
    ): IntArray {
        if (header.depth > 8) return IntArray(0)
        val maximum = 1 shl header.depth
        val count = if (header.used > 0) minOf(header.used, maximum) else maximum
        val entryBytes = if (header.core) 3 else 4
        val start = infoOffset + headerSize
        if (start + count * entryBytes > bytes.size) return IntArray(maximum)
        return IntArray(maximum) { index ->
            if (index >= count) {
                -1
            } else {
                val at = start + index * entryBytes
                val blue = bytes[at].toInt() and 0xFF
                val green = bytes[at + 1].toInt() and 0xFF
                val red = bytes[at + 2].toInt() and 0xFF
                (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
    }

    private fun masksOf(
        bytes: ByteArray,
        infoOffset: Int,
        headerSize: Int,
        header: DibHeader,
    ): Masks? {
        if (header.depth !in HIGH_COLOUR_DEPTHS) return null
        val fallback = when (header.depth) {
            16 -> Masks(0x7C00, 0x03E0, 0x001F, 0)
            32 -> Masks(0x00FF0000, 0x0000FF00, 0x000000FF, 0)
            else -> return null
        }
        if (header.compression == BI_BITFIELDS && headerSize < V4_HEADER_BYTES) {
            if (infoOffset + headerSize + 12 > bytes.size) return null
            val reader = GdiReader(bytes, infoOffset + headerSize)
            return Masks(reader.u32().toInt(), reader.u32().toInt(), reader.u32().toInt(), 0)
        }
        if (headerSize >= V4_HEADER_BYTES && infoOffset + V4_HEADER_BYTES <= bytes.size) {
            val reader = GdiReader(bytes, infoOffset + 40)
            val red = reader.u32().toInt()
            val green = reader.u32().toInt()
            val blue = reader.u32().toInt()
            val alpha = reader.u32().toInt()
            if (red != 0 || green != 0 || blue != 0) return Masks(red, green, blue, alpha)
        }
        return fallback
    }

    private fun pixelOf(
        bytes: ByteArray,
        row: Long,
        x: Int,
        depth: Int,
        palette: IntArray,
        masks: Masks?,
    ): Int {
        return when (depth) {
            1 -> {
                val value = bytes[row.toInt() + x / 8].toInt() and 0xFF
                palette[(value shr (7 - x % 8)) and 1]
            }
            4 -> {
                val value = bytes[row.toInt() + x / 2].toInt() and 0xFF
                palette[if (x % 2 == 0) value shr 4 else value and 0x0F]
            }
            8 -> palette[bytes[row.toInt() + x].toInt() and 0xFF]
            16 -> {
                val at = row + x * 2L
                val value = (bytes[at.toInt()].toInt() and 0xFF) or ((bytes[at.toInt() + 1].toInt() and 0xFF) shl 8)
                packed(value, masks ?: return -1, 0xFF)
            }
            24 -> {
                val at = row + x * 3L
                val blue = bytes[at.toInt()].toInt() and 0xFF
                val green = bytes[at.toInt() + 1].toInt() and 0xFF
                val red = bytes[at.toInt() + 2].toInt() and 0xFF
                (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
            32 -> {
                val at = row + x * 4L
                val value = (bytes[at.toInt()].toInt() and 0xFF) or
                    ((bytes[at.toInt() + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at.toInt() + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at.toInt() + 3].toInt() and 0xFF) shl 24)
                val colour = masks ?: return -1
                packed(value, colour, colour.alpha)
            }
            else -> -1
        }
    }

    private fun packed(value: Int, masks: Masks, alphaMask: Int): Int {
        val alpha = if (alphaMask == 0) 0xFF else channel(value, alphaMask)
        return (alpha shl 24) or (channel(value, masks.red) shl 16) or
            (channel(value, masks.green) shl 8) or channel(value, masks.blue)
    }

    private fun channel(value: Int, mask: Int): Int {
        if (mask == 0) return 0
        val shift = Integer.numberOfTrailingZeros(mask)
        val maximum = mask ushr shift
        if (maximum == 0) return 0
        return ((value and mask) ushr shift) * 255 / maximum
    }

    private data class DibHeader(
        val width: Int,
        val height: Int,
        val depth: Int,
        val compression: Int,
        val used: Int,
        val core: Boolean,
    )

    private data class Masks(val red: Int, val green: Int, val blue: Int, val alpha: Int)

    private const val CORE_HEADER_BYTES = 12
    private const val INFO_HEADER_BYTES = 40
    private const val V4_HEADER_BYTES = 52
    private const val BI_RGB = 0
    private const val BI_BITFIELDS = 3
    private const val MAX_DIB_PIXELS = 4_000_000
    private val SUPPORTED_DEPTHS = setOf(1, 4, 8, 16, 24, 32)
    private val HIGH_COLOUR_DEPTHS = setOf(16, 32)
}
