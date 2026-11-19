package io.github.pixelrick420.papyrus.viewer

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

// What the page viewer needs from a document: sized pages drawn into bitmaps, behind a memory-bounded cache.
interface PageBitmapSource : Closeable {
    val pageCount: Int
    val maxRenderPixels: Int
    /** Turns true if a page render is truncated after load, so the reader can be told. */
    val incomplete: StateFlow<Boolean> get() = NEVER_INCOMPLETE
    suspend fun loadAspectRatios(): List<Float>
    fun cached(index: Int, widthPx: Int): Bitmap?
    suspend fun render(index: Int, widthPx: Int): Bitmap?
    fun tileGrid(index: Int, widthPx: Int): TileGrid? = null
    fun cachedTile(index: Int, widthPx: Int, rect: NormRect): Bitmap? = null
    suspend fun renderTile(index: Int, widthPx: Int, rect: NormRect): Bitmap? = null
    fun evictOutside(keep: IntRange)
    fun trimMemory(evictAll: Boolean)
}

/** The bitmap cache budget shared by every page and picture source. */
internal fun bitmapCacheBudgetBytes(): Int =
    (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(8 shl 20, 128 shl 20)

private val NEVER_INCOMPLETE = MutableStateFlow(false)

