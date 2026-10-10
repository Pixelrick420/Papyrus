package com.papyrus.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.Spanned
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import com.papyrus.app.MainApplication
import com.papyrus.app.R
import com.papyrus.app.data.DocumentEntity
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.SafAccess
import com.papyrus.app.data.shareIntent
import com.papyrus.app.ui.UiText
import com.papyrus.app.ui.navigation.Routes
import com.papyrus.app.viewer.DocTextExtractor
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.NormRect
import com.papyrus.app.viewer.OfficeTextExtractor
import com.papyrus.app.viewer.PDF_UNLOCK_DIR
import com.papyrus.app.viewer.PdfEncryption
import com.papyrus.app.viewer.PdfPageSource
import com.papyrus.app.viewer.PdfPageText
import com.papyrus.app.viewer.PdfPageTextSource
import com.papyrus.app.viewer.PdfPasswordException
import com.papyrus.app.viewer.PdfTextExtractor
import com.papyrus.app.viewer.TextSniffer
import com.papyrus.app.viewer.decodeText
import com.papyrus.app.ui.viewer.countOccurrences
import com.papyrus.app.ui.viewer.findBlockHits
import com.papyrus.app.ui.viewer.findChunkHits
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

sealed interface ViewerContent {
    data object Loading : ViewerContent
    data class Pdf(val source: PdfPageSource, val aspectRatios: List<Float>) : ViewerContent
    /** [text] for find and render; [source] lets a table re-snapshot force a fresh render. */
    data class Markdown(val markwon: Markwon, val source: String, val text: Spanned) : ViewerContent
    data class PlainText(val chunks: List<String>) : ViewerContent
    data class Office(val blocks: List<OfficeBlock>) : ViewerContent
    /** A PDF waiting on its password; [incorrect] flags a rejected attempt, [checking] an in-flight one. */
    data class NeedsPassword(val incorrect: Boolean = false, val checking: Boolean = false) : ViewerContent
    data class Failed(val message: UiText) : ViewerContent
}

/** Find-in-file state, owned by the ViewModel so the query survives a configuration change. */
data class FindState(
    val open: Boolean = false,
    val query: String = "",
    /** One entry per match in document order; a page with three matches appears three times. */
    val hits: List<Int> = emptyList(),
    val position: Int = -1,
    /** Markdown is one `Spanned`, so matches highlight with no target to step between. */
    val occurrences: Int = 0,
    /** PDF only, by page index; a wrapping match is several rectangles but one entry, unless past the box budget. */
    val pageMatches: Map<Int, List<List<NormRect>>> = emptyMap(),
) {
    val count: Int get() = if (hits.isNotEmpty()) hits.size else occurrences

    val navigable: Boolean get() = hits.isNotEmpty()

    /** Derived, so the hit list and the highlight position cannot disagree. */
    val activeIndex: Int? get() = if (position in hits.indices) hits[position] else null

    /** The match inside the current container: the cursor minus the container's first entry. */
    val activeOccurrence: Int? get() {
        val container = activeIndex ?: return null
        return position - hits.firstIndexOf(container)
    }

    /** Applies the query synchronously; delaying it reverts the typed character. */
    fun withQueryEchoed(query: String): FindState = copy(
        query = query,
        position = if (query == this.query) position else -1,
    )

    /** Ignored unless it still describes the query in the box. */
    fun withSearched(
        query: String,
        hits: List<Int>,
        occurrences: Int,
        pageMatches: Map<Int, List<List<NormRect>>> = emptyMap(),
    ): FindState {
        if (this.query != query) return this
        return copy(
            hits = hits,
            occurrences = occurrences,
            pageMatches = pageMatches,
            // Reset to the first hit; the old index can point past a shorter list.
            position = if (hits.isEmpty()) -1 else 0,
        )
    }
}

/** Index of the first element equal to [value] in an ascending list: a lower-bound binary search. */
private fun List<Int>.firstIndexOf(value: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid] < value) low = mid + 1 else high = mid
    }
    return low
}

data class ViewerUiState(
    val document: DocumentEntity? = null,
    val content: ViewerContent = ViewerContent.Loading,
    val find: FindState = FindState(),
    /** Raised instead of an error, so the content on screen survives the lost grant. */
    val needsRegrant: Boolean = false,
    /** True only for a handed-over document: library rows are already saved. */
    val canSaveToLibrary: Boolean = false,
    val savingToLibrary: Boolean = false,
    val savedToLibrary: Boolean = false,
)

