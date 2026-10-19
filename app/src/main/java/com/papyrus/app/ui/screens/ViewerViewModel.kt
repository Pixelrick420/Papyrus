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
import com.papyrus.app.data.AppSettings
import com.papyrus.app.data.DocumentEntity
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.PositionSample
import com.papyrus.app.data.PositionTracker
import com.papyrus.app.data.ReadingLocation
import com.papyrus.app.data.LocationKind
import com.papyrus.app.data.RestoreRequest
import com.papyrus.app.data.SafAccess
import com.papyrus.app.data.shareIntent
import com.papyrus.app.tts.TtsDocumentSource
import com.papyrus.app.tts.TtsPlaybackService
import com.papyrus.app.tts.TtsSession
import com.papyrus.app.tts.TtsUnit
import com.papyrus.app.ui.UiText
import com.papyrus.app.ui.navigation.Routes
import com.papyrus.app.viewer.CodeLanguage
import com.papyrus.app.viewer.CodeLanguages
import com.papyrus.app.viewer.DocTextExtractor
import com.papyrus.app.viewer.DocumentPasswordException
import com.papyrus.app.viewer.OutlineEntry
import com.papyrus.app.viewer.StatisticalCharsetGuesser
import com.papyrus.app.viewer.LexStates
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.NormRect
import com.papyrus.app.viewer.OfficeEncryption
import com.papyrus.app.viewer.OfficeTextExtractor
import com.papyrus.app.viewer.OFFICE_UNLOCK_DIR
import com.papyrus.app.viewer.PDF_UNLOCK_DIR
import com.papyrus.app.viewer.PdfEncryption
import com.papyrus.app.viewer.PdfPageSource
import com.papyrus.app.viewer.PdfPageText
import com.papyrus.app.viewer.PdfPageLink
import com.papyrus.app.viewer.PdfPageTextSource
import com.papyrus.app.viewer.PdfPasswordException
import com.papyrus.app.viewer.PrettyPrint
import com.papyrus.app.viewer.PrettyKind
import com.papyrus.app.viewer.SyntaxHighlighter
import com.papyrus.app.viewer.TextChunks
import com.papyrus.app.viewer.TextEncoding
import com.papyrus.app.viewer.TextEncodings
import com.papyrus.app.viewer.TextSniffer
import com.papyrus.app.viewer.chunkText
import com.papyrus.app.viewer.readTextChunks
import com.papyrus.app.viewer.readTextString
import com.papyrus.app.ui.viewer.clampScale
import com.papyrus.app.ui.viewer.countOccurrences
import com.papyrus.app.ui.viewer.findBlockHits
import com.papyrus.app.ui.viewer.findChunkHits
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.coroutines.cancellation.CancellationException

sealed interface ViewerContent {
    data object Loading : ViewerContent
    data class Pdf(val source: PdfPageSource, val aspectRatios: List<Float>) : ViewerContent
    /** [text] for find and render; [source] lets a table re-snapshot force a fresh render.
     * [encoding] is what the file was decoded as. */
    data class Markdown(val markwon: Markwon, val source: String, val text: Spanned, val encoding: TextEncoding? = null) : ViewerContent

    /** Forty lines to a chunk. [language]/[lexStates] are set only for source files: the states say
     * where each chunk's scan starts, so a block comment crossing a boundary still colours as one. */
    data class PlainText(
        val chunks: List<String>,
        val encoding: TextEncoding? = null,
        val language: CodeLanguage? = null,
        val lexStates: LexStates? = null,
        val lineCount: Int = 0,
        val maxLineColumns: Int = 0,
        val truncated: Boolean = false,
    ) : ViewerContent
    data class Office(val blocks: List<OfficeBlock>) : ViewerContent
    /** A document waiting on its password; [incorrect] flags a rejected attempt, [checking] one in flight. */
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
    /** PDF only, by page index; a wrapping match is several rectangles but one entry, unless past
     * the box budget. */
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
    /** True only for a handed-over document: library rows are already saved, and an encrypted file
     * copies the original. */
    val canSaveToLibrary: Boolean = false,
    val savingToLibrary: Boolean = false,
    val savedToLibrary: Boolean = false,
    /** A charset name picked by hand for a text or Markdown file; null leaves detection in charge. */
    val encodingOverride: String? = null,
    /** Where the viewer should open, from the stored reading position; null when nothing is stored.
     * Set with the document and before its content, so the first layout places it without flashing the top. */
    val restore: RestoreRequest? = null,
    /** A PDF's bookmarks, once read; empty until then and for a document without any. */
    val pdfOutline: List<OutlineEntry> = emptyList(),
)

