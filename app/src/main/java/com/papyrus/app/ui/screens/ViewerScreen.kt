package com.papyrus.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papyrus.app.R
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.OpenPersistableDocument
import com.papyrus.app.ui.AppViewModelProvider
import com.papyrus.app.ui.asString
import com.papyrus.app.ui.components.ControlHeight
import com.papyrus.app.ui.viewer.FileInfoSheet
import com.papyrus.app.ui.viewer.FindInFileBar
import com.papyrus.app.ui.viewer.MarkdownViewer
import com.papyrus.app.ui.viewer.OfficeViewer
import com.papyrus.app.ui.viewer.PdfSelectionState
import com.papyrus.app.ui.viewer.PdfViewer
import com.papyrus.app.ui.viewer.PlainTextViewer
import com.papyrus.app.ui.viewer.ReadoutBadge
import com.papyrus.app.ui.viewer.ZoomState
import com.papyrus.app.ui.viewer.copyToClipboard
import com.papyrus.app.ui.viewer.rememberLingerVisible
import com.papyrus.app.ui.viewer.rememberZoomState
import kotlinx.coroutines.launch

/** Same inset as Home, so the find pill lines up with the Home search pill. */
private val ScreenPadding = 16.dp

/** Find, zoom and overflow state live here, so a format switch does not reset them. */
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val zoom = rememberZoomState()
    val snackbar = remember { SnackbarHostState() }
    val selectionScope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.action_share)
    val shareDocument: () -> Unit = {
        viewModel.share { send -> context.startActivity(Intent.createChooser(send, chooserTitle)) }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }

    // Held here (not in [PdfViewer]) so the header's Copy can reach it; keyed on the source.
    val selectionState = remember((state.content as? ViewerContent.Pdf)?.source) {
        PdfSelectionState(
            scope = selectionScope,
            loadText = viewModel::pageText,
            onNoText = { Toast.makeText(context, R.string.viewer_pdf_no_text, Toast.LENGTH_SHORT).show() },
        )
    }
    // The selection takes over the header's overflow slot, so an open menu would pop up behind it.
    LaunchedEffect(selectionState.hasSelection) {
        if (selectionState.hasSelection) menuOpen = false
    }

    val find = state.find
    // Only surfaces with searchable content; a failed load has neither.
    val findAvailable = when (state.content) {
        is ViewerContent.Pdf, is ViewerContent.PlainText, is ViewerContent.Office, is ViewerContent.Markdown -> true
        else -> false
    }

    val closeFind: () -> Unit = {
        focusManager.clearFocus()
        viewModel.closeFind()
    }

    // Back dismisses find first; a second press leaves the document.
    BackHandler(enabled = find.open, onBack = closeFind)

    // SAF grants have no runtime dialog, so re-access means a re-pick through the picker.
    val regrantFile = rememberLauncherForActivityResult(OpenPersistableDocument()) { uri ->
        if (uri != null) viewModel.regrant(uri) else viewModel.consumeRegrantRequest()
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.asString(context))
            viewModel.consumeMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Ctrl+F wins over a focused field; on the column so it also covers the find field.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Ctrl only: no connected Android keyboard emits Meta, so it is untestable.
                    if (event.key == Key.F && event.isCtrlPressed && findAvailable) {
                        viewModel.openFind()
                        true
                    } else {
                        false
                    }
                },
        ) {
            ViewerHeader(
                title = state.document?.title.orEmpty(),
                findAvailable = findAvailable,
                menuOpen = menuOpen,
                selectionActive = selectionState.hasSelection,
                showShare = state.document != null,
                showSaveToLibrary = state.canSaveToLibrary,
                savingToLibrary = state.savingToLibrary,
                savedToLibrary = state.savedToLibrary,
                onMenuOpenChange = { menuOpen = it },
                onBack = onBack,
                onFind = viewModel::openFind,
                onInfo = { infoOpen = true },
                onShare = shareDocument,
                onSaveToLibrary = viewModel::saveToLibrary,
                onCopySelection = {
                    selectionScope.launch {
                        selectionState.selectedText()?.let { copyToClipboard(context, it) }
                        selectionState.clear()
                    }
                },
                onClearSelection = selectionState::clear,
            )

            // Not an overlay: in the flow, so opening find pushes the document down.
            AnimatedVisibility(
                visible = find.open,
                enter = expandVertically(tween(FIND_ANIMATION_MS)) + fadeIn(tween(FIND_ANIMATION_MS)),
                exit = shrinkVertically(tween(FIND_ANIMATION_MS)) + fadeOut(tween(FIND_ANIMATION_MS)),
            ) {
                FindInFileBar(
                    query = find.query,
                    matchCount = find.count,
                    currentMatch = find.position.coerceAtLeast(0),
                    // Markdown has no step target, so the bar shows the total and greys the arrows.
                    canNavigate = find.navigable,
                    onQueryChange = viewModel::updateFindQuery,
                    onNext = viewModel::nextMatch,
                    onPrevious = viewModel::previousMatch,
                    onClose = closeFind,
                    // End inset smaller: the close button's own padding brings its glyph to 16dp.
                    modifier = Modifier.padding(start = ScreenPadding, end = 4.dp, bottom = 8.dp),
                )
            }

            // Clipped so a zoom preview can never paint over the header or the find bar above.
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                when (val content = state.content) {
                    ViewerContent.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is ViewerContent.Pdf -> PdfViewer(
                        source = content.source,
                        aspectRatios = content.aspectRatios,
                        zoom = zoom,
                        selectionState = selectionState,
                        pageHits = find.hits,
                        pageMatches = find.pageMatches,
                        activePage = find.activeIndex,
                        activeOccurrence = find.activeOccurrence,
                    )
                    is ViewerContent.Markdown -> MarkdownViewer(
                        markwon = content.markwon,
                        source = content.source,
                        text = content.text,
                        findQuery = find.query,
                        zoom = zoom,
                    )
                    is ViewerContent.PlainText -> PlainTextViewer(
                        chunks = content.chunks,
                        findQuery = find.query,
                        activeHit = find.activeIndex,
                        activeOccurrence = find.activeOccurrence,
                        zoom = zoom,
                    )
                    is ViewerContent.Office -> OfficeViewer(
                        blocks = content.blocks,
                        findQuery = find.query,
                        activeHit = find.activeIndex,
                        activeOccurrence = find.activeOccurrence,
                        zoom = zoom,
                    )
                    is ViewerContent.Failed -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = content.message.asString(),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        // The same outlined button as Home's Open file.
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.padding(top = 16.dp).heightIn(min = ControlHeight),
                        ) {
                            Text(stringResource(R.string.action_back))
                        }
                    }
                }

                // Not a snackbar: the grant stays gone until the user acts, and a toast leaves no way back.
                if (state.needsRegrant) {
                    RegrantBanner(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                        regrantFile.launch(DocumentFormat.pickerMimeTypes)
                    }
                }

                ZoomBadge(zoom, Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
        }
    }

    val infoDocument = state.document
    if (infoOpen && infoDocument != null) {
        // Both are read live: the provider's type, and the page count of the PDF already open.
        val mimeType by produceState<String?>(null, infoDocument) {
            value = viewModel.mimeType(infoDocument)
        }
        FileInfoSheet(
            document = infoDocument,
            pageCount = (state.content as? ViewerContent.Pdf)?.source?.pageCount,
            mimeType = mimeType,
            onDismiss = { infoOpen = false },
        )
    }
}

