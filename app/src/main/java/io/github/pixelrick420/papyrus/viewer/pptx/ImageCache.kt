package io.github.pixelrick420.papyrus.viewer.pptx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.pixelrick420.papyrus.viewer.bitmapCacheBudgetBytes
import java.io.File

internal data class ImageBounds(val width: Int, val height: Int) {

    val pixels: Long get() = width.toLong() * height
}

internal object ImageSizing {

    /** The largest power-of-two sample that still leaves at least [targetWidth] pixels of width. */
    fun sampleSize(width: Int, targetWidth: Int): Int {
        if (width <= 0 || targetWidth <= 0) return 1
        var sample = 1
        while (sample <= width / 2 && width / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    fun withinCap(bounds: ImageBounds, maxPixels: Long): Boolean = bounds.pixels in 1..maxPixels
}

internal class PictureImage(val bitmap: Bitmap, val bounds: ImageBounds)

internal class ImageCache(
    private val budgetBytes: Int = bitmapCacheBudgetBytes(),
    private val maxPixels: Long = PptxLimits().maxImagePixels,
    private val maxEdgePx: Int = PptxLimits().maxRenderEdgePx,
    private val boundsOf: (File) -> ImageBounds? = ::platformBounds,
    private val decode: (File, Int) -> Bitmap? = ::platformDecode,
) {

    private val cache = object : LruCache<ImageKey, PictureImage>(budgetBytes) {
        override fun sizeOf(key: ImageKey, value: PictureImage): Int =
            value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    // `inJustDecodeBounds` is file I/O; a tiled picture asks for the same bounds twice per paint.
    private val boundsCache = LruCache<String, ImageBounds>(MAX_BOUNDS_ENTRIES)

    /** The source size, or null for an unreadable or over-cap image, without allocating a bitmap. */
    fun bounds(file: File): ImageBounds? {
        boundsCache.get(file.path)?.let { return it }
        val bounds = boundsOf(file)?.takeIf { ImageSizing.withinCap(it, maxPixels) } ?: return null
        boundsCache.put(file.path, bounds)
        return bounds
    }

    /**
     * Decodes [file] at no more than [targetWidthPx], or returns null. The sample is kept in the key so a wider
     * request does not reuse a coarser decode.
     */
    fun load(file: File, targetWidthPx: Int): PictureImage? {
        val bounds = bounds(file) ?: return null
        val target = targetWidthPx.coerceIn(1, maxEdgePx)
        val sample = ImageSizing.sampleSize(bounds.width, target)
        val key = ImageKey(file.path, sample)
        cache.get(key)?.let { return it }
        val bitmap = try {
            decode(file, sample)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val image = PictureImage(bitmap, bounds)
        cache.put(key, image)
        return image
    }

    fun trimMemory(evictAll: Boolean) {
        if (evictAll) cache.evictAll() else cache.trimToSize(cache.maxSize() / 2)
    }

    fun clear() {
        cache.evictAll()
    }

    private data class ImageKey(val path: String, val sample: Int)

    private companion object {
        const val MAX_BOUNDS_ENTRIES = 128
    }
}

private fun platformBounds(file: File): ImageBounds? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return ImageBounds(options.outWidth, options.outHeight)
}

private fun platformDecode(file: File, sampleSize: Int): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(file.path, options)
}
