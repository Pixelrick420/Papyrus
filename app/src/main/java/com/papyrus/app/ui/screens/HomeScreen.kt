package com.papyrus.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papyrus.app.R
import com.papyrus.app.data.DocumentEntity
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.DocumentRepository
import com.papyrus.app.data.FormatGroup
import com.papyrus.app.data.LibraryFilter
import com.papyrus.app.data.LibrarySort
import com.papyrus.app.data.OpenPersistableDocuments
import com.papyrus.app.data.SafAccess
import com.papyrus.app.data.SettingsRepository
import com.papyrus.app.data.ThumbnailLoader
import com.papyrus.app.data.availableGroups
import com.papyrus.app.data.libraryView
import com.papyrus.app.data.shareIntent
import com.papyrus.app.data.shareIntentMultiple
import com.papyrus.app.data.shareMimeType
import com.papyrus.app.data.sharedMimeType
import com.papyrus.app.ui.AppViewModelProvider
import com.papyrus.app.ui.UiText
import com.papyrus.app.ui.asString
import com.papyrus.app.ui.components.ControlCorner
import com.papyrus.app.ui.components.ControlHeight
import com.papyrus.app.ui.components.DocumentThumbnail
import com.papyrus.app.ui.components.SearchPill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * Everything the list needs in one value, so loading, empty and no-match states never disagree.
 */
data class HomeUiState(
    val loaded: Boolean = false,
    val libraryEmpty: Boolean = false,
    /** Trimmed query [results] was filtered with, so the "no matches" text never leads the list. */
    val query: String = "",
    /** The unfiltered library, so a bulk action resolves ids the current query may be hiding. */
    val documents: List<DocumentEntity> = emptyList(),
    val results: List<DocumentEntity> = emptyList(),
    val selection: SelectionState = SelectionState(),
    /** The removal waiting out its undo window, or null; [HomeViewModel.undoRemove] takes it back. */
    val pendingRemovalToken: Long? = null,
    val filter: LibraryFilter = LibraryFilter(),
    val sort: LibrarySort = LibrarySort.LAST_OPENED,
    /** The format chips: what the library has plus what is selected, so a selected chip can always be unselected. */
    val groups: List<FormatGroup> = emptyList(),
    val hasFavourites: Boolean = false,
)

/**
 * What the list shows for one set of inputs. Pure and independent of the flows it reads from, so
 * [HomeViewModel.uiState] can run it off the main thread.
 */
internal fun buildHomeUiState(
    all: List<DocumentEntity>,
    params: ViewParams,
    selection: SelectionState,
    pendingRemovalToken: Long?,
    hidden: Set<Long>,
): HomeUiState {
    val needle = params.query.trim()
    // Hiding first keeps `libraryEmpty` and the results agreeing with what the screen actually shows.
    val documents = if (hidden.isEmpty()) all else all.filterNot { it.id in hidden }
    return HomeUiState(
        loaded = true,
        libraryEmpty = documents.isEmpty(),
        query = needle,
        documents = documents,
        results = libraryView(documents, needle, params.filter, params.sort),
        selection = selection,
        pendingRemovalToken = pendingRemovalToken,
        filter = params.filter,
        sort = params.sort,
        groups = (availableGroups(documents) + params.filter.groups).distinct().sortedBy { it.ordinal },
        hasFavourites = documents.any { it.isFavourite },
    )
}

/** How many share probes run at once: enough to hide a slow provider, few enough to stay polite. */
private const val SHARE_PROBE_PARALLELISM = 4

/**
 * The share pre-flight for several files, concurrently but at most [limit] at a time: each document
 * is probed once, and only a readable one is also asked its type. The result keeps [selected]'s
 * order and holds the readable documents paired with the type they reported.
 */
internal suspend fun <T> probeShareable(
    selected: List<T>,
    limit: Int = SHARE_PROBE_PARALLELISM,
    probe: suspend (T) -> SafAccess,
    type: suspend (T) -> String?,
): List<Pair<T, String?>> = coroutineScope {
    val gate = Semaphore(limit)
    selected.map { document ->
        async(Dispatchers.IO) {
            gate.withPermit {
                if (probe(document) == SafAccess.Readable) document to type(document) else null
            }
        }
    }.awaitAll().filterNotNull()
}

