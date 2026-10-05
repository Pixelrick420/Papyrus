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
 * The text of a PDF's pages one at a time, as a reader touches them.
 *
 * Find reads every page up front, which is right for a search and far too slow to wait on when a
 * finger has just gone down on page 212. This keeps one [PDDocument] open and reads only the page
 * asked for, then remembers it. The document stays open until [close] because loading one is a
 * parse of the whole file, and the next page a drag runs onto should not pay that again.
 *
 * [PDDocument] is not thread-safe, so every read goes through one lock.
 */
class PdfPageTextSource(
    private val context: Context,
    private val uri: Uri,
) : Closeable {

    private val lock = Mutex()
    private val pages = HashMap<Int, PdfPageText>()
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

    /** A document that failed to load is not retried: loading is the expensive part, and it would fail the same way. */
    private fun open(): PDDocument? {
        if (unreadable) return null
        PDFBoxResourceLoader.init(context)
        val loaded = try {
            context.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
        } catch (ignored: Exception) {
            // Anything PdfBox cannot parse, not only an IOException: it throws unchecked on some malformed files.
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
                    runCatching { document?.close() }
                    document = null
                }
            }
        }
    }
}
