package com.papyrus.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeTextExtractor
import com.papyrus.app.viewer.PdfPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream

sealed interface ThumbnailResult {
    data class Image(val bitmap: Bitmap) : ThumbnailResult

    /** A lost SAF grant is the user's to fix: the document has to be picked again, never an empty tile. */
    data object NoAccess : ThumbnailResult

    data class Unavailable(val reason: String) : ThumbnailResult
}

/** One bitmap per document, not per page, so a 200-page scan holds a single PdfRenderer bitmap in the LruCache. */
class ThumbnailLoader(private val context: Context) {

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Keyed by id@sizeBytes: an external size change re-thumbnails, a rename does not. */
    fun cached(document: DocumentEntity): Bitmap? = cache.get(key(document))

    /** Scrolling a row out and back re-runs produceState, so the per-key mutex collapses two overlapping loads into one render. */
    private val inFlight = mutableMapOf<String, Mutex>()

    private fun gateFor(key: String): Mutex = synchronized(inFlight) { inFlight.getOrPut(key) { Mutex() } }

    private fun releaseGate(key: String, gate: Mutex) = synchronized(inFlight) {
        if (inFlight[key] === gate) inFlight.remove(key)
    }

    suspend fun load(document: DocumentEntity): ThumbnailResult = withContext(Dispatchers.IO) {
        val cacheKey = key(document)
        val gate = gateFor(cacheKey)
        try {
            gate.withLock {
                cached(document)?.let { return@withLock ThumbnailResult.Image(it) }
                val bitmap = try {
                    render(document)
                } catch (e: SecurityException) {
                    return@withLock ThumbnailResult.NoAccess
                } catch (e: FileNotFoundException) {
                    return@withLock ThumbnailResult.Unavailable(e.message ?: "missing")
                } catch (e: Exception) {
                    // Broad on purpose: a thumbnail is decoration, so no parse failure should crash the app.
                    return@withLock ThumbnailResult.Unavailable(e.javaClass.simpleName)
                }
                if (bitmap == null) {
                    ThumbnailResult.Unavailable("no renderer")
                } else {
                    cache.put(cacheKey, bitmap)
                    ThumbnailResult.Image(bitmap)
                }
            }
        } finally {
            releaseGate(cacheKey, gate)
        }
    }

    private suspend fun render(document: DocumentEntity): Bitmap? = when (document.format) {
        DocumentFormat.PDF -> renderPdf(document.uri.toUri())
        DocumentFormat.MARKDOWN, DocumentFormat.TEXT, DocumentFormat.CODE -> renderText(document.uri.toUri())
        DocumentFormat.DOCX, DocumentFormat.ODT -> renderOffice(document)
        DocumentFormat.UNKNOWN -> null
    }

    /** Closes the PdfPageSource right after page 1; holding one per list row exhausts the open-PD budget. */
    private suspend fun renderPdf(uri: Uri): Bitmap? =
        PdfPageSource.open(context, uri).use { source ->
            val page = source.render(0, THUMBNAIL_PDF_WIDTH_PX) ?: return@use null
            // scale, not Bitmap.copy: copy changes only config and mutability, never size.
            // Never recycle `page`: the source's LruCache still owns it, and a recycled bitmap
            // reports byteCount == 0, so close() throws "sizeOf() is reporting inconsistent results".
            page.scale(THUMBNAIL_WIDTH_PX, THUMBNAIL_HEIGHT_PX, true)
        }

    private fun renderText(uri: Uri): Bitmap? = openStream(uri)?.use { input ->
        val head = readHead(input, MAX_THUMBNAIL_SOURCE_BYTES) ?: return@use null
        if (!com.papyrus.app.viewer.TextSniffer.looksLikeText(head)) return@use null
        val lines = String(head, Charsets.UTF_8).lineSequence().take(MAX_THUMBNAIL_LINES).toList()
        drawTextTile(lines)
    }

    private fun renderOffice(document: DocumentEntity): Bitmap? {
        // mediaDir = null: thumbnails are text only, and writing document media would double the cacheDir cost of opening.
        val extractor = OfficeTextExtractor(mediaDir = null)
        val blocks = extractor.extract({ openStream(document.uri.toUri())!! }, document.format)
        val lines = blocks.asSequence()
            .mapNotNull { block ->
                when (block) {
                    is OfficeBlock.Heading -> block.text
                    is OfficeBlock.Paragraph -> block.text
                    is OfficeBlock.Table, is OfficeBlock.Image -> null
                }
            }
            .take(4)
            .toList()
        return drawTextTile(lines)
    }

    private fun openStream(uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (_: SecurityException) {
        null
    }

    private fun readHead(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return if (total == 0) null else out.toByteArray()
    }

    /** No wrapping, since a thumbnail is a glance; lines past the tile height are clipped. */
    private fun drawTextTile(lines: List<String>): Bitmap? {
        if (lines.isEmpty()) return null
        val bitmap = createBitmap(THUMBNAIL_WIDTH_PX, THUMBNAIL_HEIGHT_PX)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 40)
            typeface = Typeface.MONOSPACE
            textSize = THUMBNAIL_TEXT_PX
        }
        val lineHeight = paint.textSize * 1.3f
        lines.take(MAX_THUMBNAIL_LINES).forEachIndexed { index, line ->
            val y = (index + 1) * lineHeight
            if (y > THUMBNAIL_HEIGHT_PX) return@forEachIndexed
            canvas.drawText(line.take(MAX_THUMBNAIL_CHARS), PADDING_PX, y, paint)
        }
        return bitmap
    }

    private fun key(document: DocumentEntity) = "${document.id}@${document.sizeBytes}"

    companion object {
        const val THUMBNAIL_WIDTH_PX = 132
        const val THUMBNAIL_HEIGHT_PX = 176
        private const val THUMBNAIL_PDF_WIDTH_PX = 256
        private const val THUMBNAIL_TEXT_PX = 18f
        private const val PADDING_PX = 8f
        private const val MAX_THUMBNAIL_LINES = 8
        private const val MAX_THUMBNAIL_CHARS = 20
        private const val MAX_THUMBNAIL_SOURCE_BYTES = 16 * 1024

        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 16).toInt().coerceIn(4 shl 20, 32 shl 20)
    }
}
