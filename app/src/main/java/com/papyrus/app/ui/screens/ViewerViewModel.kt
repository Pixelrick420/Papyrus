package com.papyrus.app.ui.screens

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
import com.papyrus.app.ui.UiText
import com.papyrus.app.ui.navigation.Routes
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.NormRect
import com.papyrus.app.viewer.OfficeTextExtractor
import com.papyrus.app.viewer.PdfPageSource
import com.papyrus.app.viewer.PdfPageText
import com.papyrus.app.viewer.PdfPasswordException
import com.papyrus.app.viewer.PdfTextExtractor
import com.papyrus.app.viewer.TextSniffer
import com.papyrus.app.viewer.decodeText
import com.papyrus.app.ui.viewer.countOccurrences
import com.papyrus.app.ui.viewer.findBlockHits
import com.papyrus.app.ui.viewer.findChunkHits
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
    /**
     * [text] is the parse used for find counting; [source] is kept so the viewer can render a fresh
     * `Spanned` when a table re-snapshots the TextView's paint (see `MarkdownViewer`).
     */
    data class Markdown(val markwon: Markwon, val source: String, val text: Spanned) : ViewerContent
    data class PlainText(val chunks: List<String>) : ViewerContent
    data class Office(val blocks: List<OfficeBlock>) : ViewerContent
    data class Failed(val message: UiText) : ViewerContent
}

/** Find-in-file state, owned by the ViewModel so the query survives a configuration change. */
data class FindState(
    val open: Boolean = false,
    val query: String = "",
    /**
     * One entry per match, in document order, holding the index of the chunk, block or page it is
     * in. A container with three matches appears three times, so the find bar steps through this
     * list and [count] is the true number of matches.
     */
    val hits: List<Int> = emptyList(),
    val position: Int = -1,
    /** Markdown is one `Spanned`, so matches highlight with no target to step between. */
    val occurrences: Int = 0,
    /**
     * PDF only: by page index, one list of rectangles per match on that page, in match order. A
     * match wrapping lines is several rectangles but still one entry. A hit page can lack an entry
     * past the extractor's budget.
     */
    val pageMatches: Map<Int, List<List<NormRect>>> = emptyMap(),
) {
    /** What the counter shows: navigable targets if there are any, otherwise raw occurrences. */
    val count: Int get() = if (hits.isNotEmpty()) hits.size else occurrences

    val navigable: Boolean get() = hits.isNotEmpty()

    /** Derived, so the hit list and the highlight position cannot disagree. */
    val activeIndex: Int? get() = if (position in hits.indices) hits[position] else null

    /**
     * Which match inside [activeIndex]'s container is current: 0 for the first, 1 for the second.
     * Derived from [position] because [hits] is sorted and a container's matches are contiguous, so
     * it is that container's first entry subtracted from the cursor.
     */
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

    /** Per document: a shared directory would let a viewer delete another's images. */
    private val mediaDir: File by lazy { File(app.cacheDir, "office-media/$documentId") }

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val doc = repository.getById(documentId)
        if (doc == null) {
            _state.value = ViewerUiState(content = ViewerContent.Failed(UiText(R.string.viewer_error_not_found)))
            return
        }
        _state.value = ViewerUiState(document = doc)
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
        _state.update { it.copy(content = content) }
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
            DocumentFormat.PDF -> {
                val source = PdfPageSource.open(app, uri)
                if (source.pageCount != doc.pageCount) repository.updatePageCount(doc.id, source.pageCount)
                ViewerContent.Pdf(source, source.loadAspectRatios())
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
            DocumentFormat.UNKNOWN -> ViewerContent.Failed(UiText(R.string.viewer_error_unsupported))
        }
    }

    private fun sniffsAsText(uri: Uri): Boolean = runCatching {
        openStream(uri).use(TextSniffer::looksLikeText)
    }.getOrDefault(false)

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

    /**
     * A page is repeated in the hits once per match on it, so counting and stepping are per match.
     * Counting needs no rectangles, so a page past the extractor's box budget still counts every
     * match; it just has nothing to draw. Scans have no text layer.
     */
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
        val input = app.contentResolver.openInputStream(documentUri.toUri())
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
        // Unconditional: own dir per document, and any format may have written images.
        mediaDir.deleteRecursively()
    }

    private companion object {
        const val MAX_TEXT_BYTES = 4 * 1024 * 1024
        const val LINES_PER_CHUNK = 40
        const val FIND_DEBOUNCE_MILLIS = 200L
    }
}
