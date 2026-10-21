package com.papyrus.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.papyrus.app.viewer.DocTextExtractor
import com.papyrus.app.viewer.EpubExtractor
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

    /** A lost SAF grant needs a re-pick, never an empty tile. */
    data object NoAccess : ThumbnailResult

    data class Unavailable(val reason: String) : ThumbnailResult
}

/** One bitmap per document, not per page, so a long scan holds a single renderer bitmap. */
class ThumbnailLoader(private val context: Context) {

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Keyed by id@sizeBytes: an external size change re-thumbnails, a rename does not. */
    fun cached(document: DocumentEntity): Bitmap? = cache.get(key(document))

    /** The per-key mutex collapses overlapping loads from a row re-scrolled into view. */
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
        DocumentFormat.MARKDOWN, DocumentFormat.TEXT, DocumentFormat.CODE, DocumentFormat.CSV -> renderText(document.uri.toUri())
        DocumentFormat.DOCX, DocumentFormat.ODT, DocumentFormat.DOC -> renderOffice(document)
        DocumentFormat.EPUB -> renderEpub(document.uri.toUri())
        DocumentFormat.UNKNOWN -> null
    }

    /** Closes PdfPageSource right after page 1: holding one per row exhausts the open-PD budget. */
    private suspend fun renderPdf(uri: Uri): Bitmap? =
        PdfPageSource.open(context, uri).use { source ->
            val page = source.render(0, THUMBNAIL_PDF_WIDTH_PX) ?: return@use null
            // scale, not Bitmap.copy: copy never changes size. Never recycle `page` -- its LruCache
            // still owns it, and a recycled bitmap reports byteCount == 0, so close() throws.
            page.scale(THUMBNAIL_WIDTH_PX, THUMBNAIL_HEIGHT_PX, true)
        }

    private fun renderText(uri: Uri): Bitmap? = openStream(uri)?.use { input ->
        val head = readHead(input, MAX_THUMBNAIL_SOURCE_BYTES) ?: return@use null
        if (!com.papyrus.app.viewer.TextSniffer.looksLikeText(head)) return@use null
        val lines = String(head, Charsets.UTF_8).lineSequence().take(MAX_THUMBNAIL_LINES).toList()
        drawTextTile(lines)
    }

    private fun renderOffice(document: DocumentEntity): Bitmap? {
        // mediaDir = null: thumbnails are text only, and writing media would double the cacheDir cost.
        val uri = document.uri.toUri()
        val blocks = if (document.format == DocumentFormat.DOC) {
            val source = { openStream(uri) ?: throw FileNotFoundException(uri.toString()) }
            DocTextExtractor.extract(source, THUMBNAIL_BLOCK_BUDGET)
        } else {
            OfficeTextExtractor(mediaDir = null)
                .extract({ openStream(uri)!! }, document.format, THUMBNAIL_BLOCK_BUDGET)
        }
        return drawTextTile(previewLines(blocks))
    }

    private fun previewLines(blocks: List<OfficeBlock>): List<String> = blocks.asSequence()
        .mapNotNull { block ->
            when (block) {
                is OfficeBlock.Heading -> block.text
                is OfficeBlock.Paragraph -> block.text
                is OfficeBlock.ListItem -> block.text
                is OfficeBlock.Table, is OfficeBlock.Image, is OfficeBlock.Note, is OfficeBlock.Divider -> null
            }
        }
        .take(4)
        .toList()

    /**
     * The cover when the package names one, read as that one entry and nothing else; otherwise the same text
     * tile as any other document, from the first chapters only. Nothing is written to disk.
     */
    private fun renderEpub(uri: Uri): Bitmap? {
        val open = { openStream(uri) ?: throw FileNotFoundException(uri.toString()) }
        val extractor = EpubExtractor(mediaDir = null, blockBudget = THUMBNAIL_BLOCK_BUDGET)
        extractor.readCover(open)?.let { bytes -> decodeCover(bytes)?.let { return it } }
        return drawTextTile(previewLines(extractor.extract(open, previewChapters = EPUB_PREVIEW_CHAPTERS).blocks))
    }

    /**
     * Decodes a cover without ever holding it at full size: the dimensions first (a picture that claims more
     * than [MAX_COVER_PIXELS] is refused), then a power-of-two subsample down to about twice the tile, then a
     * fill of the tile, anchored at the top so a tall cover keeps its title.
     */
    private fun decodeCover(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_COVER_PIXELS) return null
        var sample = 1
        while (width / (sample * 2) >= THUMBNAIL_WIDTH_PX * 2 && height / (sample * 2) >= THUMBNAIL_HEIGHT_PX * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val tile = createBitmap(THUMBNAIL_WIDTH_PX, THUMBNAIL_HEIGHT_PX)
        val canvas = Canvas(tile)
        canvas.drawColor(Color.WHITE)
        val fill = maxOf(THUMBNAIL_WIDTH_PX.toFloat() / decoded.width, THUMBNAIL_HEIGHT_PX.toFloat() / decoded.height)
        val matrix = Matrix().apply {
            postScale(fill, fill)
            postTranslate((THUMBNAIL_WIDTH_PX - decoded.width * fill) / 2f, 0f)
        }
        canvas.drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        decoded.recycle()
        return tile
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

        /** A tile draws 4 lines, so 32 blocks covers documents that open with tables or images. */
        const val THUMBNAIL_BLOCK_BUDGET = 32

        private const val THUMBNAIL_PDF_WIDTH_PX = 256
        private const val THUMBNAIL_TEXT_PX = 18f
        private const val PADDING_PX = 8f
        private const val MAX_THUMBNAIL_LINES = 8
        private const val MAX_THUMBNAIL_CHARS = 20
        private const val MAX_THUMBNAIL_SOURCE_BYTES = 16 * 1024
        private const val EPUB_PREVIEW_CHAPTERS = 3

        /** Pixels a cover may claim before it is not worth decoding for a 132 by 176 tile. */
        private const val MAX_COVER_PIXELS = 40L * 1000 * 1000

        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 16).toInt().coerceIn(4 shl 20, 32 shl 20)
    }
}
