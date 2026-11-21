package io.github.pixelrick420.papyrus.data

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
import io.github.pixelrick420.papyrus.viewer.DocTextExtractor
import io.github.pixelrick420.papyrus.viewer.EpubExtractor
import io.github.pixelrick420.papyrus.viewer.OfficeBlock
import io.github.pixelrick420.papyrus.viewer.OfficeTextExtractor
import io.github.pixelrick420.papyrus.viewer.PdfPageSource
import io.github.pixelrick420.papyrus.viewer.pptx.SlidePageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

sealed interface ThumbnailResult {
    data class Image(val bitmap: Bitmap) : ThumbnailResult

    data object NoAccess : ThumbnailResult

    data class Unavailable(val reason: String) : ThumbnailResult
}

/** One bitmap per document, not per page, so a long scan holds a single renderer bitmap. */
class ThumbnailLoader(private val context: Context) {

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    private val failures = ThumbnailFailureCache({ System.currentTimeMillis() })

    /** Renders are blocking and each may open a PDF, so a fast scroll must not start one per row at once. */
    private val renderSlots = Semaphore(MAX_CONCURRENT_RENDERS)

    private val renderAttempts = AtomicInteger()

    /** Instrumentation checks that a failed document is not parsed again on every scroll. */
    internal val renderCount: Int get() = renderAttempts.get()

    fun trimMemory(evictAll: Boolean) {
        if (evictAll) {
            cache.evictAll()
            failures.evictAll()
        } else {
            cache.trimToSize(cache.maxSize() / 2)
        }
    }

    /** Keyed by id@sizeBytes: an external size change re-thumbnails, a rename does not. */
    fun cachedResult(document: DocumentEntity): ThumbnailResult? {
        val key = thumbnailCacheKey(document)
        return cache.get(key)?.let(ThumbnailResult::Image) ?: failures.get(key)
    }

    /** Ref-counted so a waiter still holding the key keeps the gate alive for a later caller. */
    private val inFlight = mutableMapOf<String, Gate>()

    private fun acquireGate(key: String): Gate = synchronized(inFlight) {
        inFlight.getOrPut(key) { Gate(Mutex()) }.also { it.users++ }
    }

    private fun releaseGate(key: String, gate: Gate) = synchronized(inFlight) {
        gate.users--
        if (gate.users == 0 && inFlight[key] === gate) inFlight.remove(key)
    }

    private class Gate(val mutex: Mutex, var users: Int = 0)

    // A thumbnail is decoration: no parse or provider failure should crash the list.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun load(document: DocumentEntity): ThumbnailResult = withContext(Dispatchers.IO) {
        val cacheKey = thumbnailCacheKey(document)
        cachedResult(document)?.let { return@withContext it }
        val gate = acquireGate(cacheKey)
        try {
            gate.mutex.withLock {
                cachedResult(document)?.let { return@withLock it }
                val bitmap = try {
                    renderSlots.withPermit {
                        ensureActive()
                        renderAttempts.incrementAndGet()
                        render(document)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    return@withLock remember(cacheKey, ThumbnailResult.NoAccess, ThumbnailFailureCache.SHORT_TTL)
                } catch (e: FileNotFoundException) {
                    val failure = ThumbnailResult.Unavailable(e.message ?: "missing")
                    return@withLock remember(cacheKey, failure, ThumbnailFailureCache.SHORT_TTL)
                } catch (e: Exception) {
                    // Broad on purpose: a thumbnail is decoration, so no parse failure should crash the app.
                    val failure = ThumbnailResult.Unavailable(e.javaClass.simpleName)
                    return@withLock remember(cacheKey, failure, ThumbnailFailureCache.PERMANENT_TTL)
                }
                if (bitmap == null) {
                    remember(
                        cacheKey,
                        ThumbnailResult.Unavailable("no renderer"),
                        if (document.format == DocumentFormat.UNKNOWN) ThumbnailFailureCache.PERMANENT_TTL
                        else ThumbnailFailureCache.SHORT_TTL,
                    )
                } else {
                    cache.put(cacheKey, bitmap)
                    ThumbnailResult.Image(bitmap)
                }
            }
        } finally {
            releaseGate(cacheKey, gate)
        }
    }

