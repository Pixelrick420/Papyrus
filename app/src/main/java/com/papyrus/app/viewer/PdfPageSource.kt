package com.papyrus.app.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.roundToInt

class PdfPasswordException : IOException("Password-protected PDF")

/**
 * Thread-safe wrapper over [PdfRenderer], which allows one open page at a time, so access is serialised
 * by a mutex on a single dispatcher and bitmaps live in a heap-sized [LruCache].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfPageSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val tempFile: File?,
) : Closeable {

    val pageCount: Int = renderer.pageCount

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val lock = Mutex()
    private var closed = false

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        // coerceAtLeast(1) is an invariant, not noise: a recycled bitmap reports byteCount == 0, and
        // LruCache requires a stable sizeOf, so trimToSize would throw "sizeOf() is reporting inconsistent results".
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    /** height / width for every page, so list items can reserve space before rendering. */
    suspend fun loadAspectRatios(): List<Float> = withContext(dispatcher) {
        lock.withLock {
            List(pageCount) { i -> renderer.openPage(i).use { it.height.toFloat() / it.width } }
        }
    }

    fun cached(index: Int, widthPx: Int): Bitmap? = cache.get(key(index, widthPx))

    /** Returns null if the source was closed meanwhile. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        cache.get(key(index, widthPx))?.let { return it }
        return withContext(dispatcher) {
            lock.withLock {
                if (closed) return@withLock null
                cache.get(key(index, widthPx))?.let { return@withLock it }
                renderer.openPage(index).use { page ->
                    val height = (widthPx.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                    val bitmap = createBitmap(widthPx, height)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key(index, widthPx), bitmap)
                    bitmap
                }
            }
        }
    }

    /** Closes after any in-flight render finishes. The body is guarded because it runs detached, where an escaping exception reaches the uncaught handler and ends the process. */
    override fun close() {
        CoroutineScope(dispatcher).launch {
            runCatching {
                lock.withLock {
                    if (closed) return@withLock
                    closed = true
                    runCatching { cache.evictAll() }
                    runCatching { renderer.close() }
                    runCatching { descriptor.close() }
                    tempFile?.delete()
                }
            }
        }
    }

    private fun key(index: Int, widthPx: Int) = "$index@$widthPx"

    companion object {
        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(8 shl 20, 128 shl 20)

        suspend fun open(context: Context, uri: Uri): PdfPageSource = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
            try {
                return@withContext PdfPageSource(pfd, create(pfd), null)
            } catch (e: PdfPasswordException) {
                pfd.close()
                throw e
            } catch (e: IOException) {
                pfd.close() // not seekable (some providers) or corrupt: retry from a cached copy
            }
            val copy = File.createTempFile("view-", ".pdf", context.cacheDir)
            try {
                (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()))
                    .use { input -> copy.outputStream().use { input.copyTo(it) } }
                val copyPfd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    PdfPageSource(copyPfd, create(copyPfd), copy)
                } catch (e: Exception) {
                    copyPfd.close()
                    throw e
                }
            } catch (e: Exception) {
                copy.delete()
                throw e
            }
        }

        private fun create(pfd: ParcelFileDescriptor): PdfRenderer =
            try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                throw PdfPasswordException() // PdfRenderer signals password-protected files this way
            }
    }
}
