package io.github.pixelrick420.papyrus.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.createBitmap
import io.github.pixelrick420.papyrus.data.openRequired
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

class PdfPasswordException : IOException("Password-protected PDF")

internal fun shouldCopyAfterOpenFailure(failure: Throwable): Boolean =
    failure !is PdfPasswordException && failure !is SecurityException

internal fun platformSearchNeedle(capabilities: PdfCapabilities, query: String): String? {
    if (!capabilities.search) return null
    return PdfPageText.normalizeQuery(query).takeIf { it.isNotEmpty() }
}

internal fun shouldRetryMixedCaseSearch(query: String, platformHits: Int): Boolean =
    platformHits == 0 && query != query.lowercase()

@OptIn(ExperimentalCoroutinesApi::class)
class PdfPageSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val engine: PdfEngine,
    private val capabilities: PdfCapabilities,
    private val tempFile: File?,
) : PageBitmapSource {

    override val pageCount: Int = engine.pageCount

    private val _aspectRatios = MutableStateFlow(List(pageCount) { DEFAULT_ASPECT })
    val aspectRatios: StateFlow<List<Float>> = _aspectRatios.asStateFlow()

    val hasForm: Boolean get() = capabilities.forms

    val canAnnotate: Boolean get() = capabilities.annotations

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val searchDispatcher = Dispatchers.Default.limitedParallelism(1)
    // Find walks every page; a separate dispatcher keeps that queue off a visible page's render.
    // PdfRenderer is not thread-safe, so both dispatchers still share this engine mutex.
    private val engineLock = Mutex()
    private var closed = false

    private val cacheBudgetBytes = bitmapCacheBudgetBytes()

    // A few zoomed pages are on screen at once and each holds its bitmap, so one page may take only a share of the cache; a page past it would be dropped as it went in, or fail to allocate.
    override val maxRenderPixels: Int = cacheBudgetBytes / (BYTES_PER_PIXEL * PAGES_PER_CACHE)

    private val cache = object : LruCache<PageKey, Bitmap>(cacheBudgetBytes) {
        // allocationByteCount, since a reused bitmap can hold more than its pixels need. Must stay >= 1:
        // a recycled bitmap reports 0, and LruCache's sizeOf must be stable.
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    override suspend fun loadAspectRatios(): List<Float> = withContext(dispatcher) {
        List(pageCount) { loadAspectRatio(it) }.also { _aspectRatios.value = it }
    }

    internal suspend fun loadAspectRatio(index: Int): Float =
        withPage(index) { sanitizedAspect(it.width.toFloat(), it.height.toFloat()) } ?: DEFAULT_ASPECT

    override fun cached(index: Int, widthPx: Int): Bitmap? = cache.get(PageKey(index, widthPx))

    // Not recycled: a page on screen may still be drawing its bitmap, and the GC frees it once unused.
    override fun evictOutside(keep: IntRange) {
        for (key in cache.snapshot().keys) {
            if (key.index !in keep) cache.remove(key)
        }
    }

    override fun trimMemory(evictAll: Boolean) {
        if (evictAll) cache.evictAll() else cache.trimToSize(cache.maxSize() / 2)
    }

    // Renders queue behind one another, so a caller cancelled while waiting never starts; a render already running cannot be interrupted and finishes into the cache.
    override suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = PageKey(index, widthPx)
        cache.get(key)?.let { return it }
        return withContext(dispatcher) {
            engineLock.withLock {
                if (closed) return@withLock null
                cache.get(key)?.let { return@withLock it }
                // Checked with the lock held: the wait is where a page has time to scroll away.
                ensureActive()
                try {
                    engine.open(index).use { page ->
                        if (page.width <= 0 || page.height <= 0) return@withLock null
                        val height = (widthPx.toFloat() * page.height / page.width).roundToInt().coerceIn(1, MAX_PAGE_HEIGHT_PX)
                        val bitmap = createBitmap(widthPx, height)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap)
                        cache.put(key, bitmap)
                        bitmap
                    }
                } catch (_: OutOfMemoryError) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            }
        }
    }

    // Null when the engine's text is not something a finger can select from (no text, text without boxes, a stream wrapping over several lines): the caller falls back to PDFBox.
    // An empty answer must be null too, or a page the engine cannot read would pass as blank and never reach PDFBox.
    internal suspend fun platformText(index: Int): PdfPageText? {
        if (!capabilities.text) return null
        return withPage(index) { page ->
            val lines = page.text()
            val width = page.width.toFloat()
            val height = page.height.toFloat()
            if (PdfPlatformText.isUsable(lines, width, height)) PdfPlatformText.from(lines, width, height) else null
        }
    }

    // Platform search case-sensitivity is not guaranteed; let PDFBox retry a mixed-case query with no hits.
    internal suspend fun platformSearch(index: Int, query: String): PdfPageText.PageMatches? {
        val needle = platformSearchNeedle(capabilities, query) ?: return null
        return withSearchPage(index) { page ->
            val hits = page.search(needle)
            if (shouldRetryMixedCaseSearch(needle, hits.size)) return@withSearchPage null
            PdfPageText.PageMatches(
                count = hits.size,
                groups = PdfPlatformText.groups(hits, page.width.toFloat(), page.height.toFloat()),
            )
        }
    }

    internal suspend fun platformSelect(
        index: Int,
        start: PlatformBoundary,
        stop: PlatformBoundary,
    ): PdfPlatformSelection? {
        if (!capabilities.selection) return null
        return withPage(index) { page ->
            page.select(start, stop)?.let {
                PdfPlatformText.selection(it, page.width.toFloat(), page.height.toFloat())
            }
        }
    }

    // Read a page at a time off the main thread; past [MAX_FORM_FIELDS] the rest are left out rather than holding an unbounded list.
    internal suspend fun formFields(): List<PdfFormField> {
        if (!capabilities.forms) return emptyList()
        val fields = ArrayList<PdfFormField>()
        for (index in 0 until pageCount) {
            if (fields.size >= MAX_FORM_FIELDS) break
            fields += withPage(index) { it.widgets() }.orEmpty()
        }
        return fields
    }

    internal suspend fun formFields(page: Int): List<PdfFormField> {
        if (!capabilities.forms) return emptyList()
        return withPage(page) { it.widgets() }.orEmpty()
    }

    internal suspend fun applyFormEdit(index: Int, edit: PdfFormEdit): Boolean {
        if (!capabilities.forms) return false
        return withPage(index) { it.applyEdit(edit) } ?: false
    }

    // Runs on the render dispatcher, so it never overlaps a page being read. All or nothing: a refused annotation fails the whole write rather than leaving a copy whose highlights are silently missing.
    // The caller deletes the half-written file.
    internal suspend fun writeCopy(
        destination: ParcelFileDescriptor,
        highlights: Map<Int, List<List<NormRect>>> = emptyMap(),
    ): Boolean = withContext(dispatcher) {
        engineLock.withLock {
            if (closed) return@withLock false
            try {
                if (highlights.isNotEmpty()) {
                    // A copy that claims highlights the engine cannot write is not a copy at all.
                    if (!capabilities.annotations) return@withLock false
                    for ((page, marks) in highlights) {
                        if (!annotate(page, marks)) return@withLock false
                    }
                }
                engine.write(destination)
            } catch (ignored: Exception) {
                // Where and which class only: the file being written is the reader's document.
                Log.w(TAG, "PDF copy failed: ${ignored.javaClass.simpleName}")
                false
            }
        }
    }

    /** Called with [engineLock] held, since the engine allows one open page at a time. */
    private fun annotate(page: Int, marks: List<List<NormRect>>): Boolean {
        return try {
            engine.open(page).use { it.highlight(marks) }
        } catch (ignored: Exception) {
            logPageFailure(page, ignored)
            false
        } catch (ignored: LinkageError) {
            logPageFailure(page, ignored)
            false
        }
    }

    private suspend fun <T> withPage(index: Int, attempt: (PdfEnginePage) -> T): T? =
        withPageOn(dispatcher, engineLock, index, attempt)

    private suspend fun <T> withSearchPage(index: Int, attempt: (PdfEnginePage) -> T): T? =
        withPageOn(searchDispatcher, engineLock, index, attempt)

    private suspend fun <T> withPageOn(
        context: CoroutineDispatcher,
        mutex: Mutex,
        index: Int,
        attempt: (PdfEnginePage) -> T,
    ): T? = withContext(context) {
        mutex.withLock {
            if (closed) return@withLock null
            try {
                engine.open(index).use(attempt)
            } catch (ignored: Exception) {
                logPageFailure(index, ignored)
                null
            } catch (ignored: LinkageError) {
                logPageFailure(index, ignored)
                null
            }
        }
    }

    /** Where and which class only, never what: a page can hold form values or text the reader typed. */
    private fun logPageFailure(index: Int, error: Throwable) {
        Log.w(TAG, "PDF page $index unavailable: ${error.javaClass.simpleName}")
    }

    // Closes after any in-flight render; the detached body is guarded so an escaping exception can't
    // kill the process. Cancellation is part of that guard: closing must never propagate.
    @Suppress("SuspendFunSwallowedCancellation")
    override fun close() {
        synchronized(openSources) { openSources.remove(this) }
        CoroutineScope(dispatcher).launch {
            runCatching {
                engineLock.withLock {
                    if (closed) return@withLock
                    closed = true
                    runCatching { cache.evictAll() }
                    runCatching { engine.close() }
                    runCatching { descriptor.close() }
                    tempFile?.delete()
                }
            }
        }
    }

    private data class PageKey(val index: Int, val widthPx: Int)

    companion object {
        private const val TAG = "PdfPageSource"

        private const val BYTES_PER_PIXEL = 4

        private const val PAGES_PER_CACHE = 3

        /** Weak, so a source that is never closed cannot be kept alive by the memory-pressure hook. */
        private val openSources: MutableSet<PdfPageSource> =
            Collections.newSetFromMap(WeakHashMap<PdfPageSource, Boolean>())

        fun trimAll(evictAll: Boolean) {
            val sources = synchronized(openSources) { openSources.toList() }
            for (source in sources) source.trimMemory(evictAll)
        }

        private const val MAX_FORM_FIELDS = 300

        /** Upper bound on a page bitmap's height, so a degenerate page size never asks for a bitmap the device cannot hold. */
        internal const val MAX_PAGE_HEIGHT_PX = 16_384

        // An unseekable PDF gets one bounded copy; the descriptor is closed unless the source owns it.
        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        suspend fun open(context: Context, uri: Uri): PdfPageSource = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val usableSpace = context.cacheDir.usableSpace
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
            var directOpen = false
            try {
                try {
                    return@withContext openDescriptor(pfd, null).also { directOpen = true }
                } catch (e: Exception) {
                    if (!shouldCopyAfterOpenFailure(e)) throw e
                    val fallback = pdfOpenFallback(
                        statSize = pfd.statSize,
                        seekable = isSeekable(pfd),
                        hasPdfHeader = runCatching { hasPdfHeader(resolver.openInputStream(uri)) }.getOrDefault(false),
                        usableSpace = usableSpace,
                    )
                    when (fallback) {
                        PdfOpenFallback.FAIL_FORMAT -> throw IOException("Not a seekable PDF", e)
                        PdfOpenFallback.FAIL_NO_SPACE -> throw IOException("Not enough cache space for this PDF", e)
                        PdfOpenFallback.COPY -> Unit
                    }
                }
                val dir = File(context.cacheDir, PDF_VIEW_DIR).apply { mkdirs() }
                val copy = File.createTempFile("view-", ".pdf", dir)
                val limit = minOf(MAX_PDF_COPY_BYTES, usableSpace)
                try {
                    resolver.openRequired(uri).use { input -> copy.outputStream().use { copyBounded(input, it, limit) } }
                    val copyPfd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                    try {
                        return@withContext openDescriptor(copyPfd, copy)
                    } catch (e: Exception) {
                        copyPfd.close()
                        throw e
                    }
                } catch (e: Exception) {
                    copy.delete()
                    throw e
                }
            } finally {
                if (!directOpen) pfd.close()
            }
        }

        private const val PDF_HEADER_SNIFF_BYTES = 1024

        private fun hasPdfHeader(stream: InputStream?): Boolean = stream?.use { input ->
            val head = ByteArray(PDF_HEADER_SNIFF_BYTES)
            var read = 0
            while (read < head.size) {
                val count = input.read(head, read, head.size - read)
                if (count < 0) break
                read += count
            }
            startsWithPdfHeader(head.copyOf(read))
        } ?: false

        private fun isSeekable(pfd: ParcelFileDescriptor): Boolean = try {
            Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            true
        } catch (_: ErrnoException) {
            false
        }

        // Close the descriptor before rethrowing an open failure.
        @Suppress("TooGenericExceptionCaught")
        suspend fun openFile(file: File): PdfPageSource = withContext(Dispatchers.IO) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                openDescriptor(pfd, null)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
        }

        private fun openDescriptor(pfd: ParcelFileDescriptor, tempFile: File?): PdfPageSource {
            val device = devicePdfCapabilities()
            val engine = createEngine(pfd, device)
            // A platform engine that turned out to be missing at open renders only, so its text,
            // form and annotation calls must not be attempted either.
            val aligned = if (engine.kind == device.kind) device else PdfCapabilities.legacy()
            // An XFA form is drawn but never filled: the platform's AcroForm edit path does not apply.
            val capabilities = aligned.copy(forms = aligned.forms && engine.formType.fillable)
            return PdfPageSource(pfd, engine, capabilities, tempFile).also {
                synchronized(openSources) { openSources.add(it) }
            }
        }

        // PdfRenderer signals a password-protected file as a SecurityException.
        @Suppress("SwallowedException")
        private fun createEngine(pfd: ParcelFileDescriptor, capabilities: PdfCapabilities): PdfEngine =
            try {
                PdfEngineFactory.create(pfd, capabilities)
            } catch (e: SecurityException) {
                throw PdfPasswordException() // PdfRenderer signals password-protected files this way
            }
    }
}