    private fun remember(key: String, failure: ThumbnailResult, ttl: Long): ThumbnailResult {
        failures.put(key, failure, ttl)
        return failure
    }

    private suspend fun render(document: DocumentEntity): Bitmap? = when (document.format) {
        DocumentFormat.PDF -> renderPdf(document.uri.toUri())
        DocumentFormat.MARKDOWN, DocumentFormat.TEXT, DocumentFormat.CODE, DocumentFormat.CSV -> renderText(document.uri.toUri())
        DocumentFormat.DOCX, DocumentFormat.ODT, DocumentFormat.DOC -> renderOffice(document)
        DocumentFormat.EPUB -> renderEpub(document.uri.toUri())
        DocumentFormat.PPTX -> renderPptx(document.uri.toUri())
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
        if (!io.github.pixelrick420.papyrus.viewer.TextSniffer.looksLikeText(head)) return@use null
        val lines = String(head, Charsets.UTF_8).lineSequence().take(MAX_THUMBNAIL_LINES).toList()
        drawTextTile(lines)
    }

    private fun renderOffice(document: DocumentEntity): Bitmap? {
        // mediaDir = null: thumbnails are text only, and writing media would double the cacheDir cost.
        val uri = document.uri.toUri()
        val blocks = if (document.format == DocumentFormat.DOC) {
            val source = { requiredStream(uri) }
            DocTextExtractor.extract(source, THUMBNAIL_BLOCK_BUDGET, document.sizeBytes)
        } else {
            val source = { requiredStream(uri) }
            OfficeTextExtractor(mediaDir = null)
                .extract(source, document.format, THUMBNAIL_BLOCK_BUDGET)
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

    private fun renderEpub(uri: Uri): Bitmap? {
        val open = { requiredStream(uri) }
        val extractor = EpubExtractor(mediaDir = null, blockBudget = THUMBNAIL_BLOCK_BUDGET)
        extractor.readCover(open)?.let { bytes -> decodeCover(bytes)?.let { return it } }
        return drawTextTile(previewLines(extractor.extract(open, previewChapters = EPUB_PREVIEW_CHAPTERS).blocks))
    }

    private suspend fun renderPptx(uri: Uri): Bitmap? = try {
        withTimeoutOrNull(PPTX_THUMBNAIL_BUDGET_MS) {
            // No text index for a thumbnail, and skip hidden leading slides so the tile shows a real page.
            SlidePageSource.open(context, uri, buildTextIndex = false).use { source ->
                val first = (0 until source.pageCount).firstOrNull { it !in source.hidden } ?: 0
                val page = source.render(first, THUMBNAIL_PDF_WIDTH_PX) ?: return@withTimeoutOrNull null
                page.scale(THUMBNAIL_WIDTH_PX, THUMBNAIL_HEIGHT_PX, true)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }

    // Never holds the cover at full size: refuses a picture claiming more than [MAX_COVER_PIXELS], subsamples, then fills the tile anchored at the top so a tall cover keeps its title.
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

    private fun requiredStream(uri: Uri): InputStream = openStream(uri) ?: throw FileNotFoundException(uri.toString())

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
        private const val MAX_CONCURRENT_RENDERS = 2
        private const val PPTX_THUMBNAIL_BUDGET_MS = 5_000L

        /** Pixels a cover may claim before it is not worth decoding for a 132 by 176 tile. */
        private const val MAX_COVER_PIXELS = 40L * 1000 * 1000

        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 16).toInt().coerceIn(4 shl 20, 32 shl 20)
    }
}
