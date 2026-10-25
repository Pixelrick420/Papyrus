package com.papyrus.app.viewer

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * A PDF's page text and links, read a page at a time as touched; one [PDDocument] stays open until
 * [close] since loading parses the whole file, and reads share a lock as it is not thread-safe.
 */
class PdfPageTextSource(
    private val context: Context,
    private val uri: Uri,
) : Closeable {

    private val lock = Mutex()
    private val pages = HashMap<Int, PdfPageText>()
    private val linkPages = HashMap<Int, List<PdfPageLink>>()
    private var outlineCache: List<OutlineEntry>? = null
    private var document: PDDocument? = null
    private var unreadable = false
    private var closed = false

    /** The page's text and boxes, or null if the document cannot be read or has no such page. */
    suspend fun page(index: Int): PdfPageText? = lock.withLock {
        if (closed) return@withLock null
        pages[index]?.let { return@withLock it }
        withContext(Dispatchers.IO) { read(index) }?.also { pages[index] = it }
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
     * of the file. Not cached here; the caller holds the list as long as its search needs it, and
     * the extractor's document-wide box budget still bounds what a huge file may keep.
     */
    suspend fun allPages(): List<PdfPageText>? = lock.withLock {
        if (closed) null
        else withContext(Dispatchers.IO) {
            val open = document ?: open()
            open?.let { PdfTextExtractor.extract(it) }
        }
    }

    /**
     * Every page's text in order joined by [PAGE_SEPARATOR], cut at [maxChars], or null if the
     * document cannot be read. For the search index.
     *
     * Reads a page at a time instead of reusing [allPages]: that method keeps 4 floats per character
     * (16 bytes, up to about 32 MB for one document), which indexing has no use for.
     */
    suspend fun allText(maxChars: Int): String? = lock.withLock {
        if (closed) null else withContext(Dispatchers.IO) { readAllText(maxChars) }
    }

    /** Runs on the IO dispatcher, under [lock]. */
    private fun readAllText(maxChars: Int): String? {
        val open = document ?: open() ?: return null
        if (maxChars <= 0) return ""
        val text = StringBuilder()
        for (index in 0 until open.numberOfPages) {
            if (text.length >= maxChars) break
            if (text.isNotEmpty()) text.append(PAGE_SEPARATOR)
            text.append(PdfTextExtractor.extractPage(open, index).text)
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
                    pages.clear()
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
    }
}
