package io.github.pixelrick420.papyrus.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.core.net.toUri
import io.github.pixelrick420.papyrus.viewer.BoundedCache
import io.github.pixelrick420.papyrus.viewer.DocTextExtractor
import io.github.pixelrick420.papyrus.viewer.EpubExtractor
import io.github.pixelrick420.papyrus.viewer.EpubProtectedException
import io.github.pixelrick420.papyrus.viewer.OfficeEncryption
import io.github.pixelrick420.papyrus.viewer.OfficeTextExtractor
import io.github.pixelrick420.papyrus.viewer.PdfEncryption
import io.github.pixelrick420.papyrus.viewer.localSourceFile
import io.github.pixelrick420.papyrus.viewer.PdfPageTextSource
import io.github.pixelrick420.papyrus.viewer.TextCacheKey
import io.github.pixelrick420.papyrus.viewer.pptx.SlideTextIndex
import io.github.pixelrick420.papyrus.viewer.pptx.text.FontCatalog
import io.github.pixelrick420.papyrus.viewer.readTextString
import io.github.pixelrick420.papyrus.viewer.textCacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

data class ExtractedText(
    val text: String,
    val state: TextIndexState,
)

// Same text find-in-file scans, so the index and the screen see the same words.
// Failures never throw, so the queue drains; SKIPPED is terminal, FAILED is retried with a bounded backoff.
class DocumentTextExtractor(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openStream: (Uri) -> InputStream,
    private val onFailure: (documentId: Long, failure: Throwable) -> Unit = { _, _ -> },
    private val maxSourceBytes: Long = MAX_SOURCE_BYTES,
) {

    // Keyed by what the provider says the bytes are now (URI, modified time, size); only indexed text is kept, since a failure may differ next time.
    private val extracted = BoundedCache<TextCacheKey, ExtractedText>(MAX_CACHED_BYTES) {
        it.text.length * 2L + CACHED_OVERHEAD_BYTES
    }

    private val fonts by lazy { FontCatalog(context.assets) }

    @Suppress("TooGenericExceptionCaught")
    suspend fun extract(document: DocumentEntity): ExtractedText = withContext(dispatcher) {
        // Checked before any open: a 2 GB scan should not be read just to be told it is too large.
        if (document.sizeBytes > MAX_SOURCE_BYTES) {
            return@withContext ExtractedText("", TextIndexState.TOO_LARGE)
        }
        val key = textCacheKey(context, document.uri.toUri())
        key?.let { extracted.get(it) }?.let { return@withContext it }
        val result = try {
            read(document) ?: ExtractedText("", TextIndexState.SKIPPED)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expectedDrm: EpubProtectedException) {
            ExtractedText("", TextIndexState.SKIPPED)
        } catch (_: SourceLimitExceeded) {
            // A provider that reports no size is still capped on the read path.
            ExtractedText("", TextIndexState.TOO_LARGE)
        } catch (failure: Throwable) {
            // A huge or malformed document must not kill the indexer or stall its queue.
            onFailure(document.id, failure)
            ExtractedText("", TextIndexState.FAILED)
        }
        if (key != null && result.state == TextIndexState.INDEXED) extracted.put(key, result)
        result
    }

    private suspend fun read(document: DocumentEntity): ExtractedText? = when (document.format) {
        DocumentFormat.PDF -> readPdf(document.uri.toUri())
        DocumentFormat.DOCX, DocumentFormat.ODT -> readOffice(document)
        DocumentFormat.DOC -> readDoc(document.uri.toUri(), document.sizeBytes)
        DocumentFormat.EPUB -> readEpub(document.uri.toUri())
        DocumentFormat.PPTX -> readPptx(document.uri.toUri())
        DocumentFormat.TEXT, DocumentFormat.CODE, DocumentFormat.MARKDOWN, DocumentFormat.CSV ->
            readPlainText(document.uri.toUri())
        DocumentFormat.UNKNOWN -> null
    }

    private suspend fun readPdf(uri: Uri): ExtractedText {
        if (PdfEncryption.status { stream(uri) } != PdfEncryption.NOT_ENCRYPTED) {
            return ExtractedText("", TextIndexState.SKIPPED)
        }
        val text = PdfPageTextSource(context, uri).use { source -> source.allText(MAX_INDEX_CHARS, dispatcher) }
            ?: return ExtractedText("", TextIndexState.FAILED)
        return indexable(text)
    }

    private fun readOffice(document: DocumentEntity): ExtractedText {
        val uri = document.uri.toUri()
        if (OfficeEncryption.isEncrypted({ stream(uri) }, sourceFile = localSourceFile(uri.toString()))) {
            return ExtractedText("", TextIndexState.SKIPPED)
        }
        // mediaDir = null, as in ThumbnailLoader: indexing needs the words, not the pictures.
        val blocks = OfficeTextExtractor(mediaDir = null).extract({ stream(uri) }, document.format)
        return indexable(officeBlocksToText(blocks, MAX_INDEX_CHARS))
    }

    private fun readDoc(uri: Uri, sizeBytes: Long): ExtractedText {
        if (OfficeEncryption.isEncrypted({ stream(uri) }, knownLength = sizeBytes, sourceFile = localSourceFile(uri.toString()))) {
            return ExtractedText("", TextIndexState.SKIPPED)
        }
        return indexable(officeBlocksToText(DocTextExtractor.extract({ stream(uri) }, knownLength = sizeBytes), MAX_INDEX_CHARS))
    }

    private fun readPptx(uri: Uri): ExtractedText {
        if (OfficeEncryption.isEncrypted({ stream(uri) }, sourceFile = localSourceFile(uri.toString()))) {
            return ExtractedText("", TextIndexState.SKIPPED)
        }
        // mediaDir = null: a deck's words are indexed, not its pictures.
        val pages = SlideTextIndex.indexPages(
            open = { stream(uri) },
            fonts = fonts,
            newParserFactory = { Xml.newPullParser() },
        )
        return indexable(pages.joinToString("\n"))
    }

    private fun readEpub(uri: Uri): ExtractedText {
        val book = EpubExtractor(mediaDir = null).extract({ stream(uri) })
        return indexable(officeBlocksToText(book.blocks, MAX_INDEX_CHARS))
    }

    private fun readPlainText(uri: Uri): ExtractedText {
        val decoded = stream(uri).use { readTextString(it, MAX_SOURCE_TEXT_BYTES) }
        return indexable(decoded.text)
    }

    private fun indexable(text: String): ExtractedText =
        ExtractedText(text.take(MAX_INDEX_CHARS), TextIndexState.INDEXED)

    private fun stream(uri: Uri): InputStream = LimitedInputStream(openStream(uri), maxSourceBytes)

    companion object {
        const val MAX_INDEX_CHARS = 1_000_000

        const val MAX_SOURCE_BYTES = 64L * 1024 * 1024

        /** Bytes read for a text format, enough for [MAX_INDEX_CHARS] even at four bytes each. */
        const val MAX_SOURCE_TEXT_BYTES = 8 * 1024 * 1024

        private const val MAX_CACHED_BYTES = 8L * 1024 * 1024

        /** The object and its state beside the characters, so an empty text still costs something. */
        private const val CACHED_OVERHEAD_BYTES = 64L
    }
}

internal class SourceLimitExceeded : IOException("source exceeded the indexing limit")

internal class LimitedInputStream(
    private val source: InputStream,
    private val limit: Long,
) : InputStream() {
    private var remaining = limit

    override fun read(): Int {
        if (remaining == 0L && source.read() >= 0) throw SourceLimitExceeded()
        if (remaining == 0L) return -1
        val value = source.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) {
            if (source.read(bytes, offset, minOf(length, 1)) > 0) throw SourceLimitExceeded()
            return -1
        }
        val allowed = minOf(remaining, length.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val value = source.read(bytes, offset, allowed)
        if (value > 0) remaining -= value
        return value
    }

    override fun skip(count: Long): Long {
        val skipped = source.skip(minOf(count, remaining))
        remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(source.available().toLong(), remaining).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun close() = source.close()
}
