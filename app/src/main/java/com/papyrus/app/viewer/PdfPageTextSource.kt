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

/** The share of the heap page texts may take, and the bounds on it for a small and a large heap. */
private const val PAGE_TEXT_HEAP_SHARE = 32L
private const val MIN_PAGE_TEXT_BYTES = 4L shl 20
private const val MAX_PAGE_TEXT_BYTES = 24L shl 20

private fun newPageTextCache(): PageTexts =
    BoundedCache(
        (Runtime.getRuntime().maxMemory() / PAGE_TEXT_HEAP_SHARE).coerceIn(MIN_PAGE_TEXT_BYTES, MAX_PAGE_TEXT_BYTES),
    ) { it.approxBytes }

/**
 * A PDF's page text and links, read a page at a time as touched; one [PDDocument] stays open until
 * [close] since loading parses the whole file, and reads share a lock as it is not thread-safe.
 *
 * A page's text is kept under the document's [TextCacheKey] (URI, modified time and size), so a
 * later reader of the same bytes, in this open or the next, takes it instead of reading it again.
 */
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

    /** The cache this document's pages go in, and the key they go in under. */
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

    /** Blocking: one provider query, the first time. Call on an IO thread. */
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

    /**
     * A page read before, by this source or an earlier open of the same bytes, as the platform engine
     * ([fromPlatform]) or PDFBox read it; null when none is held. Never reads the document.
     */
    suspend fun cached(index: Int, fromPlatform: Boolean): PdfPageText? =
        (storeOrNull ?: withContext(Dispatchers.IO) { store() }).get(index, fromPlatform)

    /** Keeps a page read elsewhere, such as by the platform engine, so every later reader shares it. */
    suspend fun remember(index: Int, text: PdfPageText) {
        (storeOrNull ?: withContext(Dispatchers.IO) { store() }).keep(index, text)
    }

    /** The page's text and boxes, or null if the document cannot be read or has no such page. */
    suspend fun page(index: Int): PdfPageText? = lock.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            val pages = store()
            pages.get(index, fromPlatform = false) ?: read(index)?.also { pages.keep(index, it) }
        }
    }

    /** Runs on the IO dispatcher, under [lock]. */
    private fun read(index: Int): PdfPageText? {
        val open = document ?: open() ?: return null
        if (index !in 0 until open.numberOfPages) return null
        return PdfTextExtractor.extractPage(open, index)
    }

    /** The page's links, or null if unreadable or no such page. Read once per page. */
    suspend fun links(index: Int): List<PdfPageLink>? = lock.withLock {
        if (closed) return@withLock null
        linkPages[index]?.let { return@withLock it }
        withContext(Dispatchers.IO) { readLinks(index) }?.also { linkPages[index] = it }
    }

    /** Runs on the IO dispatcher, under [lock]. */
    private fun readLinks(index: Int): List<PdfPageLink>? {
        val open = document ?: open() ?: return null
        if (index !in 0 until open.numberOfPages) return null
        return PdfLinkExtractor.extract(open, index)
    }

    /**
     * The document's bookmarks, or null if it cannot be read. Read once, on the same open document and
     * under the same lock as the pages, off the main thread, so it never delays the first page.
     *
     * [emit] is handed everything read so far as the walk proceeds, so a long outline can be shown
     * while the rest of it is still being read. It runs under the lock and must not block.
     */
    suspend fun outline(emit: (List<OutlineEntry>) -> Unit = {}): List<OutlineEntry>? = lock.withLock {
        if (closed) return@withLock null
        outlineCache?.let { cached ->
            if (cached.isNotEmpty()) emit(cached)
            return@withLock cached
        }
        withContext(Dispatchers.IO) { readOutline(emit) }?.also { outlineCache = it }
    }

    /** Runs on the IO dispatcher, under [lock]. */
    private fun readOutline(emit: (List<OutlineEntry>) -> Unit): List<OutlineEntry>? {
        val open = document ?: open() ?: return null
        return PdfOutlineReader.read(open, emit)
    }

    /**
     * Every page's text in order, or null if the document cannot be read — one whole-document pass on
     * the already-open document, so find-in-file shares this parse instead of opening a second copy
     * of the file. A page already held (read for TalkBack, a selection, read aloud or an earlier find)
     * is taken as it is, and the pages this pass reads are kept for those readers in turn. The caller
     * holds the list as long as its search needs it, and the extractor's document-wide box budget
     * still bounds what a huge file may keep.
     */
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

    /**
     * Every page's text in order joined by [PAGE_SEPARATOR], cut at [maxChars], or null if the
     * document cannot be read. For the search index.
     *
     * Reads a page at a time instead of reusing [allPages]: that method keeps 4 floats per character
     * (16 bytes, up to about 32 MB for one document), which indexing has no use for.
     */
    suspend fun allText(maxChars: Int, dispatcher: CoroutineDispatcher = Dispatchers.IO): String? = lock.withLock {
        if (closed) null else withContext(dispatcher) { readAllText(maxChars) }
    }

    /** Runs on the IO dispatcher, under [lock]. */
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

    /** Closes after any read in flight finishes; the body is guarded because it runs detached. */
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
        /** Joins page texts in [allText]. */
        const val PAGE_SEPARATOR = "\n"

        /** Pages of every document whose bytes a provider vouches for, kept after the viewer that read them closes. */
        private val shared = newPageTextCache()
    }
}