class ViewerViewModel(
    private val app: MainApplication,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val repository = app.repository
    private val documentId: Long = savedState[Routes.ARG_DOCUMENT_ID] ?: -1L

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    /** Wrap, line numbers and highlighting are read live, so a toggle needs no reload. */
    val settings: StateFlow<AppSettings> = app.settings.settings

    /** Kept in the saved-state handle so a hand-picked encoding survives process death. */
    private var encodingName: String? = savedState[KEY_ENCODING]

    private val _messages = MutableStateFlow<UiText?>(null)
    val messages: StateFlow<UiText?> = _messages.asStateFlow()

    /** Extracted once on first search, reused for every keystroke. */
    private var pdfPageText: List<PdfPageText>? = null

    /** The over-budget file is mentioned once per find session, not once per keystroke. */
    private var findBudgetWarned = false

    /** Cancelled per query; racing searches would let the older overwrite the newer. */
    private var findJob: Job? = null

    /** Cancelled per reload, so a quick encoding pick and a settings toggle leave the later one on screen. */
    private var reloadJob: Job? = null

    /** One page at a time; keyed by URI so a re-grant never reads the dead one. */
    private var pageTextSource: PdfPageTextSource? = null
    private var pageTextSourceUri: String? = null

    /** The decrypted copy of a password-protected document; owned here so close deletes the plaintext. */
    private var unlockedFile: File? = null
    private var unlockedUri: String? = null

    /** Per document: a shared directory would let a viewer delete another's images. */
    private val mediaDir: File by lazy { File(app.cacheDir, "office-media/$documentId") }

    /** When the reading position may be saved. Declared above `init`: `load()` runs inline on the main
     * dispatcher and would otherwise reach it before it exists. */
    private val tracker = PositionTracker(now = { System.nanoTime() / NANOS_PER_MILLI })

    /** The library id the position is stored under: the document's own, or the new row's once saved.
     * 0 while there is none, so nothing is written for a document not in the library. */
    private var persistedId: Long = documentId.coerceAtLeast(0L)

    private var saveJob: Job? = null

    /** One writer, in order, that outlives this ViewModel: the last position is still written after
     * the viewer closes. Conflated, because only the newest position matters. */
    private val outbox = Channel<Pair<Long, PositionSample>>(Channel.CONFLATED)

    init {
        app.applicationScope.launch {
            for ((id, sample) in outbox) {
                try {
                    repository.saveReadingProgress(id, sample)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best effort: a missed save only means the previous position is reopened.
                }
            }
        }
        viewModelScope.launch { load() }
        // A pretty-print toggle re-reads a formattable plain-text document, like an encoding
        // change; the initial value is what the first load already applied, so it is skipped.
        viewModelScope.launch {
            app.settings.settings
                .map { it.prettyPrint }
                .distinctUntilChanged()
                .drop(1)
                .collect { reloadPrettyPrintedContent() }
        }
    }

    private suspend fun load() {
        // A re-load (after a re-grant) points at a new URI, so every URI-keyed cache from the
        // previous load is stale: drop the extracted find text and the decrypted copy.
        pdfPageText = null
        findBudgetWarned = false
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
        val restore = restoreFor(doc)
        tracker.beginLoad(restorePending = restore != null)
        _state.value = ViewerUiState(
            document = doc,
            canSaveToLibrary = doc.id < 0,
            encodingOverride = encodingName,
            restore = restore,
        )
        repository.markOpened(doc.id)
        val content = loadContentSafely(doc)
        _state.update { it.copy(content = content) }
        loadPdfOutline()
        autosaveIfEnabled()
    }

    /**
     * Reads the bookmarks of a PDF that has just loaded. Launched after its first page is already
     * showing, off the main thread, so a large outline never delays the page. The menu offers Outline
     * only once this lands with something in it.
     */
    private fun loadPdfOutline() {
        val shown = (_state.value.content as? ViewerContent.Pdf)?.source ?: return
        // PdfBox parses the whole file into memory, and until the viewer shares one document it would be a
        // second copy next to the renderer's: a very large file gets no outline rather than risk the heap.
        if ((_state.value.document?.sizeBytes ?: 0L) > MAX_OUTLINE_FILE_BYTES) return
        val source = pageSource() ?: return
        viewModelScope.launch {
            val entries = source.outline()?.takeIf { it.isNotEmpty() } ?: return@launch
            // A re-grant may have replaced the document while this was reading: publish only for the one it was read for.
            _state.update { if ((it.content as? ViewerContent.Pdf)?.source === shown) it.copy(pdfOutline = entries) else it }
        }
    }

    /**
     * Where to open [doc]: the position last shown if it has been open (a re-grant reload rebuilds at
     * the top), otherwise the stored one. Nothing when the setting is off.
     */
    private suspend fun restoreFor(doc: DocumentEntity): RestoreRequest? {
        if (!app.settings.current.rememberPosition) return null
        tracker.latest?.let { return RestoreRequest(it.location, clampScale(it.zoom)) }
        val stored = repository.restoreRequest(doc.id, doc.format) ?: return null
        return stored.copy(zoom = clampScale(stored.zoom))
    }

    /**
     * What a freshly composed viewer should open at: the latest position it reported (so a rotation or
     * a reload comes back to the same place at the same zoom), else the stored one.
     */
    fun restoreTarget(): RestoreRequest? =
        tracker.latest?.let { RestoreRequest(it.location, clampScale(it.zoom)) } ?: _state.value.restore

    /** App-scoped read-aloud session: the viewer collects its state and drives the in-flow control bar. */
    val tts: TtsSession = app.ttsSession

    /** The library id the viewer's document is read aloud under; empty until the document is known. */
    fun ttsDocumentId(): String = _state.value.document?.id?.toString().orEmpty()

    /**
     * Read the open document aloud, starting at the current reading position. Every format reuses the
     * text path the viewer already loaded: a PDF reads its pages one at a time (never `allPages()`),
     * plain text and code use their chunks, Markdown its rendered text, Office its blocks. A document
     * with nothing speakable reports the same "no text" message the viewer uses.
     */
    fun startReadAloud() {
        val document = _state.value.document ?: return
        val content = _state.value.content
        viewModelScope.launch {
            val units = when (content) {
                is ViewerContent.Pdf -> pdfTtsUnits(content.source.pageCount)
                is ViewerContent.PlainText -> TtsDocumentSource.unitsFromPlainText(content.chunks)
                is ViewerContent.Markdown -> TtsDocumentSource.unitsFromMarkdown(content.text.toString())
                is ViewerContent.Office -> TtsDocumentSource.unitsFromOffice(content.blocks)
                else -> emptyList()
            }
            if (units.isEmpty()) {
                _messages.value = UiText(R.string.viewer_pdf_no_text)
                return@launch
            }
            val kind = LocationKind.forFormat(document.format) ?: LocationKind.CHUNK
            val location = restoreTarget()?.location?.takeIf { it.kind == kind }
                ?: ReadingLocation(kind, 0)
            tts.prepare(
                documentId = document.id.toString(),
                title = document.title,
                format = document.format,
                units = units,
                startIndex = TtsDocumentSource.startIndexFor(units, location),
                speed = tts.state.value.speed.takeIf { it > 0f } ?: 1.0f,
            )
            TtsPlaybackService.start(app)
        }
    }

    /** Stop reading aloud and clear the session, hiding the control bar. */
    fun stopReadAloud() = tts.stop()

    fun ttsPlayPause() {
        when {
            tts.state.value.isPlaying -> tts.pause()
            // A failure let the service go, so it took the controller with it: nothing is listening
            // for play(), and the queue is gone. Rebuild it from where the reader now is.
            tts.state.value.hasError -> {
                stopReadAloud()
                startReadAloud()
            }
            else -> tts.play()
        }
    }

    fun ttsSkipBack() = tts.skipBack()

    fun ttsSkipForward() = tts.skipForward()

    fun ttsSetSpeed(rate: Float) = tts.setSpeed(rate)

    /** One page at a time, through the same one-page reader find uses; a blank page speaks nothing. */
    private suspend fun pdfTtsUnits(pageCount: Int): List<TtsUnit> {
        val pages = ArrayList<String>(pageCount)
        for (index in 0 until pageCount) {
            pages.add(pageText(index)?.text.orEmpty())
        }
        return TtsDocumentSource.unitsFromPdfPages(pages)
    }

    /** The viewer placed the document at [applied], read back after scrolling; opens the save gate. */
    fun onRestored(applied: PositionSample) = tracker.restoreApplied(applied)

    /** The viewport now shows [sample]; called often, so it only records and schedules. */
    fun onPosition(sample: PositionSample) {
        if (!app.settings.current.rememberPosition) return
        tracker.observe(sample)
        scheduleSave()
    }

    /** Writes the pending position now: the app is going to the background, where it may be killed. */
    fun flushPosition() = commitPosition(force = true)

    /** One coroutine for as long as a save is outstanding, so a stream of position changes costs a
     * field write each, not a coroutine each. Does nothing outside the library. */
    private fun scheduleSave() {
        if (persistedId <= 0 || saveJob?.isActive == true || tracker.nextDelay() == null) return
        saveJob = viewModelScope.launch {
            while (true) {
                val wait = tracker.nextDelay() ?: break
                if (wait > 0) delay(wait) else commitPosition(force = false)
            }
        }
    }

    private fun commitPosition(force: Boolean) {
        val id = persistedId
        if (id <= 0) return // not in the library (yet): the position stays pending
        val sample = tracker.take(force) ?: return
        outbox.trySend(id to sample)
    }

    /** Every failure mode maps to a screen of its own, so the callers only ever see content to show. */
    private suspend fun loadContentSafely(doc: DocumentEntity): ViewerContent = try {
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

    // Saves a handed-over document once it opens, if its setting is on. An encrypted document waits
    // for the unlock, and content that failed to load is never saved.
    private fun autosaveIfEnabled() {
        val current = _state.value
        if (!current.canSaveToLibrary || current.savingToLibrary || current.savedToLibrary) return
        val opened = when (current.content) {
            is ViewerContent.Pdf, is ViewerContent.Markdown, is ViewerContent.PlainText, is ViewerContent.Office -> true
            else -> false
        }
        if (!opened) return
        val settings = app.settings.current
        // A decrypted copy exists exactly when the document was encrypted, with a password or owner-only.
        val wanted = if (unlockedUri != null) settings.autosaveEncrypted else settings.autosaveExternal
        if (wanted) saveToLibrary(notify = false)
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
            DocumentFormat.PDF -> when (repository.pdfEncryption(uri)) {
                PdfEncryption.NOT_ENCRYPTED -> openUnencryptedPdf(uri)
                // Owner-only (permissions) encryption opens on the empty user password, no prompt.
                PdfEncryption.OWNER_ONLY -> unlockPdf(uri, "")
                PdfEncryption.USER_PASSWORD -> ViewerContent.NeedsPassword()
            }
            DocumentFormat.MARKDOWN -> {
                val markwon = Markwon.builder(app)
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(TablePlugin.create(app))
                    .build()
                // Markwon takes the whole text and builds one Spanned for one TextView, so it keeps the small limit.
                val decoded = openStream(uri).use { readTextString(it, MAX_MARKDOWN_BYTES, forcedCharset(), StatisticalCharsetGuesser) }
                val source = if (decoded.truncated) decoded.text + "\n\n" + app.getString(R.string.viewer_truncated) else decoded.text
                ViewerContent.Markdown(markwon, source, markwon.toMarkdown(source), decoded.encoding)
            }
            // CODE renders through the same surface as TEXT: monospace, chunked, selectable.
            DocumentFormat.TEXT, DocumentFormat.CODE -> {
                val prettyKind = if (app.settings.current.prettyPrint) PrettyPrint.kindFor(doc.title) else null
                if (prettyKind == null) {
                    val read = openStream(uri).use { readTextChunks(it, MAX_TEXT_BYTES, forcedCharset(), StatisticalCharsetGuesser) }
                    plainText(doc, read, codeOnly = format == DocumentFormat.CODE)
                } else {
                    val read = openStream(uri).use { readTextString(it, MAX_TEXT_BYTES, forcedCharset(), StatisticalCharsetGuesser) }
                    val formatted = PrettyPrint.format(prettyKind, read.text)
                    // Structural failure: the exact stored text always opens, with one snackbar.
                    if (formatted == null) _messages.value = UiText(R.string.viewer_pretty_failed)
                    val chunks = chunkText(formatted ?: read.text, read.encoding, read.truncated)
                    plainText(doc, chunks, codeOnly = format == DocumentFormat.CODE)
                }
            }
            DocumentFormat.DOCX, DocumentFormat.ODT -> when (OfficeEncryption.status({ openStream(uri) })) {
                OfficeEncryption.Party.NOT_ENCRYPTED -> {
                    val blocks = extractOffice(uri, format)
                    if (blocks.isEmpty()) ViewerContent.Failed(UiText(R.string.viewer_error_empty)) else ViewerContent.Office(blocks)
                }
                OfficeEncryption.Party.PASSWORD -> ViewerContent.NeedsPassword()
                OfficeEncryption.Party.UNSUPPORTED -> ViewerContent.Failed(UiText(R.string.viewer_error_password))
            }
            DocumentFormat.DOC -> when (OfficeEncryption.status({ openStream(uri) })) {
                OfficeEncryption.Party.NOT_ENCRYPTED -> {
                    val blocks = DocTextExtractor.extract({ openStream(uri) })
                    if (blocks.isEmpty()) ViewerContent.Failed(UiText(R.string.viewer_error_empty)) else ViewerContent.Office(blocks)
                }
                else -> ViewerContent.Failed(UiText(R.string.viewer_error_password))
            }
            DocumentFormat.UNKNOWN -> ViewerContent.Failed(UiText(R.string.viewer_error_unsupported))
        }
    }

    private fun extractOffice(uri: Uri, format: DocumentFormat): List<OfficeBlock> =
        OfficeTextExtractor(mediaDir = mediaDir).extract({ openStream(uri) }, format)

    /** Plain text from either read path. By name, and only for source: a .log or .txt that
     * happens to look like code stays plain. */
    private fun plainText(doc: DocumentEntity, read: TextChunks, codeOnly: Boolean): ViewerContent.PlainText {
        val language = CodeLanguages.forFileName(doc.title).takeIf { codeOnly }
        return ViewerContent.PlainText(
            chunks = read.chunks,
            encoding = read.encoding,
            language = language,
            lexStates = language?.let { SyntaxHighlighter.chunkStartStates(it, read.chunks) },
            lineCount = read.lineCount,
            maxLineColumns = read.maxLineColumns,
            truncated = read.truncated,
        )
    }

    /** A wrong [password] returns [ViewerContent.NeedsPassword] for an in-place retry rather than throwing. */
    private suspend fun unlock(doc: DocumentEntity, password: String): ViewerContent =
        when (doc.format) {
            DocumentFormat.DOCX, DocumentFormat.ODT, DocumentFormat.DOC -> unlockOffice(doc.uri.toUri(), doc.format, password)
            else -> unlockPdf(doc.uri.toUri(), password)
        }

    /** Opens a PDF the byte screen found no `/Encrypt` in. If the renderer still wants a password the
     * screen was wrong, so the real parser decides the lock kind and the usual unlock path follows. */
    private suspend fun openUnencryptedPdf(uri: Uri): ViewerContent =
        try {
            val source = PdfPageSource.open(app, uri)
            ViewerContent.Pdf(source, source.loadAspectRatios())
        } catch (e: PdfPasswordException) {
            when (PdfEncryption.authoritativeStatus { openStream(uri) }) {
                PdfEncryption.USER_PASSWORD -> ViewerContent.NeedsPassword()
                PdfEncryption.OWNER_ONLY -> unlockPdf(uri, "")
                PdfEncryption.NOT_ENCRYPTED -> throw e
            }
        }

    private suspend fun unlockPdf(uri: Uri, password: String): ViewerContent {
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

    /** Office decryption writes a plaintext copy, so it is only kept once extraction has succeeded. */
    private suspend fun unlockOffice(uri: Uri, format: DocumentFormat, password: String): ViewerContent {
        val dir = File(app.cacheDir, OFFICE_UNLOCK_DIR).apply { mkdirs() }
        val target = File.createTempFile("unlock-", ".${format.name.lowercase()}", dir)
        val blocks = try {
            OfficeEncryption.decrypt({ openStream(uri) }, password, target)
            extractOffice(target.toUri(), format)
        } catch (expected: DocumentPasswordException) {
            target.delete()
            return ViewerContent.NeedsPassword(incorrect = true)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        if (blocks.isEmpty()) {
            target.delete()
            return ViewerContent.Failed(UiText(R.string.viewer_error_empty))
        }
        unlockedFile = target
        unlockedUri = target.toUri().toString()
        return ViewerContent.Office(blocks)
    }

    private fun sniffsAsText(uri: Uri): Boolean = runCatching {
        openStream(uri).use(TextSniffer::looksLikeText)
    }.getOrDefault(false)

    /** Asked for by the info sheet when it opens, so an uninspected document never pays the provider call. */
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

    /** Copies a handed-over document into the library while the viewer keeps the in-memory entry for the intent.
     *  [notify] is false for the autosave path, which saves silently; the manual "save to library" keeps the snackbar. */
    fun saveToLibrary(notify: Boolean = true) {
        val doc = _state.value.document ?: return
        if (_state.value.savingToLibrary || _state.value.savedToLibrary) return
        _state.update { it.copy(savingToLibrary = true) }
        viewModelScope.launch {
            val savedId = try {
                repository.saveToLibrary(doc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val saved = savedId != null
            if (savedId != null) {
                // From here on the position (and later bookmarks) are kept under the library row, and a
                // position reached before the save is written now.
                persistedId = savedId
                scheduleSave()
            }
            _state.update { it.copy(savingToLibrary = false, savedToLibrary = saved) }
            if (notify) _messages.value = UiText(if (saved) R.string.viewer_save_done else R.string.viewer_save_failed)
        }
    }

    /** The password stays a local: never state, saved state, or a log, and dropped when the attempt ends. */
    fun submitPassword(password: String) {
        val doc = _state.value.document ?: return
        val current = _state.value.content
        if (current !is ViewerContent.NeedsPassword || current.checking) return
        _state.update { it.copy(content = ViewerContent.NeedsPassword(checking = true)) }
        viewModelScope.launch {
            val content = try {
                withContext(Dispatchers.IO) { unlock(doc, password) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ViewerContent.Failed(UiText(R.string.viewer_error_generic, e.localizedMessage ?: e.javaClass.simpleName))
            }
            _state.update { it.copy(content = content) }
            loadPdfOutline()
            autosaveIfEnabled()
        }
    }

    fun cancelPassword() {
        _state.update { it.copy(content = ViewerContent.Failed(UiText(R.string.viewer_password_cancelled))) }
    }

    /** Re-reads a text or Markdown file as [charsetName], or by detection again when null. The old
     * content stays until the new arrives, so the page does not flash and the scroll holds. */
    fun setEncoding(charsetName: String?) {
        val doc = _state.value.document ?: return
        val content = _state.value.content
        if (content !is ViewerContent.PlainText && content !is ViewerContent.Markdown) return
        if (charsetName == encodingName) return
        encodingName = charsetName
        savedState[KEY_ENCODING] = charsetName
        _state.update { it.copy(encodingOverride = charsetName) }
        reloadContent()
    }

    /** The pretty-print toggle changed: re-read a plain-text document the formatter applies to;
     * anything else (a name that never formats, PDF, Office, Markdown) is left as it is. */
    private fun reloadPrettyPrintedContent() {
        val doc = _state.value.document ?: return
        if (_state.value.content !is ViewerContent.PlainText) return
        if (PrettyPrint.kindFor(doc.title) == null) return
        reloadContent()
    }

    /** One reload at a time from the current URI; the old content stays until the new arrives.
     * The text changes under any search in progress, so its hits are recomputed. */
    private fun reloadContent() {
        val doc = _state.value.document ?: return
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            val reloaded = loadContentSafely(doc)
            _state.update { it.copy(content = reloaded) }
            val query = _state.value.find.query
            if (query.isNotBlank()) updateFindQuery(query)
        }
    }

    fun openFind() {
        findBudgetWarned = false
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
            is ViewerContent.PlainText -> {
                // A newer keystroke cancels this job; the scan stops between chunks instead of finishing the file.
                val running = currentCoroutineContext()
                FindResult(hits = findChunkHits(content.chunks, query, MAX_TEXT_FIND_HITS) { !running.isActive })
            }
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
        if (overFindBudget()) return FindResult()
        val pages = pdfPageText ?: extractPageText().also { pdfPageText = it }
        val hits = ArrayList<Int>()
        val pageMatches = HashMap<Int, List<List<NormRect>>>()
        pages.forEachIndexed { index, page ->
            val found = page.matches(query)
            if (found.count == 0) return@forEachIndexed
            repeat(found.count) { hits += index }
            if (found.groups.any { it.isNotEmpty() }) pageMatches[index] = found.groups
        }
        return FindResult(hits = hits, pageMatches = pageMatches)
    }

    /**
     * Past the budget find reports no matches and says why, once per find session: parsing the
     * whole file again to gather them risks the heap, the same tradeoff the outline reader makes.
     */
    private fun overFindBudget(): Boolean {
        if ((_state.value.document?.sizeBytes ?: 0L) <= MAX_PDF_FIND_BYTES) return false
        if (!findBudgetWarned) {
            findBudgetWarned = true
            _messages.value = UiText(R.string.viewer_find_too_large)
        }
        return true
    }

    /**
     * Every page's text on the one document [pageText] and [pageLinks] already share, so a second
     * parse of the same file never sits on the heap beside the first. Empty when the file is
     * unreadable. Rethrows CancellationException; catching it caches empty text and breaks find.
     */
    private suspend fun extractPageText(): List<PdfPageText> = pageSource()?.allPages() ?: emptyList()

    /** One page of text plus boxes; a search read is reused unless it was past the box budget. */
    suspend fun pageText(index: Int): PdfPageText? {
        pdfPageText?.getOrNull(index)?.takeIf { it.hasBoxes || it.text.isBlank() }?.let { return it }
        return pageSource()?.page(index)
    }

    /** One page's links. Shares [pageTextSource] with [pageText], so the document is opened once. */
    suspend fun pageLinks(index: Int): List<PdfPageLink>? = pageSource()?.links(index)

    /** One-page reader for the current bytes, opened once and reused until the file changes. */
    private fun pageSource(): PdfPageTextSource? {
        val uri = contentUri
        if (uri.isEmpty()) return null
        return pageTextSource?.takeIf { pageTextSourceUri == uri }
            ?: PdfPageTextSource(app, uri.toUri()).also {
                pageTextSource?.close()
                pageTextSource = it
                pageTextSourceUri = uri
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

    /** The decrypted copy when one is open, so find and selection read the bytes actually on screen. */
    private val contentUri: String
        get() = unlockedUri ?: documentUri

    private fun openStream(uri: Uri): InputStream =
        app.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())

    /** The charset picked by hand, or null to detect; an unsupported name falls back to detection. */
    private fun forcedCharset(): Charset? = encodingName?.let(TextEncodings::charsetOrNull)

    override fun onCleared() {
        // Leaving the viewer page ends read-aloud: the page is gone, so the voice goes with it. This runs
        // when the nav entry is destroyed (back, or the app finishing), not when the app is merely
        // backgrounded — the entry stays then, and the foreground service keeps playing.
        tts.stopFor(ttsDocumentId())
        // viewModelScope is already cancelled here; the outbox writer is not, and drains this last position.
        commitPosition(force = true)
        outbox.close()
        (_state.value.content as? ViewerContent.Pdf)?.source?.close()
        pageTextSource?.close()
        unlockedFile?.delete()
        // Unconditional: own dir per document, and any format may have written images.
        mediaDir.deleteRecursively()
    }

    private companion object {
        // Plain text streams into 40-line chunks, so memory is about the file's size, not several copies of it.
        const val MAX_TEXT_BYTES = 32 * 1024 * 1024

        // Markdown is one Spanned in one TextView, which is far heavier per byte than a chunk of text.
        const val MAX_MARKDOWN_BYTES = 4 * 1024 * 1024

        // A hit is a boxed index; past this a counter says nothing more and the list only costs heap.
        const val MAX_TEXT_FIND_HITS = 100_000
        const val FIND_DEBOUNCE_MILLIS = 200L
        const val KEY_ENCODING = "encoding"
        const val NANOS_PER_MILLI = 1_000_000L

        /** Past this the whole file is not parsed again just to gather find matches. */
        const val MAX_PDF_FIND_BYTES = 64L * 1024 * 1024

        /** Past this a PDF is not opened a second time just to read its bookmarks. */
        const val MAX_OUTLINE_FILE_BYTES = 64L * 1024 * 1024
    }
}
