package com.papyrus.app.viewer

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

private typealias PageTexts = BoundedCache<PageTextKey, PdfPageText>

private const val PAGE_TEXT_HEAP_SHARE = 32L
private const val MIN_PAGE_TEXT_BYTES = 4L shl 20
private const val MAX_PAGE_TEXT_BYTES = 24L shl 20

private fun newPageTextCache(): PageTexts =
    BoundedCache(
        (Runtime.getRuntime().maxMemory() / PAGE_TEXT_HEAP_SHARE).coerceIn(MIN_PAGE_TEXT_BYTES, MAX_PAGE_TEXT_BYTES),
    ) { it.approxBytes }

// One [PDDocument] stays open until [close] since loading parses the whole file; reads share a lock as it is not thread-safe.
// A page's text is kept under the document's [TextCacheKey], so a later reader of the same bytes takes it instead of reading again.
class PdfPageTextSource(
    private val context: Context,
    private val uri: Uri,
) : Closeable {

    private val lock = Mutex()

    /** Holds this source's pages when nothing can vouch for the bytes, so no other open may reuse them. */
    private val ownPages = newPageTextCache()
    private val linkPages = HashMap<Int, List<PdfPageLink>>()
    private var outlineCache: List<OutlineEntry>? = null
    private var document: PDDocument? = null
    private var unreadable = false
    private var closed = false

    @Volatile
    private var storeOrNull: PageStore? = null

    private class PageStore(private val cache: PageTexts, private val document: TextCacheKey) {
        fun get(page: Int, fromPlatform: Boolean): PdfPageText? =
            cache.get(PageTextKey(document, page, fromPlatform))

        /** A page past the extractor's box budget has text but no boxes, which a selection cannot use. */
        fun keep(page: Int, text: PdfPageText) {
            if (text.hasBoxes || text.text.isBlank()) {
                cache.put(PageTextKey(document, page, text.fromPlatform), text)
            }
        }
    }

    private fun store(): PageStore = storeOrNull ?: openStore().also { storeOrNull = it }

    private fun openStore(): PageStore {
        val key = textCacheKey(context, uri)
        // Without a key nothing says the bytes are unchanged, so the pages stay with this source alone.
        return if (key != null) {
            PageStore(shared, key)
        } else {
            PageStore(ownPages, TextCacheKey(uri.toString(), 0L, 0L))
        }
    }

    suspend fun cached(index: Int, fromPlatform: Boolean): PdfPageText? =
        (storeOrNull ?: withContext(Dispatchers.IO) { store() }).get(index, fromPlatform)

    suspend fun remember(index: Int, text: PdfPageText) {
        (storeOrNull ?: withContext(Dispatchers.IO) { store() }).keep(index, text)
    }

    suspend fun page(index: Int): PdfPageText? = lock.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            val pages = store()
            pages.get(index, fromPlatform = false) ?: read(index)?.also { pages.keep(index, it) }
        }
    }

    private fun read(index: Int): PdfPageText? {
        val open = document ?: open() ?: return null
        if (index !in 0 until open.numberOfPages) return null
        return PdfTextExtractor.extractPage(open, index)
    }

    suspend fun links(index: Int): List<PdfPageLink>? = lock.withLock {
        if (closed) return@withLock null
        linkPages[index]?.let { return@withLock it }
        withContext(Dispatchers.IO) { readLinks(index) }?.also { linkPages[index] = it }
    }

    private fun readLinks(index: Int): List<PdfPageLink>? {
        val open = document ?: open() ?: return null
        if (index !in 0 until open.numberOfPages) return null
        return PdfLinkExtractor.extract(open, index)
    }

    // Read under the same lock as the pages, off the main thread, so it never delays the first page; [emit] must not block.
    suspend fun outline(emit: (List<OutlineEntry>) -> Unit = {}): List<OutlineEntry>? = lock.withLock {
        if (closed) return@withLock null
        outlineCache?.let { cached ->
            if (cached.isNotEmpty()) emit(cached)
            return@withLock cached
        }
        withContext(Dispatchers.IO) { readOutline(emit) }?.also { outlineCache = it }
    }

    private fun readOutline(emit: (List<OutlineEntry>) -> Unit): List<OutlineEntry>? {
        val open = document ?: open() ?: return null
        return PdfOutlineReader.read(open, emit)
    }

    // One whole-document pass on the open document, so find shares this parse instead of opening a second copy; pages already held are taken as they are, and the pages this pass reads are kept for other readers.
    suspend fun allPages(): List<PdfPageText>? = lock.withLock {
        if (closed) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val open = document ?: open()
                val pages = store()
                open?.let { opened ->
                    PdfTextExtractor.extract(opened) { pages.get(it, fromPlatform = false) }.also { all ->
                        all.forEachIndexed { index, text -> pages.keep(index, text) }
                    }
                }
            }
        }
    }

    // Reads a page at a time instead of [allPages], which keeps 4 floats per character (up to about 32 MB for one document) that indexing has no use for.
    suspend fun allText(maxChars: Int, dispatcher: CoroutineDispatcher = Dispatchers.IO): String? = lock.withLock {
        if (closed) null else withContext(dispatcher) { readAllText(maxChars) }
    }

    private fun readAllText(maxChars: Int): String? {
        val open = document ?: open() ?: return null
        if (maxChars <= 0) return ""
        val pages = store()
        val text = StringBuilder()
        for (index in 0 until open.numberOfPages) {
            if (text.length >= maxChars) break
            if (text.isNotEmpty()) text.append(PAGE_SEPARATOR)
            // A page already held is used; one read here is not kept, or a pass over a whole library
            // would push out the pages a reader has open.
            text.append((pages.get(index, fromPlatform = false) ?: PdfTextExtractor.extractPage(open, index)).text)
        }
        return if (text.length <= maxChars) text.toString() else text.substring(0, maxChars)
    }

    /** A document that failed to load is not retried: loading is the expensive part, and it would fail the same way. */
    private fun open(): PDDocument? {
        if (unreadable) return null
        PDFBoxResourceLoader.init(context)
        val loaded = try {
            context.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
        } catch (ignored: Exception) {
            // PdfBox throws unchecked on malformed files, so catch Exception, not just IOException.
            null
        }
        if (loaded == null) unreadable = true
        document = loaded
        return loaded
    }

    override fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                lock.withLock {
                    closed = true
                    ownPages.clear()
                    linkPages.clear()
                    outlineCache = null
                    runCatching { document?.close() }
                    document = null
                }
            }
        }
    }

    companion object {
        const val PAGE_SEPARATOR = "\n"

        private val shared = newPageTextCache()
    }
}
