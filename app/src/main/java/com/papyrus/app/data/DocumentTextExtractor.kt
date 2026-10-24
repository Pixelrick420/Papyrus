package com.papyrus.app.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.papyrus.app.viewer.DocTextExtractor
import com.papyrus.app.viewer.EpubExtractor
import com.papyrus.app.viewer.EpubProtectedException
import com.papyrus.app.viewer.OfficeEncryption
import com.papyrus.app.viewer.OfficeTextExtractor
import com.papyrus.app.viewer.PdfEncryption
import com.papyrus.app.viewer.PdfPageTextSource
import com.papyrus.app.viewer.readTextString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** What one extraction produced: the text to index, or the reason there is none. */
data class ExtractedText(
    val text: String,
    val state: TextIndexState,
)

/**
 * Extracts a document's searchable text with the readers the viewer already uses, so the index and
 * the screen see the same words.
 *
 * The stored text is the same text find-in-file scans, per format: PDF page texts in order joined by
 * [PdfPageTextSource.PAGE_SEPARATOR], Office/EPUB blocks through [officeBlocksToText], plain text
 * decoded from the source. That is a contract, not an accident: a future open-at-the-match action
 * maps a character offset from the index back to a page or block by re-running the same extraction,
 * so no offset map needs to be stored.
 *
 * Every failure is terminal, never thrown: the indexer's queue must always drain. Encryption is
 * checked before parsing, so a protected document is [TextIndexState.SKIPPED] and is not retried.
 */
class DocumentTextExtractor(
    private val context: Context,
    private val openStream: (Uri) -> InputStream,
) {

    suspend fun extract(document: DocumentEntity): ExtractedText = withContext(Dispatchers.IO) {
        // Checked before any open: a 2 GB scan should not be read just to be told it is too large.
        if (document.sizeBytes > MAX_SOURCE_BYTES) {
            return@withContext ExtractedText("", TextIndexState.TOO_LARGE)
        }
        try {
            read(document) ?: ExtractedText("", TextIndexState.SKIPPED)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expectedDrm: EpubProtectedException) {
            ExtractedText("", TextIndexState.SKIPPED)
        } catch (ignored: Exception) {
            // Malformed file, provider died, a parser that gave up: terminal, so the queue moves on.
            // An Error (an actual OutOfMemoryError) is deliberately not caught: it says nothing
            // about this document and the process state is no longer trustworthy.
            ExtractedText("", TextIndexState.FAILED)
        }
    }

    /** Null when the format has nothing to index at all. */
    private suspend fun read(document: DocumentEntity): ExtractedText? = when (document.format) {
        DocumentFormat.PDF -> readPdf(document.uri.toUri())
        DocumentFormat.DOCX, DocumentFormat.ODT -> readOffice(document)
        DocumentFormat.DOC -> readDoc(document.uri.toUri())
        DocumentFormat.EPUB -> readEpub(document.uri.toUri())
        DocumentFormat.TEXT, DocumentFormat.CODE, DocumentFormat.MARKDOWN, DocumentFormat.CSV ->
            readPlainText(document.uri.toUri())
        DocumentFormat.UNKNOWN -> null
    }

    private suspend fun readPdf(uri: Uri): ExtractedText {
        if (PdfEncryption.status { openStream(uri) } != PdfEncryption.NOT_ENCRYPTED) {
            return ExtractedText("", TextIndexState.SKIPPED)
        }
        val text = PdfPageTextSource(context, uri).use { source -> source.allText(MAX_INDEX_CHARS) }
            ?: return ExtractedText("", TextIndexState.FAILED)
        return indexable(text)
    }

    private fun readOffice(document: DocumentEntity): ExtractedText {
        val uri = document.uri.toUri()
        if (OfficeEncryption.isEncrypted({ openStream(uri) })) {
            return ExtractedText("", TextIndexState.SKIPPED)
        }
        // mediaDir = null, as in ThumbnailLoader: indexing needs the words, not the pictures.
        val blocks = OfficeTextExtractor(mediaDir = null).extract({ openStream(uri) }, document.format)
        return indexable(officeBlocksToText(blocks, MAX_INDEX_CHARS))
    }

    private fun readDoc(uri: Uri): ExtractedText {
        if (OfficeEncryption.isEncrypted({ openStream(uri) })) {
            return ExtractedText("", TextIndexState.SKIPPED)
        }
        return indexable(officeBlocksToText(DocTextExtractor.extract({ openStream(uri) }), MAX_INDEX_CHARS))
    }

    private fun readEpub(uri: Uri): ExtractedText {
        val book = EpubExtractor(mediaDir = null).extract({ openStream(uri) })
        return indexable(officeBlocksToText(book.blocks, MAX_INDEX_CHARS))
    }

    private fun readPlainText(uri: Uri): ExtractedText {
        val decoded = openStream(uri).use { readTextString(it, MAX_SOURCE_TEXT_BYTES) }
        return indexable(decoded.text)
    }

    private fun indexable(text: String): ExtractedText =
        ExtractedText(text.take(MAX_INDEX_CHARS), TextIndexState.INDEXED)

    companion object {
        /** Characters stored per document, the agreed cap; longer text is cut, not refused. */
        const val MAX_INDEX_CHARS = 1_000_000

        /** A document above this size is [TextIndexState.TOO_LARGE] without being opened. */
        const val MAX_SOURCE_BYTES = 64L * 1024 * 1024

        /** Bytes read for a text format, enough for [MAX_INDEX_CHARS] even at four bytes each. */
        const val MAX_SOURCE_TEXT_BYTES = 8 * 1024 * 1024
    }
}
