package io.github.pixelrick420.papyrus.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pixelrick420.papyrus.R
import io.github.pixelrick420.papyrus.data.ContentHit
import io.github.pixelrick420.papyrus.data.DocumentEntity
import io.github.pixelrick420.papyrus.data.DocumentFormat
import io.github.pixelrick420.papyrus.data.DocumentRepository
import io.github.pixelrick420.papyrus.data.FormatGroup
import io.github.pixelrick420.papyrus.data.LibraryFilter
import io.github.pixelrick420.papyrus.data.LibrarySort
import io.github.pixelrick420.papyrus.data.OpenPersistableDocuments
import io.github.pixelrick420.papyrus.data.SafAccess
import io.github.pixelrick420.papyrus.data.SearchQuery
import io.github.pixelrick420.papyrus.data.SearchRepository
import io.github.pixelrick420.papyrus.data.SettingsRepository
import io.github.pixelrick420.papyrus.data.ThumbnailLoader
import io.github.pixelrick420.papyrus.data.availableGroups
import io.github.pixelrick420.papyrus.data.libraryView
import io.github.pixelrick420.papyrus.data.shareIntent
import io.github.pixelrick420.papyrus.data.shareIntentMultiple
import io.github.pixelrick420.papyrus.data.shareMimeType
import io.github.pixelrick420.papyrus.data.sharedMimeType
import io.github.pixelrick420.papyrus.ui.AppViewModelProvider
import io.github.pixelrick420.papyrus.ui.UiText
import io.github.pixelrick420.papyrus.ui.asString
import io.github.pixelrick420.papyrus.ui.components.ControlCorner
import io.github.pixelrick420.papyrus.ui.components.ControlHeight
import io.github.pixelrick420.papyrus.ui.components.DocumentThumbnail
import io.github.pixelrick420.papyrus.ui.viewer.ActiveMatchColor
import io.github.pixelrick420.papyrus.ui.viewer.findMatchRanges
import io.github.pixelrick420.papyrus.ui.viewer.focusTermFor
import io.github.pixelrick420.papyrus.ui.components.SearchPill
import io.github.pixelrick420.papyrus.ui.components.SnippetText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HomeUiState(
    val loaded: Boolean = false,
    val libraryEmpty: Boolean = false,
    /** Trimmed query [results] was filtered with, so the "no matches" text never leads the list. */
    val query: String = "",
    /** The unfiltered library, so a bulk action resolves ids the current query may be hiding. */
    val documents: List<DocumentEntity> = emptyList(),
    val results: List<DocumentEntity> = emptyList(),
    val selection: SelectionState = SelectionState(),
    val pendingRemovalToken: Long? = null,
    val filter: LibraryFilter = LibraryFilter(),
    val sort: LibrarySort = LibrarySort.LAST_OPENED,
    /** The file-type groups the sheet lists: what the library has plus what is selected, so a selected group can always be unselected. */
    val groups: List<FormatGroup> = emptyList(),
    val hasStarred: Boolean = false,
    val contentHits: Map<Long, ContentHit> = emptyMap(),
    val searching: Boolean = false,
    val bulkProgress: BulkProgress? = null,
)

// The query travels with the hits so a keystroke can never leave the previous query's excerpts on screen under the new one.
internal data class ContentSearch(val query: String, val hits: Map<Long, ContentHit>?)

private data class AsyncState(
    val pendingRemovalToken: Long?,
    val content: ContentSearch?,
    val bulkProgress: BulkProgress?,
)

internal fun buildHomeUiState(
    all: List<DocumentEntity>,
    params: ViewParams,
    selection: SelectionState,
    pendingRemovalToken: Long?,
    hidden: Set<Long>,
    contentHits: Map<Long, ContentHit> = emptyMap(),
    searching: Boolean = false,
    bulkProgress: BulkProgress? = null,
): HomeUiState {
    val needle = params.query.trim()
    // Hiding first keeps `libraryEmpty` and the results agreeing with what the screen actually shows.
    val documents = if (hidden.isEmpty()) all else all.filterNot { it.id in hidden }
    return HomeUiState(
        loaded = true,
        libraryEmpty = documents.isEmpty(),
        query = needle,
        documents = documents,
        results = libraryView(documents, needle, params.filter, params.sort, alsoMatching = contentHits.keys),
        selection = selection,
        pendingRemovalToken = pendingRemovalToken,
        filter = params.filter,
        sort = params.sort,
        groups = (availableGroups(documents) + params.filter.groups).distinct().sortedBy { it.ordinal },
        hasStarred = documents.any { it.isStarred },
        contentHits = contentHits,
        searching = searching,
        bulkProgress = bulkProgress,
    )
}