private const val FIND_ANIMATION_MS = 200

/** Flat title row like Home's; a selection swaps the overflow menu for Copy and dismiss. */
@Composable
private fun ViewerHeader(
    title: String,
    findAvailable: Boolean,
    menuOpen: Boolean,
    selectionActive: Boolean,
    showShare: Boolean,
    showSaveToLibrary: Boolean,
    savingToLibrary: Boolean,
    savedToLibrary: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onFind: () -> Unit,
    onInfo: () -> Unit,
    onShare: () -> Unit,
    onSaveToLibrary: () -> Unit,
    onCopySelection: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .semantics { heading() },
        )

        // A selection borrows the overflow slot; clearing it restores the bar exactly.
        if (selectionActive) {
            IconButton(onClick = onCopySelection) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.viewer_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClearSelection) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.viewer_selection_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (showShare) {
                IconButton(onClick = onShare) {
                    Icon(
                        painter = painterResource(R.drawable.ic_share),
                        contentDescription = stringResource(R.string.action_share),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(onClick = { onMenuOpenChange(true) }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.viewer_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_find_title)) },
                        enabled = findAvailable,
                        onClick = { onMenuOpenChange(false); onFind() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_info_title)) },
                        onClick = { onMenuOpenChange(false); onInfo() },
                    )
                    // Only for a handed-over document: library rows are already saved.
                    if (showSaveToLibrary) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_save_library)) },
                            enabled = !savingToLibrary && !savedToLibrary,
                            onClick = { onMenuOpenChange(false); onSaveToLibrary() },
                        )
                    }
                }
            }
        }
    }
}

/** Stays until acted on: until the re-pick, what the viewer shows is stale. */
@Composable
private fun RegrantBanner(modifier: Modifier = Modifier, onRegrant: () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.viewer_regrant_message),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRegrant) { Text(stringResource(R.string.viewer_regrant_action)) }
        }
    }
}

/** Readout only: shown while the scale changes, and for a beat after it stops. */
@Composable
private fun ZoomBadge(zoom: ZoomState, modifier: Modifier = Modifier) {
    // Held while the fingers are down, so a pinch that pauses keeps its readout.
    val visible by rememberLingerVisible(hold = { zoom.isZooming }) { zoom.effectiveScale }
    // Matches the scroll knob, alpha included.
    ReadoutBadge(
        text = zoom.percentLabel(),
        visible = visible,
        transformOrigin = TransformOrigin(1f, 0f),
        textStyle = MaterialTheme.typography.labelSmall,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = modifier,
    )
}