/** What the list is built with, apart from the library itself. */
internal data class ViewParams(val query: String, val filter: LibraryFilter, val sort: LibrarySort)

class HomeViewModel(
    private val repository: DocumentRepository,
    val thumbnailLoader: ThumbnailLoader,
    /**
     * Outlives this ViewModel, so a removal still in its undo window when Home is cleared commits here.
     */
    private val removalScope: CoroutineScope,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Kept here, so it survives a rotation; not stored, a new session starts unfiltered. */
    private val _filter = MutableStateFlow(LibraryFilter())

    private val viewParams = combine(
        _query,
        _filter,
        settings.settings.map { it.librarySort }.distinctUntilChanged(),
    ) { query, filter, sort -> ViewParams(query.trim(), filter, sort) }

    private val _selection = MutableStateFlow(SelectionState())

    /**
     * Rows removed with an undo window; hidden only until it ends, then committed in [commitRemoval].
     */
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

    /**
     * The one thing that hides rows: ids removed but not yet gone from the list. An id leaves only on
     * Undo, a failed commit, or once the list drops the row, so the database's emission never shows it.
     */
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

    /**
     * Filtered in memory; starts "not loaded" so a populated library cannot flash its empty state.
     * Hidden rows are left out of documents and libraryEmpty too, so bulk actions agree with the screen.
     * The filter runs on [Dispatchers.Default] so typing never waits on a large library, and
     * [distinctUntilChanged] drops the second, identical state the prune above would otherwise publish.
     */
    val uiState: StateFlow<HomeUiState> = combine(
        repository.documents, viewParams, _selection, pendingRemoval.pending, hiddenIds,
    ) { all, params, selection, pending, hidden ->
        buildHomeUiState(all, params, selection, pending?.token, hidden)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onQueryChange(value: String) {
        _query.value = value
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

    /** Applies to what the current filter shows, so "select all" means all visible rows. */
    fun toggleSelectAll(visible: Set<Long>) {
        _selection.value = _selection.value.toggleAll(visible)
    }

    private fun selectedDocuments(): List<DocumentEntity> {
        val ids = uiState.value.selection.ids
        return uiState.value.documents.filter { it.id in ids }
    }

    fun toggleGroup(group: FormatGroup) = _filter.update { it.toggled(group) }

    fun toggleFavouritesFilter() = _filter.update { it.withFavouritesOnly(!it.favouritesOnly) }

    fun clearFilters() {
        _filter.value = LibraryFilter()
    }

    fun setSort(sort: LibrarySort) = settings.setLibrarySort(sort)

    fun toggleFavourite(document: DocumentEntity) {
        viewModelScope.launch {
            try {
                repository.setFavourite(listOf(document.id), !document.isFavourite)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _messages.send(UiText(R.string.home_favourite_failed))
            }
        }
    }

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** One file opens straight away; several are only added, leaving the person on Home. */
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

    /** Hides the row and starts its undo window; the real removal happens when the window ends. */
    fun remove(document: DocumentEntity) {
        hiddenIds.update { it + document.id }
        pendingRemoval.begin(listOf(document))
    }

    /** Takes back the removal [token] names; a stale token does nothing. */
    fun undoRemove(token: Long) {
        val entry = pendingRemoval.pending.value?.takeIf { it.token == token } ?: return
        if (pendingRemoval.undo(token)) hiddenIds.update { it - entry.item.mapTo(HashSet()) { document -> document.id } }
    }

    /**
     * Releases the grant, deletes the row and the owned copy, on [removalScope] since [PendingRemoval]
     * holds a lock; a failed removal is the one case that takes the row back out of [hiddenIds].
     */
    private fun commitRemoval(documents: List<DocumentEntity>) {
        removalScope.launch {
            for (document in documents) {
                try {
                    repository.remove(document)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing was removed, so the list gets the row back instead of hiding it for good.
                    hiddenIds.update { it - document.id }
                }
            }
        }
    }

    /**
     * Commits a pending removal when Home goes away; a process death leaves the row in the library.
     */
    override fun onCleared() {
        pendingRemoval.flush()
    }

    /** Bulk remove only, which is immediate because the confirmation dialog already asked. */
    fun removeSelected() {
        val selected = selectedDocuments()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            var removed = 0
            for (document in selected) {
                try {
                    repository.remove(document)
                    removed++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Skip the one that failed; the rest still go.
                }
            }
            if (removed > 0) _messages.send(UiText(R.string.home_removed_count, removed))
            exitSelection()
        }
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

    /** Unreadable files are skipped and reported rather than failing the whole share. */
    fun shareSelected(onShare: (Intent) -> Unit) {
        val selected = selectedDocuments()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val readable = probeShareable(
                selected,
                probe = { repository.probeShare(it) },
                type = { repository.mimeTypeOf(it) },
            )
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

/** A plus drawn as two strokes on a 24dp grid; tinted by [Icon]. */
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

/** How long a single-row removal can be undone: long enough to notice and reach the button. */
private const val UNDO_WINDOW_MILLIS = 6_000L

/** List inset; title and search pill use [TitleInset]. */
private val ScreenPadding = 16.dp

private val TitleInset = 24.dp

@Composable
fun HomeScreen(
    onOpenDocument: (Long) -> Unit,
    onOpenSettings: () -> Unit,
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

    // Bulk remove confirms because it cannot be undone; the ⋮ menu's Remove can be.
    var confirmRemove by remember { mutableStateOf(false) }

    val dismissKeyboard: () -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    val openFiles = rememberLauncherForActivityResult(OpenPersistableDocuments()) { uris ->
        viewModel.onDocumentsPicked(
            uris = uris,
            open = onOpenDocument,
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

    // Back leaves selection before it leaves Home; only active while selecting.
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
                // Taps nothing else claimed (gaps, empty space) put the keyboard away.
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
                    onExit = { viewModel.exitSelection() },
                    onToggleAll = { viewModel.toggleSelectAll(state.results.mapTo(mutableSetOf()) { it.id }) },
                    onShare = shareSelection,
                    onRemove = { confirmRemove = true },
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            } else {
                // The sort button's glyph lands on the old right edge (4dp + its 12dp inset = ScreenPadding), so the
                // pill only gives up the room the button itself takes.
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
                        // Search keeps the filter and just puts the keyboard away.
                        onSearch = dismissKeyboard,
                        iconContentDescription = stringResource(R.string.cd_search),
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
                    SortButton(
                        current = state.sort,
                        onSelect = { sort ->
                            viewModel.setSort(sort)
                            if (listState.canScrollBackward) scope.launch { listState.scrollToItem(0) }
                        },
                    )
                }
                if (!state.libraryEmpty && (state.groups.size > 1 || state.hasFavourites || state.filter.isActive)) {
                    FilterChipsRow(
                        groups = state.groups,
                        filter = state.filter,
                        showFavourites = state.hasFavourites || state.filter.favouritesOnly,
                        onToggleGroup = { group ->
                            viewModel.toggleGroup(group)
                            if (listState.canScrollBackward) scope.launch { listState.scrollToItem(0) }
                        },
                        onToggleFavourites = {
                            viewModel.toggleFavouritesFilter()
                            if (listState.canScrollBackward) scope.launch { listState.scrollToItem(0) }
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
                            loader = viewModel.thumbnailLoader,
                            selecting = state.selection.active,
                            selected = doc.id in state.selection.ids,
                            menuOpen = menuFor == doc.id,
                            onMenuOpenChange = { open -> menuFor = if (open) doc.id else null },
                            onClick = {
                                if (state.selection.active) {
                                    viewModel.toggleSelection(doc.id)
                                } else {
                                    dismissKeyboard()
                                    onOpenDocument(doc.id)
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
                            onToggleFavourite = { viewModel.toggleFavourite(doc) },
                            // Filtering, removing and re-sorting glide instead of snapping.
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                // Overlaid, not swapped with the list, so the last row can fade out inside it; no exit animation —
                // the message goes at once and the rows fade in, which reads cleaner than a cross-fade.
                EmptyStateOverlay(
                    visible = state.loaded && state.results.isEmpty(),
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
}

/** Takes the search pill's slot while selecting: back, count, remove/share/select-all. */
@Composable
private fun SelectionHeader(
    count: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onToggleAll: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSelection = count > 0
    Row(
        modifier = modifier
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
            text = stringResource(R.string.home_selected_count, count),
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
        Checkbox(checked = allSelected, onCheckedChange = { onToggleAll() })
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

/** An icon beside the search pill that opens the sort menu; the current order is marked and spoken. */
@Composable
private fun SortButton(
    current: LibrarySort,
    onSelect: (LibrarySort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = stringResource(current.labelRes())
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_sort),
                contentDescription = stringResource(R.string.home_sort_cd, currentLabel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (sort in LibrarySort.entries) {
                val isCurrent = sort == current
                DropdownMenuItem(
                    text = { Text(stringResource(sort.labelRes())) },
                    // The mark holds its place on every row, so the labels line up.
                    leadingIcon = {
                        if (isCurrent) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(sort)
                    },
                    modifier = Modifier.semantics { selected = isCurrent },
                )
            }
        }
    }
}

/** Format chips from what the library has, then Favourites; the row scrolls sideways on a narrow screen. */
@Composable
private fun FilterChipsRow(
    groups: List<FormatGroup>,
    filter: LibraryFilter,
    showFavourites: Boolean,
    onToggleGroup: (FormatGroup) -> Unit,
    onToggleFavourites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        contentPadding = PaddingValues(start = TitleInset, end = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showFavourites) {
            item(key = "favourites") {
                FilterChoiceChip(
                    label = stringResource(R.string.home_filter_favourites),
                    selected = filter.favouritesOnly,
                    onClick = onToggleFavourites,
                )
            }
        }
        items(groups, key = { it.name }) { group ->
            FilterChoiceChip(
                label = stringResource(group.labelRes()),
                selected = group in filter.groups,
                onClick = { onToggleGroup(group) },
            )
        }
    }
}

/** Selected state shows as a check as well as the fill, so it never rests on colour alone. */
@Composable
private fun FilterChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
    )
}

@StringRes
private fun LibrarySort.labelRes(): Int = when (this) {
    LibrarySort.LAST_OPENED -> R.string.home_sort_last_opened
    LibrarySort.NAME_ASC -> R.string.home_sort_name_asc
    LibrarySort.NAME_DESC -> R.string.home_sort_name_desc
    LibrarySort.DATE_ADDED -> R.string.home_sort_date_added
    LibrarySort.SIZE -> R.string.home_sort_size
}

@StringRes
private fun FormatGroup.labelRes(): Int = when (this) {
    FormatGroup.PDF -> R.string.home_filter_pdf
    FormatGroup.WORD -> R.string.home_filter_word
    FormatGroup.ODT -> R.string.home_filter_odt
    FormatGroup.EPUB -> R.string.home_filter_epub
    FormatGroup.MARKDOWN -> R.string.home_filter_markdown
    FormatGroup.TEXT -> R.string.home_filter_text
    FormatGroup.CSV -> R.string.home_filter_csv
    FormatGroup.CODE -> R.string.home_filter_code
}

/**
 * Clear button that fades in and out inside a constant-size slot; extracted because
 * AnimatedVisibility needs a Column/Row receiver, as in [EmptyStateOverlay].
 */
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

/** The empty states: a first-run library is centred; a search or filter that hides everything sits near the top. */
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
    loader: ThumbnailLoader,
    selecting: Boolean,
    selected: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
    onShare: () -> Unit,
    onToggleFavourite: () -> Unit,
    modifier: Modifier = Modifier,
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
    val shape = RoundedCornerShape(16.dp)
    Surface(
        // combinedClickable rather than Surface(onClick): the long-press that enters selection has no
        // Surface overload. The clip keeps the ripple inside the rounded corners.
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
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
                    doc.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$sizeLabel · $openedLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Its own slot, left of the ⋮: inside the title's row the star sat at the end of a
            // short name and at the far right of a long one, so it moved with the length of the name.
            if (doc.isFavourite) {
                Icon(
                    painter = painterResource(R.drawable.ic_star),
                    contentDescription = stringResource(R.string.home_favourite_marker),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp).size(14.dp),
                )
            }
            // The checkbox takes the ⋮'s exact slot, so the row's contents hold still between modes.
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
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
                                        if (doc.isFavourite) R.string.home_favourite_remove else R.string.home_favourite_add,
                                    ),
                                )
                            },
                            onClick = { onMenuOpenChange(false); onToggleFavourite() },
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
