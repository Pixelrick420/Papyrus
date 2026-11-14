package io.github.pixelrick420.papyrus.viewer

import android.graphics.Bitmap
import java.io.Closeable

// What the page viewer needs from a document: sized pages drawn into bitmaps, behind a memory-bounded cache.
interface PageBitmapSource : Closeable {
    val pageCount: Int
    val maxRenderPixels: Int
    suspend fun loadAspectRatios(): List<Float>
    fun cached(index: Int, widthPx: Int): Bitmap?
    suspend fun render(index: Int, widthPx: Int): Bitmap?
    fun evictOutside(keep: IntRange)
    fun trimMemory(evictAll: Boolean)
}

/** The bitmap cache budget shared by every page and picture source. */
internal fun bitmapCacheBudgetBytes(): Int =
    (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(8 shl 20, 128 shl 20)