private const val SHARE_PROBE_PARALLELISM = 4

private const val CONTENT_SEARCH_DEBOUNCE_MILLIS = 250L

// Each document is probed once; only a readable one is also asked its type. [onProbed] fires once per document, readable or not, for the progress count.
internal suspend fun <T> probeShareable(
    selected: List<T>,
    limit: Int = SHARE_PROBE_PARALLELISM,
    probe: suspend (T) -> SafAccess,
    type: suspend (T) -> String?,
    onProbed: () -> Unit = {},
): List<Pair<T, String?>> = coroutineScope {
    val gate = Semaphore(limit)
    selected.map { document ->
        async(Dispatchers.IO) {
            gate.withPermit {
                val probed = if (probe(document) == SafAccess.Readable) document to type(document) else null
                onProbed()
                probed
            }
        }
    }.awaitAll().filterNotNull()
}

internal data class ViewParams(val query: String, val filter: LibraryFilter, val sort: LibrarySort)

class HomeViewModel(
    private val repository: DocumentRepository,
    private val search: SearchRepository,
    val thumbnailLoader: ThumbnailLoader,
    // Outlives this ViewModel, so a removal still in its undo window when Home is cleared commits here.
    private val removalScope: CoroutineScope,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val contentSearch = MutableStateFlow<ContentSearch?>(null)

    private var contentSearchJob: Job? = null

    /** Kept here, so it survives a rotation; not stored, a new session starts unfiltered. */
    private val _filter = MutableStateFlow(LibraryFilter())

    private val viewParams = combine(
        _query,
        _filter,
        settings.settings.map { it.librarySort }.distinctUntilChanged(),
    ) { query, filter, sort -> ViewParams(query.trim(), filter, sort) }

    private val _selection = MutableStateFlow(SelectionState())

    private val _bulkProgress = MutableStateFlow<BulkProgress?>(null)

    /** The multi-file Remove or Share running now, so a second tap cannot start another over it. */
    private var bulkJob: Job? = null

    private val pendingRemoval = PendingRemoval<List<DocumentEntity>>(
        timer = { delayMillis, action ->
            val job = viewModelScope.launch {
                delay(delayMillis)
                action()
            }
            PendingRemoval.Cancellation { job.cancel() }
        },
        windowMillis = UNDO_WINDOW_MILLIS,
        commit = ::commitRemoval,
    )

    // An id leaves only on Undo, a failed commit, or once the list drops the row, so the database's emission never shows it.
    private val hiddenIds = MutableStateFlow<Set<Long>>(emptySet())

    init {
        // A hidden id is dropped only after the list it filters has stopped containing it. Read here,
        // beside the transform rather than inside it, so filtering stays a pure function of its inputs.
        viewModelScope.launch {
            repository.documents.collect { all ->
                val present = all.mapTo(HashSet()) { it.id }
                hiddenIds.update { it - present }
            }
        }
    }

    // Gathered into one so the typed five-argument [combine] below still fits.
    private val asyncState = combine(pendingRemoval.pending, contentSearch, _bulkProgress) { pending, content, bulk ->
        AsyncState(pending?.token, content, bulk)
    }

    // Starts "not loaded" so a populated library cannot flash its empty state; hidden rows are left out of documents and libraryEmpty so bulk actions agree with the screen.
    // Runs on [Dispatchers.Default] so typing never waits; a content answer counts only while its query is still the one typed, so a slower search for a shorter query adds no rows.
    val uiState: StateFlow<HomeUiState> = combine(
        repository.documents, viewParams, _selection, hiddenIds, asyncState,
    ) { all, params, selection, hidden, async ->
        val answer = async.content?.takeIf { it.query == params.query }
        buildHomeUiState(
            all = all,
            params = params,
            selection = selection,
            pendingRemovalToken = async.pendingRemovalToken,
            hidden = hidden,
            contentHits = answer?.hits.orEmpty(),
            searching = params.query.isNotEmpty() && answer?.hits == null,
            bulkProgress = async.bulkProgress,
        )
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    // The content search is debounced and cancelled by the next keystroke because it reads the database.
    fun onQueryChange(value: String) {
        _query.value = value
        searchContents(value.trim())
    }

    // A failed search reports once, so a broken index is not mistaken for a library that lacks the words.
    private fun searchContents(needle: String) {
        contentSearchJob?.cancel()
        if (SearchQuery.parse(needle) == null) {
            contentSearch.value = ContentSearch(needle, emptyMap())
            return
        }
        contentSearch.value = ContentSearch(needle, hits = null)
        contentSearchJob = viewModelScope.launch {
            delay(CONTENT_SEARCH_DEBOUNCE_MILLIS)
            val hits = try {
                search.search(needle)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.send(UiText(R.string.home_search_failed))
                emptyList()
            }
            contentSearch.value = ContentSearch(needle, hits.associateBy { it.documentId })
        }
    }

    fun startSelection(id: Long) {
        _selection.value = _selection.value.start(id)
    }

    fun toggleSelection(id: Long) {
        _selection.value = _selection.value.toggle(id)
    }

    fun exitSelection() {
        _selection.value = _selection.value.clear()
    }

    fun toggleSelectAll(visible: Set<Long>) {
        _selection.value = _selection.value.toggleAll(visible)
    }

    private fun selectedDocuments(): List<DocumentEntity> {
        val ids = uiState.value.selection.ids
        return uiState.value.documents.filter { it.id in ids }
    }

    fun toggleGroup(group: FormatGroup) = _filter.update { it.toggled(group) }

    fun toggleStarredFilter() = _filter.update { it.withStarredOnly(!it.starredOnly) }

    fun clearFilters() {
        _filter.value = LibraryFilter()
    }

    fun setSort(sort: LibrarySort) = settings.setLibrarySort(sort)

    fun toggleStarred(document: DocumentEntity) {
        viewModelScope.launch {
            try {
                repository.setStarred(listOf(document.id), !document.isStarred)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.send(UiText(R.string.home_star_failed))
            }
        }
    }

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun onDocumentsPicked(uris: List<Uri>, open: (Long) -> Unit, onAdded: () -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            if (uris.size == 1) {
                try {
                    open(repository.register(uris.first()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _messages.send(UiText(R.string.home_open_failed))
                }
                return@launch
            }
            var added = 0
            var skipped = 0
            for (uri in uris) {
                try {
                    // register returns a negative id for a transient (password-protected) PDF: opened, not added.
                    if (repository.register(uri) > 0) added++ else skipped++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                }
            }
            if (added > 0) {
                onAdded()
                _messages.send(UiText(R.string.home_added_files, added))
            }
            if (skipped > 0) _messages.send(UiText(R.string.home_skipped_encrypted, skipped))
            if (added == 0 && skipped == 0) _messages.send(UiText(R.string.home_open_failed))
        }
    }

    fun remove(document: DocumentEntity) {
        hiddenIds.update { it + document.id }
        pendingRemoval.begin(listOf(document))
    }

    fun undoRemove(token: Long) {
        val entry = pendingRemoval.pending.value?.takeIf { it.token == token } ?: return
        if (pendingRemoval.undo(token)) hiddenIds.update { it - entry.item.mapTo(HashSet()) { document -> document.id } }
    }

    // Runs on [removalScope] since [PendingRemoval] holds a lock; a failed removal is the one case that takes the row back out of [hiddenIds].
    private fun commitRemoval(documents: List<DocumentEntity>) {
        removalScope.launch {
            for (document in documents) {
                try {
                    repository.remove(document)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    hiddenIds.update { it - document.id }
                }
            }
        }
    }

    override fun onCleared() {
        pendingRemoval.flush()
    }

    // Immediate: the confirmation dialog already asked.
    fun removeSelected() {
        val selected = selectedDocuments()
        if (selected.isEmpty() || bulkRunning()) return
        bulkJob = viewModelScope.launch {
            val removed = trackBulk(BulkAction.REMOVE, selected.size, ::publishBulk) { progress ->
                var count = 0
                for (document in selected) {
                    try {
                        repository.remove(document)
                        count++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                    }
                    progress.advance()
                }
                count
            }
            // Sent after the progress is gone, and only once: the snackbar is the announcement.
            if (removed > 0) _messages.send(UiText(R.string.home_removed_count, removed))
            exitSelection()
        }
    }

    private fun bulkRunning(): Boolean = bulkJob?.isActive == true

    private fun publishBulk(progress: BulkProgress?) {
        _bulkProgress.value = progress
    }

    /** Probed first: a chooser opened over a dead grant works, then fails inside the chosen app. */
    fun share(document: DocumentEntity, onShare: (Intent) -> Unit) {
        viewModelScope.launch {
            when (repository.probeShare(document)) {
                SafAccess.Readable ->
                    onShare(shareIntent(repository.resolveShareUri(document.uri.toUri()), repository.shareTypeFor(document)))
                SafAccess.NoAccess -> _messages.send(UiText(R.string.home_share_no_access))
                SafAccess.Missing -> _messages.send(UiText(R.string.home_share_missing))
                SafAccess.Unreadable -> _messages.send(UiText(R.string.home_share_failed))
            }
        }
    }

    // Checking the files is the slow part, so that is what shows progress; the chooser is the system's own.
    fun shareSelected(onShare: (Intent) -> Unit) {
        val selected = selectedDocuments()
        if (selected.isEmpty() || bulkRunning()) return
        bulkJob = viewModelScope.launch {
            val readable = trackBulk(BulkAction.SHARE, selected.size, ::publishBulk) { progress ->
                probeShareable(
                    selected,
                    probe = { repository.probeShare(it) },
                    type = { repository.mimeTypeOf(it) },
                    onProbed = progress::advance,
                )
            }
            if (readable.isEmpty()) {
                _messages.send(UiText(R.string.home_share_none))
                return@launch
            }
            val uris = readable.map { (document, _) -> repository.resolveShareUri(document.uri.toUri()) }
            val types = readable.map { (document, reported) -> shareMimeType(document.format, reported) }
            onShare(shareIntentMultiple(uris, sharedMimeType(types)))
            val skipped = selected.size - readable.size
            if (skipped > 0) _messages.send(UiText(R.string.home_share_skipped, readable.size, skipped))
            exitSelection()
        }
    }
}

private val PlusIcon: ImageVector = ImageVector.Builder(
    name = "Plus",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
    ) {
        moveTo(12f, 5f)
        lineTo(12f, 19f)
        moveTo(5f, 12f)
        lineTo(19f, 12f)
    }
}.build()

private const val UNDO_WINDOW_MILLIS = 6_000L

private val ScreenPadding = 16.dp

private val TitleInset = 24.dp

@Composable
fun HomeScreen(
    onOpenDocument: (Long, String?) -> Unit,
    onOpenSettings: () -> Unit,
    openDocumentId: Long? = null,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Sync copy of the query so typing never waits on a flow round trip; seeded so returning from
    // the viewer finds the same search.
    var text by remember { mutableStateOf(viewModel.query.value) }

    // Which row has its menu open, not a bare flag: one flag would open every row's menu at once.
    var menuFor by remember { mutableStateOf<Long?>(null) }

    var sheetOpen by remember { mutableStateOf(false) }

    // Bulk remove confirms because it cannot be undone; the ⋮ menu's Remove can be.
    var confirmRemove by remember { mutableStateOf(false) }

    val dismissKeyboard: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    val openFiles = rememberLauncherForActivityResult(OpenPersistableDocuments()) { uris ->
        viewModel.onDocumentsPicked(
            uris = uris,
            open = { id -> onOpenDocument(id, null) },
            onAdded = { scope.launch { listState.scrollToItem(0) } },
        )
    }

    // Per-document read grant is enough: the app never writes to a document it opened.
    val context = LocalContext.current

    // Built here because both startActivity and the chooser title need the Activity.
    val chooserTitle = stringResource(R.string.action_share)
    val shareDocument: (DocumentEntity) -> Unit = { doc ->
        dismissKeyboard()
        viewModel.share(doc) { send -> context.startActivity(Intent.createChooser(send, chooserTitle)) }
    }
    val shareSelection: () -> Unit = {
        dismissKeyboard()
        viewModel.shareSelected { send -> context.startActivity(Intent.createChooser(send, chooserTitle)) }
    }

    BackHandler(enabled = state.selection.active) { viewModel.exitSelection() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it.asString(context)) }
    }

    // The ViewModel's timer decides when the window ends, not the snackbar's own: it is indefinite and
    // lives exactly as long as the removal is pending, and the token makes a late tap a no-op.
    val removedMessage = stringResource(R.string.home_removed)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(state.pendingRemovalToken) {
        val token = state.pendingRemovalToken ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = removedMessage,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove(token)
    }

    // Dragging the list means the person is done typing. A drag interaction rather than
    // `isScrollInProgress`, which also goes true for our own scroll-to-top while typing.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) dismissKeyboard()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!state.selection.active) {
                FloatingActionButton(
                    onClick = {
                        dismissKeyboard()
                        openFiles.launch(DocumentFormat.pickerMimeTypes)
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(ControlCorner),
                ) {
                    Icon(
                        imageVector = PlusIcon,
                        contentDescription = stringResource(R.string.cd_add_files),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Consuming the Scaffold padding first stops the navigation-bar inset being counted twice.
                .consumeWindowInsets(padding)
                .pointerInput(Unit) { detectTapGestures(onTap = { dismissKeyboard() }) },
        ) {
            // Row padding keeps the bar as tall as the bare title was, and the icon's end padding
            // lands its glyph on the search pill's right edge (4dp + the button's 12dp inset = ScreenPadding).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = TitleInset, end = 4.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                IconButton(
                    onClick = {
                        dismissKeyboard()
                        onOpenSettings()
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.cd_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.selection.active) {
                SelectionHeader(
                    count = state.selection.ids.size,
                    allSelected = state.results.isNotEmpty() && state.results.all { it.id in state.selection.ids },
                    progress = state.bulkProgress,
                    onExit = { viewModel.exitSelection() },
                    onToggleAll = { viewModel.toggleSelectAll(state.results.mapTo(mutableSetOf()) { it.id }) },
                    onShare = shareSelection,
                    onRemove = { confirmRemove = true },
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            } else {
                // The sort and filter button's glyph lands on the old right edge (4dp + its 12dp inset =
                // ScreenPadding), so the pill only gives up the room the button itself takes.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = TitleInset, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchPill(
                        text = text,
                        onTextChange = { value ->
                            text = value
                            viewModel.onQueryChange(value)
                            // A new query starts from the top; otherwise a short result list can open
                            // part-way down with its first rows out of view.
                            if (listState.canScrollBackward) scope.launch { listState.scrollToItem(0) }
                        },
                        hint = stringResource(R.string.home_search_hint),
                        onSearch = dismissKeyboard,
                        modifier = Modifier.weight(1f),
                        trailing = {
                            // The slot is always reserved: only the icon fades, so the text never shifts sideways
                            // when the first character is typed.
                            ClearSearchButton(
                                visible = text.isNotEmpty(),
                                onClear = {
                                    text = ""
                                    viewModel.onQueryChange("")
                                },
                            )
                        },
                    )
                    SortFilterButton(
                        sort = state.sort,
                        activeCount = state.filter.activeCount,
                        onClick = {
                            dismissKeyboard()
                            sheetOpen = true
                        },
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Fades in once, on the first load. Already loaded when the screen is re-entered, so
                // it starts at 1 and does not replay.
                val listAlpha by animateFloatAsState(
                    targetValue = if (state.loaded) 1f else 0f,
                    animationSpec = tween(300),
                    label = "listAlpha",
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = listAlpha },
                    contentPadding = PaddingValues(start = ScreenPadding, top = 12.dp, end = ScreenPadding, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.results, key = { it.id }) { doc ->
                        DocumentRow(
                            doc = doc,
                            hit = state.contentHits[doc.id],
                            loader = viewModel.thumbnailLoader,
                            selecting = state.selection.active,
                            selected = doc.id in state.selection.ids,
                            open = doc.id == openDocumentId,
                            menuOpen = menuFor == doc.id,
                            onMenuOpenChange = { open -> menuFor = if (open) doc.id else null },
                            searchQuery = state.query,
                            onClick = {
                                if (state.selection.active) {
                                    viewModel.toggleSelection(doc.id)
                                } else {
                                    dismissKeyboard()
                                    onOpenDocument(doc.id, focusTermFor(state.query, hasContentHit = state.contentHits[doc.id] != null))
                                }
                            },
                            onLongPress = {
                                dismissKeyboard()
                                menuFor = null
                                viewModel.startSelection(doc.id)
                            },
                            onRemove = {
                                dismissKeyboard()
                                viewModel.remove(doc)
                            },
                            onShare = { shareDocument(doc) },
                            onToggleStarred = { viewModel.toggleStarred(doc) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                EmptyStateOverlay(
                    visible = state.loaded && state.results.isEmpty() && !state.searching,
                    libraryEmpty = state.libraryEmpty && state.query.isEmpty(),
                    query = state.query,
                    filtersActive = state.filter.isActive && !state.libraryEmpty,
                    onClearFilters = viewModel::clearFilters,
                )
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.home_remove_confirm_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.home_remove_confirm_body,
                        state.selection.ids.size,
                        state.selection.ids.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        viewModel.removeSelected()
                    },
                ) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (sheetOpen) {
        FilterSortSheet(
            sort = state.sort,
            filter = state.filter,
            groups = state.groups,
            // The starred chip stays once it is on, so it can be switched off again.
            showStarred = state.hasStarred || state.filter.starredOnly,
            matchCount = state.results.size,
            onSort = { sort ->
                viewModel.setSort(sort)
                if (listState.canScrollBackward) scope.launch { listState.scrollToItem(0) }
            },
            onToggleGroup = { group ->
                viewModel.toggleGroup(group)
                if (listState.canScrollBackward) scope.launch { listState.scrollToItem(0) }
            },
            onToggleStarred = {
                viewModel.toggleStarredFilter()
                if (listState.canScrollBackward) scope.launch { listState.scrollToItem(0) }
            },
            onClearFilters = viewModel::clearFilters,
            onDismiss = { sheetOpen = false },
        )
    }
}

// While a bulk action runs the count gives way to progress, and the buttons that would start another are off.
@Composable
internal fun SelectionHeader(
    count: Int,
    allSelected: Boolean,
    progress: BulkProgress?,
    onExit: () -> Unit,
    onToggleAll: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = progress != null
    val hasSelection = count > 0 && !busy
    val selectAllLabel = stringResource(R.string.home_select_all)
    // A Box so the bar can sit on the header's bottom edge without making it taller when it appears.
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ControlHeight)
                // 4dp inside the screen's 16dp list inset puts the checkbox on the row checkboxes' centre line.
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Text(
                text = progress?.label() ?: stringResource(R.string.home_selected_count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            IconButton(onClick = onRemove, enabled = hasSelection) {
                Icon(
                    painter = painterResource(R.drawable.ic_remove),
                    contentDescription = stringResource(R.string.action_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShare, enabled = hasSelection) {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = stringResource(R.string.action_share),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(
                checked = allSelected,
                onCheckedChange = { onToggleAll() },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = selectAllLabel },
            )
        }
        if (progress != null) {
            BulkProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

/** Drawn over the list, not inline: AnimatedVisibility here needs a Column/Row receiver. */
@Composable
private fun EmptyStateOverlay(
    visible: Boolean,
    libraryEmpty: Boolean,
    query: String,
    filtersActive: Boolean,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(tween(300, delayMillis = 120)),
        exit = ExitTransition.None,
    ) {
        // Three different situations: nothing added yet, filters that hide everything, a search with no hit.
        // Only the filters can be undone in one tap, so only they get the button.
        EmptyState(
            title = when {
                libraryEmpty -> stringResource(R.string.home_empty_title)
                filtersActive && query.isEmpty() -> stringResource(R.string.home_filter_none)
                else -> stringResource(R.string.home_search_none, query)
            },
            libraryEmpty = libraryEmpty,
            actionLabel = if (filtersActive) stringResource(R.string.home_filter_clear) else null,
            onAction = onClearFilters,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// Extracted because AnimatedVisibility needs a Column/Row receiver, as in [EmptyStateOverlay].
@Composable
private fun ClearSearchButton(
    visible: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(ControlHeight), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.8f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
        ) {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_clear_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    libraryEmpty: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = if (libraryEmpty) modifier.padding(start = 32.dp, end = 32.dp, bottom = 48.dp) else modifier.padding(horizontal = 32.dp),
        verticalArrangement = if (libraryEmpty) Arrangement.Center else Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (libraryEmpty) {
            Image(
                painter = painterResource(R.drawable.ic_empty_box),
                contentDescription = null,
                modifier = Modifier.size(160.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(48.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (libraryEmpty) {
            Text(
                text = stringResource(R.string.home_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (actionLabel != null) {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 16.dp).heightIn(min = ControlHeight),
            ) { Text(actionLabel) }
        }
    }
}

@Composable
private fun DocumentRow(
    doc: DocumentEntity,
    hit: ContentHit?,
    loader: ThumbnailLoader,
    selecting: Boolean,
    selected: Boolean,
    /** This is the document showing beside the list. Not the same as [selected], which is for a bulk action. */
    open: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
    onShare: () -> Unit,
    onToggleStarred: () -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
) {
    val context = LocalContext.current
    // Remembered so a row that is merely re-placed by the list animation does not re-format its text.
    val sizeLabel = remember(doc.sizeBytes) { Formatter.formatShortFileSize(context, doc.sizeBytes) }
    val justNow = stringResource(R.string.home_just_now)
    val openedLabel = remember(doc.lastOpenedAt) {
        if (System.currentTimeMillis() - doc.lastOpenedAt < DateUtils.MINUTE_IN_MILLIS * 3) {
            justNow
        } else {
            DateUtils.getRelativeTimeSpanString(doc.lastOpenedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
    }
    val needle = searchQuery.trim()
    val isFilenameMatch = needle.isNotEmpty() && doc.title.contains(needle, ignoreCase = true)
    val orangeHighlight = ActiveMatchColor.copy(alpha = 0.35f)
    val annotatedTitle = remember(doc.title, needle, isFilenameMatch) {
        if (!isFilenameMatch) {
            AnnotatedString(doc.title)
        } else {
            buildAnnotatedString {
                append(doc.title)
                val ranges = findMatchRanges(doc.title, needle)
                for (range in ranges) {
                    val start = range.first.coerceIn(0, doc.title.length)
                    val end = (range.last + 1).coerceIn(start, doc.title.length)
                    if (end > start) {
                        addStyle(SpanStyle(background = orangeHighlight), start, end)
                    }
                }
            }
        }
    }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        // combinedClickable rather than Surface(onClick): the long-press that enters selection has no
        // Surface overload. The clip keeps the ripple inside the rounded corners.
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            // Only said when true, so a phone's rows read exactly as they did.
            .then(if (open) Modifier.semantics { this.selected = true } else Modifier)
            .combinedClickable(
                onClickLabel = stringResource(
                    when {
                        !selecting -> R.string.home_row_open
                        selected -> R.string.home_row_deselect
                        else -> R.string.home_row_select
                    },
                ),
                onLongClickLabel = if (selecting) null else stringResource(R.string.home_row_select),
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        shape = shape,
        color = when {
            selected -> MaterialTheme.colorScheme.surfaceContainerHighest
            open -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DocumentThumbnail(doc, loader)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    annotatedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (needle.isEmpty() || isFilenameMatch) {
                    Text(
                        "$sizeLabel · $openedLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Only a content match has an excerpt to show; without it, a document found only by
                // its contents would look like a result with no reason to be here.
                hit?.let { SnippetText(text = it.snippet, highlights = it.highlightRanges) }
            }
            // Its own slot, left of the ⋮: inside the title's row the star sat at the end of a
            // short name and at the far right of a long one, so it moved with the length of the name.
            if (doc.isStarred) {
                Icon(
                    painter = painterResource(R.drawable.ic_star),
                    contentDescription = stringResource(R.string.home_star_marker),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp).size(14.dp),
                )
            }
            // The checkbox takes the ⋮'s exact slot, so the row's contents hold still between modes.
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                Box {
                    IconButton(onClick = { onMenuOpenChange(true) }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.home_menu),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                        // Destructive last, so it cannot be reached by reflex from the icon.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_share)) },
                            onClick = { onMenuOpenChange(false); onShare() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (doc.isStarred) R.string.home_star_remove else R.string.home_star_add,
                                    ),
                                )
                            },
                            onClick = { onMenuOpenChange(false); onToggleStarred() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_remove)) },
                            onClick = { onMenuOpenChange(false); onRemove() },
                        )
                    }
                }
            }
        }
    }
}
