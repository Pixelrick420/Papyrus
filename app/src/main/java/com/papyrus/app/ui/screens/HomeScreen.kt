package com.papyrus.app.ui.screens

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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.heading
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
import com.papyrus.app.data.OpenPersistableDocuments
import com.papyrus.app.data.SafAccess
import com.papyrus.app.data.ThumbnailLoader
import com.papyrus.app.data.shareIntent
import com.papyrus.app.data.shareIntentMultiple
import com.papyrus.app.data.sharedMimeType
import com.papyrus.app.ui.AppViewModelProvider
import com.papyrus.app.ui.UiText
import com.papyrus.app.ui.asString
import com.papyrus.app.ui.components.ControlCorner
import com.papyrus.app.ui.components.ControlHeight
import com.papyrus.app.ui.components.DocumentThumbnail
import com.papyrus.app.ui.components.SearchPill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the list needs in one value, so the loading, empty and no-match states never disagree for a frame. */
data class HomeUiState(
    val loaded: Boolean = false,
    val libraryEmpty: Boolean = false,
    /** The trimmed query [results] was filtered with, so the "no matches" text is never ahead of the list. */
    val query: String = "",
    /** The unfiltered library, so a bulk action resolves ids the current query may be hiding. */
    val documents: List<DocumentEntity> = emptyList(),
    val results: List<DocumentEntity> = emptyList(),
    val selection: SelectionState = SelectionState(),
)

class HomeViewModel(
    private val repository: DocumentRepository,
    val thumbnailLoader: ThumbnailLoader,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selection = MutableStateFlow(SelectionState())

    /**
     * Filtered in memory — the whole list is already held. Starts "not loaded" rather than empty, or
     * a library with documents flashes its empty state before the first database emission.
     */
    val uiState: StateFlow<HomeUiState> = combine(repository.documents, _query, _selection) { docs, q, selection ->
        val needle = q.trim()
        HomeUiState(
            loaded = true,
            libraryEmpty = docs.isEmpty(),
            query = needle,
            documents = docs,
            results = docs.matches(needle),
            selection = selection,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

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

    private fun List<DocumentEntity>.matches(text: String): List<DocumentEntity> {
        val needle = text.trim()
        if (needle.isEmpty()) return this
        return filter { it.title.contains(needle, ignoreCase = true) }
    }

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** One file opens straight away; several are only added, leaving the person on Home where the new rows appear. */
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
        viewModelScope.launch {
            repository.remove(document)
            _messages.send(UiText(R.string.home_removed))
        }
    }

    /** Called only from the bulk-remove confirmation; the single-row [remove] stays immediate. */
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

    /** Probes every selected file; unreadable ones are skipped and reported rather than failing the whole share. */
    fun shareSelected(onShare: (Intent) -> Unit) {
        val selected = selectedDocuments()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val readable = selected.filter { repository.probeShare(it) == SafAccess.Readable }
            if (readable.isEmpty()) {
                _messages.send(UiText(R.string.home_share_none))
                return@launch
            }
            val uris = readable.map { repository.resolveShareUri(it.uri.toUri()) }
            val types = readable.map { repository.shareTypeFor(it) }
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

/** List inset; title and search pill use [TitleInset]. */
private val ScreenPadding = 16.dp

/** Title and search pill left edge alignment. */
private val TitleInset = 24.dp

@Composable
fun HomeScreen(
    onOpenDocument: (Long) -> Unit,
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

    // Bulk remove is the only destructive bulk action, so it alone confirms; the ⋮ menu's Remove stays immediate.
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
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = TitleInset, end = TitleInset, top = 12.dp, bottom = 10.dp)
                    .semantics { heading() },
            )

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
                    // Padding first, then fill: the pill gets exactly the button's width.
                    modifier = Modifier.padding(start = TitleInset, end = ScreenPadding),
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

/** Takes the search pill's slot while selecting: back, count, and right-aligned remove/share/select-all. */
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

/**
 * Empty state drawn over the list, not inline in the `Box` above: the scope-aware `AnimatedVisibility`
 * overloads need a `Column`/`Row` receiver and this is a child of a `Box`.
 */
@Composable
private fun EmptyStateOverlay(
    visible: Boolean,
    libraryEmpty: Boolean,
    query: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(tween(300, delayMillis = 120)),
        exit = ExitTransition.None,
    ) {
                EmptyState(
                    title = if (libraryEmpty && query.isEmpty()) {
                        stringResource(R.string.home_empty_title)
                    } else {
                        stringResource(R.string.home_search_none, query)
                    },
                    libraryEmpty = libraryEmpty && query.isEmpty(),
                    modifier = Modifier.fillMaxSize(),
                )
    }
}

/** Clear button fading in and out inside a slot of constant size; extracted for the same receiver reason as [EmptyStateOverlay]. */
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

/**
 * One layout for both "nothing here" states: a first-run library is centred; a failed search sits
 * near the top, since the keyboard would put a centred message behind it on a short screen.
 */
@Composable
private fun EmptyState(
    title: String,
    libraryEmpty: Boolean,
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
                            text = { Text(stringResource(R.string.action_remove)) },
                            onClick = { onMenuOpenChange(false); onRemove() },
                        )
                    }
                }
            }
        }
    }
}
