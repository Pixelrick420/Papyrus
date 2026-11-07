package com.papyrus.app.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

class PdfPasswordException : IOException("Password-protected PDF")

/** Thread-safe wrapper over one [PdfEngine], which allows one open page at a time; renders serialise and cache. */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfPageSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val engine: PdfEngine,
    private val capabilities: PdfCapabilities,
    private val tempFile: File?,
) : Closeable {

    val pageCount: Int = engine.pageCount

    /** True when the reader may be offered filling: this engine has the API and the form is an AcroForm. */
    val hasForm: Boolean get() = capabilities.forms

    /** True when a saved copy can carry the reader's highlights as page annotations. */
    val canAnnotate: Boolean get() = capabilities.annotations

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val lock = Mutex()
    private var closed = false

    private val cacheBudgetBytes = cacheBytes()

    /**
     * The most pixels one page is rasterised at. A few zoomed pages are on screen at once and each
     * holds its bitmap, so one page may take only a share of the cache; a page past it would be
     * dropped from the cache as it went in, or fail to allocate and leave the page blank.
     */
    val maxRenderPixels: Int = cacheBudgetBytes / (BYTES_PER_PIXEL * PAGES_PER_CACHE)

    private val cache = object : LruCache<PageKey, Bitmap>(cacheBudgetBytes) {
        // allocationByteCount, since a reused bitmap can hold more than its pixels need. Must stay >= 1:
        // a recycled bitmap reports 0, and LruCache's sizeOf must be stable.
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    /** height / width for every page, so list items can reserve space before rendering. */
    suspend fun loadAspectRatios(): List<Float> = withContext(dispatcher) {
        lock.withLock {
            List(pageCount) { i -> engine.open(i).use { it.height.toFloat() / it.width } }
        }
    }

    fun cached(index: Int, widthPx: Int): Bitmap? = cache.get(PageKey(index, widthPx))

    /**
     * Drops the cached renders of pages outside [keep], so pages scrolled far away stop holding memory.
     * Not recycled: a page on screen may still be drawing its bitmap, and the GC frees it once unused.
     */
    fun evictOutside(keep: IntRange) {
        for (key in cache.snapshot().keys) {
            if (key.index !in keep) cache.remove(key)
        }
    }

    /** Memory pressure: everything when [evictAll], otherwise down to half the budget, oldest first. */
    fun trimMemory(evictAll: Boolean) {
        if (evictAll) cache.evictAll() else cache.trimToSize(cache.maxSize() / 2)
    }

    /**
     * Returns null if the source was closed meanwhile, or if the page is too large to render. Renders
     * queue behind one another, so a caller cancelled while waiting (its page scrolled away) never
     * starts; a render already running cannot be interrupted and finishes into the cache.
     */
    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = PageKey(index, widthPx)
        cache.get(key)?.let { return it }
        return withContext(dispatcher) {
            lock.withLock {
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

    /**
     * The page's text in the viewer's model, or null when the engine has no text API, the page
     * fails, or what it reports is not something a finger can select from (no text, text without
     * boxes, a stream that wraps over several lines); the caller then falls back to the PDFBox
     * reader, which every device has. An empty answer must be null too: a page the engine cannot
     * read is not a blank page, and handing it back as one would never reach PDFBox.
     */
    internal suspend fun platformText(index: Int): PdfPageText? {
        if (!capabilities.text) return null
        return withPage(index) { page ->
            val lines = page.text()
            val width = page.width.toFloat()
            val height = page.height.toFloat()
            if (PdfPlatformText.isUsable(lines, width, height)) PdfPlatformText.from(lines, width, height) else null
        }
    }

    /**
     * The page's matches from the platform search, or null to scan the text model instead. The
     * platform scan is exact-case, so a query with capitals stays with the case-insensitive model.
     */
    internal suspend fun platformSearch(index: Int, query: String): PdfPageText.PageMatches? {
        if (!capabilities.search || query != query.lowercase()) return null
        val needle = PdfPageText.normalizeQuery(query)
        if (needle.isEmpty()) return null
        return withPage(index) { page ->
            val hits = page.search(needle)
            PdfPageText.PageMatches(
                count = hits.size,
                groups = PdfPlatformText.groups(hits, page.width.toFloat(), page.height.toFloat()),
            )
        }
    }

    /** The lines the platform selects between two boundaries, or null when it has no selection API. */
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

    /**
     * Every form widget in the document, in page order. Read a page at a time off the main thread, so
     * a large form never delays the first page; past [MAX_FORM_FIELDS] the rest are left out rather
     * than holding an unbounded list for a sheet to show.
     */
    internal suspend fun formFields(): List<PdfFormField> {
        if (!capabilities.forms) return emptyList()
        val fields = ArrayList<PdfFormField>()
        for (index in 0 until pageCount) {
            if (fields.size >= MAX_FORM_FIELDS) break
            fields += withPage(index) { it.widgets() }.orEmpty()
        }
        return fields
    }

    /** One page's widgets, for refreshing just that page after an edit. */
    internal suspend fun formFields(page: Int): List<PdfFormField> {
        if (!capabilities.forms) return emptyList()
        return withPage(page) { it.widgets() }.orEmpty()
    }

    /** Applies [edit] to page [index] in memory; false when the engine refused or the page failed. */
    internal suspend fun applyFormEdit(index: Int, edit: PdfFormEdit): Boolean {
        if (!capabilities.forms) return false
        return withPage(index) { it.applyEdit(edit) } ?: false
    }

    /**
     * Writes the document, edits included, to [destination]; [highlights] adds one annotation per
     * mark, by page. Runs on the render dispatcher, so it never overlaps a page being read; the
     * source file and this renderer stay as they were.
     *
     * All or nothing: a page whose annotation is refused fails the whole write, rather than leaving
     * a copy whose highlights are silently missing. The caller deletes the half-written file.
     */
    internal suspend fun writeCopy(
        destination: ParcelFileDescriptor,
        highlights: Map<Int, List<List<NormRect>>> = emptyMap(),
    ): Boolean = withContext(dispatcher) {
        lock.withLock {
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

    /** Called with [lock] held, since the engine allows one open page at a time. */
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

    /** Runs [attempt] on page [index], serialised with rendering; null when closed or the page fails. */
    private suspend fun <T> withPage(index: Int, attempt: (PdfEnginePage) -> T): T? =
        withContext(dispatcher) {
            lock.withLock {
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

    /**
     * Closes after any in-flight render; the detached body is guarded so an escaping exception can't
     * kill the process.
     */
    override fun close() {
        synchronized(openSources) { openSources.remove(this) }
        CoroutineScope(dispatcher).launch {
            runCatching {
                lock.withLock {
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

        /** Pages that may sit at the largest size together before the cache is full. */
        private const val PAGES_PER_CACHE = 3

        /** Weak, so a source that is never closed cannot be kept alive by the memory-pressure hook. */
        private val openSources: MutableSet<PdfPageSource> =
            Collections.newSetFromMap(WeakHashMap<PdfPageSource, Boolean>())

        /** Trims every open source's page cache; the application calls this when the system is short of memory. */
        fun trimAll(evictAll: Boolean) {
            val sources = synchronized(openSources) { openSources.toList() }
            for (source in sources) source.trimMemory(evictAll)
        }

        /** One sheet's worth of widgets; past this the form is not offered rather than half-listed. */
        private const val MAX_FORM_FIELDS = 300

        /** Upper bound on a page bitmap's height, so a degenerate page size never asks for a bitmap the device cannot hold. */
        private const val MAX_PAGE_HEIGHT_PX = 16_384

        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(8 shl 20, 128 shl 20)

        suspend fun open(context: Context, uri: Uri): PdfPageSource = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
            try {
                return@withContext openDescriptor(pfd, null)
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
                    openDescriptor(copyPfd, copy)
                } catch (e: Exception) {
                    copyPfd.close()
                    throw e
                }
            } catch (e: Exception) {
                copy.delete()
                throw e
            }
        }

        /** Opens an already-decrypted local copy; the caller owns and deletes [file]. */
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

        private fun createEngine(pfd: ParcelFileDescriptor, capabilities: PdfCapabilities): PdfEngine =
            try {
                PdfEngineFactory.create(pfd, capabilities)
            } catch (e: SecurityException) {
                throw PdfPasswordException() // PdfRenderer signals password-protected files this way
            }
    }
}