class ViewerViewModel(
    private val app: MainApplication,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val repository = app.repository
    private val documentId: Long = savedState[Routes.ARG_DOCUMENT_ID] ?: -1L

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<UiText?>(null)
    val messages: StateFlow<UiText?> = _messages.asStateFlow()

    /** Extracted once on first search, reused for every keystroke. */
    private var pdfPageText: List<PdfPageText>? = null

    /** Cancelled per query; racing searches would let the older overwrite the newer. */
    private var findJob: Job? = null

    /** One page at a time; keyed by URI so a re-grant never reads the dead one. */
    private var pageTextSource: PdfPageTextSource? = null
    private var pageTextSourceUri: String? = null

    /** The decrypted copy of a password-protected PDF; owned here so close deletes the plaintext. */
    private var unlockedFile: File? = null
    private var unlockedUri: String? = null

    /** Per document: a shared directory would let a viewer delete another's images. */
    private val mediaDir: File by lazy { File(app.cacheDir, "office-media/$documentId") }

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        // A re-load (after a re-grant) points at a new URI, so every URI-keyed cache from the
        // previous load is stale: drop the extracted find text and the decrypted copy.
        pdfPageText = null
        pageTextSource?.close()
        pageTextSource = null
        pageTextSourceUri = null
        unlockedFile?.delete()
        unlockedFile = null
        unlockedUri = null
        val doc = repository.getById(documentId)
        if (doc == null) {
            _state.value = ViewerUiState(content = ViewerContent.Failed(UiText(R.string.viewer_error_not_found)))
            return
        }
        _state.value = ViewerUiState(document = doc, canSaveToLibrary = doc.id < 0)
        repository.markOpened(doc.id)
        val content = try {
            withContext(Dispatchers.IO) { loadContent(doc) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfPasswordException) {
            ViewerContent.Failed(UiText(R.string.viewer_error_password))
        } catch (e: SecurityException) {
            // Dropped SAF grant: offer a re-pick rather than only reporting the failure.
            _state.update { it.copy(needsRegrant = true) }
            ViewerContent.Failed(UiText(R.string.viewer_error_access))
        } catch (e: FileNotFoundException) {
            ViewerContent.Failed(UiText(R.string.viewer_error_missing))
        } catch (e: Exception) {
            ViewerContent.Failed(UiText(R.string.viewer_error_generic, e.localizedMessage ?: e.javaClass.simpleName))
        }
        val encrypted = content is ViewerContent.NeedsPassword || unlockedUri != null
        _state.update { it.copy(content = content, canSaveToLibrary = it.canSaveToLibrary && !encrypted) }
    }

    private suspend fun loadContent(doc: DocumentEntity): ViewerContent {
        val uri = doc.uri.toUri()
        // Rows indexed before format sniffing may say UNKNOWN; a re-check beats a backfill.
        val format = if (doc.format == DocumentFormat.UNKNOWN && sniffsAsText(uri)) {
            repository.updateFormat(doc.id, DocumentFormat.CODE)
            DocumentFormat.CODE
        } else {
            doc.format
        }

        return when (format) {
            DocumentFormat.PDF -> when (PdfEncryption.status { openStream(uri) }) {
                PdfEncryption.NOT_ENCRYPTED -> {
                    val source = PdfPageSource.open(app, uri)
                    ViewerContent.Pdf(source, source.loadAspectRatios())
                }
                // Owner-only (permissions) encryption opens on the empty user password, no prompt.
                PdfEncryption.OWNER_ONLY -> unlock(uri, "")
                PdfEncryption.USER_PASSWORD -> ViewerContent.NeedsPassword()
            }
            DocumentFormat.MARKDOWN -> {
                val markwon = Markwon.builder(app)
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TablePlugin.create(app))
                    .build()
                val source = readText(uri)
                ViewerContent.Markdown(markwon, source, markwon.toMarkdown(source))
            }
            // CODE renders through the same surface as TEXT: monospace, chunked, selectable.
            DocumentFormat.TEXT, DocumentFormat.CODE ->
                ViewerContent.PlainText(readText(uri).lineSequence().chunked(LINES_PER_CHUNK).map { it.joinToString("\n") }.toList())
            DocumentFormat.DOCX, DocumentFormat.ODT -> {
                val extractor = OfficeTextExtractor(mediaDir = mediaDir)
                val blocks = extractor.extract({ openStream(uri) }, format)
                if (blocks.isEmpty()) ViewerContent.Failed(UiText(R.string.viewer_error_empty)) else ViewerContent.Office(blocks)
            }
            DocumentFormat.DOC -> {
                val blocks = DocTextExtractor.extract { openStream(uri) }
                if (blocks.isEmpty()) ViewerContent.Failed(UiText(R.string.viewer_error_empty)) else ViewerContent.Office(blocks)
            }
            DocumentFormat.UNKNOWN -> ViewerContent.Failed(UiText(R.string.viewer_error_unsupported))
        }
    }

    /** A wrong [password] returns [ViewerContent.NeedsPassword] for an in-place retry rather than throwing. */
    private suspend fun unlock(uri: Uri, password: String): ViewerContent {
        val dir = File(app.cacheDir, PDF_UNLOCK_DIR).apply { mkdirs() }
        val target = File.createTempFile("unlock-", ".pdf", dir)
        try {
            PdfEncryption.decrypt({ openStream(uri) }, password, target)
            val source = PdfPageSource.openFile(target)
            unlockedFile = target
            unlockedUri = target.toUri().toString()
            return ViewerContent.Pdf(source, source.loadAspectRatios())
        } catch (e: InvalidPasswordException) {
            target.delete()
            return ViewerContent.NeedsPassword(incorrect = true)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private fun sniffsAsText(uri: Uri): Boolean = runCatching {
        openStream(uri).use(TextSniffer::looksLikeText)
    }.getOrDefault(false)

    /** Asked for by the info sheet when it opens, so a document nobody inspects never pays the provider call. */
    suspend fun mimeType(document: DocumentEntity): String? = repository.mimeTypeOf(document)

    /** Probed first: a chooser opened over a dead grant works, then fails inside the chosen app. */
    fun share(onShare: (Intent) -> Unit) {
        val doc = _state.value.document ?: return
        viewModelScope.launch {
            when (repository.probeShare(doc)) {
                SafAccess.Readable ->
                    onShare(shareIntent(repository.resolveShareUri(doc.uri.toUri()), repository.shareTypeFor(doc)))
                SafAccess.NoAccess -> _messages.value = UiText(R.string.home_share_no_access)
                SafAccess.Missing -> _messages.value = UiText(R.string.home_share_missing)
                SafAccess.Unreadable -> _messages.value = UiText(R.string.home_share_failed)
            }
        }
    }

    /**
     * Copies a handed-over document into the library. The viewer stays on the in-memory entry so
     * the intent keeps finishing normally while the new row surfaces on Home.
     */
    fun saveToLibrary() {
        val doc = _state.value.document ?: return
        if (_state.value.savingToLibrary || _state.value.savedToLibrary) return
        _state.update { it.copy(savingToLibrary = true) }
        viewModelScope.launch {
            val saved = try {
                repository.saveToLibrary(doc)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _state.update { it.copy(savingToLibrary = false, savedToLibrary = saved) }
            _messages.value = UiText(if (saved) R.string.viewer_save_done else R.string.viewer_save_failed)
        }
    }

    /** The password stays a local: never state, the saved-state handle, or a log, and dropped when the attempt ends. */
    fun submitPassword(password: String) {
        val doc = _state.value.document ?: return
        val current = _state.value.content
        if (current !is ViewerContent.NeedsPassword || current.checking) return
        _state.update { it.copy(content = ViewerContent.NeedsPassword(checking = true)) }
        viewModelScope.launch {
            val content = try {
                withContext(Dispatchers.IO) { unlock(doc.uri.toUri(), password) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ViewerContent.Failed(UiText(R.string.viewer_error_generic, e.localizedMessage ?: e.javaClass.simpleName))
            }
            _state.update { it.copy(content = content, canSaveToLibrary = false) }
        }
    }

    fun cancelPassword() {
        _state.update { it.copy(content = ViewerContent.Failed(UiText(R.string.viewer_password_cancelled))) }
    }

    fun openFind() {
        _state.update { it.copy(find = it.find.copy(open = true)) }
    }

    fun closeFind() {
        _state.update { it.copy(find = FindState()) }
    }

    /** Query is stored synchronously; debounce the search, never the echo. */
    fun updateFindQuery(query: String) {
        _state.update { it.copy(find = it.find.withQueryEchoed(query)) }
        findJob?.cancel()
        findJob = viewModelScope.launch {
            delay(FIND_DEBOUNCE_MILLIS)
            val result = withContext(Dispatchers.IO) { computeHits(query) }
            _state.update {
                it.copy(find = it.find.withSearched(query, result.hits, result.occurrences, result.pageMatches))
            }
        }
    }

    fun nextMatch() = stepMatch(1)

    fun previousMatch() = stepMatch(-1)

    private fun stepMatch(delta: Int) {
        _state.update { current ->
            val find = current.find
            if (find.hits.isEmpty()) return@update current
            val next = ((find.position + delta) % find.hits.size + find.hits.size) % find.hits.size
            current.copy(find = find.copy(position = next))
        }
    }

    private data class FindResult(
        val hits: List<Int> = emptyList(),
        val occurrences: Int = 0,
        val pageMatches: Map<Int, List<List<NormRect>>> = emptyMap(),
    )

    private suspend fun computeHits(query: String): FindResult {
        if (query.isBlank()) return FindResult()
        return when (val content = _state.value.content) {
            is ViewerContent.PlainText -> FindResult(hits = findChunkHits(content.chunks, query))
            is ViewerContent.Office -> FindResult(hits = findBlockHits(content.blocks, query))
            // One Spanned, every match lit at once: no target to step, count must be right.
            is ViewerContent.Markdown ->
                FindResult(occurrences = countOccurrences(content.text.toString(), query))
            is ViewerContent.Pdf -> findPdfPages(query)
            else -> FindResult()
        }
    }

    /** A page repeats in hits per match; budget-exceeding pages still count, scans have no text. */
    private suspend fun findPdfPages(query: String): FindResult {
        val pages = pdfPageText ?: extractPageText().also { pdfPageText = it }
        val hits = ArrayList<Int>()
        val pageMatches = HashMap<Int, List<List<NormRect>>>()
        pages.forEachIndexed { index, page ->
            val count = page.countMatches(query)
            if (count == 0) return@forEachIndexed
            repeat(count) { hits += index }
            val groups = page.matchGroups(query)
            if (groups.any { it.isNotEmpty() }) pageMatches[index] = groups
        }
        return FindResult(hits = hits, pageMatches = pageMatches)
    }

    /** Rethrows CancellationException; catching it caches empty text and breaks find. */
    private fun extractPageText(): List<PdfPageText> {
        PDFBoxResourceLoader.init(app)
        val input = app.contentResolver.openInputStream(contentUri.toUri())
            ?: return emptyList()
        return try {
            input.use { stream ->
                PDDocument.load(stream).use { document -> PdfTextExtractor.extract(document) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** One page of text plus boxes; a search read is reused unless it was past the box budget. */
    suspend fun pageText(index: Int): PdfPageText? {
        pdfPageText?.getOrNull(index)?.takeIf { it.hasBoxes || it.text.isBlank() }?.let { return it }
        val uri = contentUri
        if (uri.isEmpty()) return null
        val source = pageTextSource?.takeIf { pageTextSourceUri == uri }
            ?: PdfPageTextSource(app, uri.toUri()).also {
                pageTextSource?.close()
                pageTextSource = it
                pageTextSourceUri = uri
            }
        return source.page(index)
    }

    /** [picked] is checked against the title, so a wrong pick cannot re-point the row. */
    fun regrant(picked: Uri) {
        val doc = _state.value.document ?: return
        viewModelScope.launch {
            val ok = try {
                repository.regrant(doc, picked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (ok) {
                _state.update { it.copy(needsRegrant = false) }
                // Old URI is dead, so the loaded content is stale.
                _state.update { it.copy(content = ViewerContent.Loading) }
                load()
            } else {
                _messages.value = UiText(R.string.viewer_regrant_mismatch)
            }
        }
    }

    fun consumeRegrantRequest() {
        _state.update { it.copy(needsRegrant = false) }
    }

    fun consumeMessage() {
        _messages.value = null
    }

    private val documentUri: String
        get() = _state.value.document?.uri.orEmpty()

    /** The decrypted copy when one is open, so find and selection read the bytes actually on screen. */
    private val contentUri: String
        get() = unlockedUri ?: documentUri

    private fun openStream(uri: Uri): InputStream =
        app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())

    /** Reads at most [MAX_TEXT_BYTES] so a huge log file can't exhaust the heap. */
    private fun readText(uri: Uri): String = openStream(uri).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < MAX_TEXT_BYTES) {
            val n = input.read(buffer, 0, minOf(buffer.size, MAX_TEXT_BYTES - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        val truncated = total >= MAX_TEXT_BYTES && input.read() != -1
        decodeText(out.toByteArray()) + if (truncated) "\n\n" + app.getString(R.string.viewer_truncated) else ""
    }

    override fun onCleared() {
        (_state.value.content as? ViewerContent.Pdf)?.source?.close()
        pageTextSource?.close()
        unlockedFile?.delete()
        // Unconditional: own dir per document, and any format may have written images.
        mediaDir.deleteRecursively()
    }

    private companion object {
        const val MAX_TEXT_BYTES = 4 * 1024 * 1024
        const val LINES_PER_CHUNK = 40
        const val FIND_DEBOUNCE_MILLIS = 200L
    }
}
