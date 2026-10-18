package com.papyrus.app.ui.screens

import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papyrus.app.R
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.LocationKind
import com.papyrus.app.data.OpenPersistableDocument
import com.papyrus.app.data.ReadingLocation
import com.papyrus.app.ui.AppViewModelProvider
import com.papyrus.app.ui.asString
import com.papyrus.app.ui.components.ControlHeight
import com.papyrus.app.ui.viewer.EncodingDialog
import com.papyrus.app.ui.viewer.FileInfoSheet
import com.papyrus.app.ui.viewer.FindInFileBar
import com.papyrus.app.ui.viewer.GoToDialog
import com.papyrus.app.ui.viewer.LocalLinkOpener
import com.papyrus.app.ui.viewer.MarkdownViewer
import com.papyrus.app.ui.viewer.OfficeViewer
import com.papyrus.app.ui.viewer.OutlineSheet
import com.papyrus.app.ui.viewer.PasswordDialog
import com.papyrus.app.ui.viewer.PdfSelectionState
import com.papyrus.app.ui.viewer.PdfViewer
import com.papyrus.app.ui.viewer.PlainTextOptions
import com.papyrus.app.ui.viewer.PlainTextViewer
import com.papyrus.app.ui.viewer.PositionHooks
import com.papyrus.app.ui.viewer.REST_SCALE
import com.papyrus.app.ui.viewer.ReadoutBadge
import com.papyrus.app.ui.viewer.TtsControls
import com.papyrus.app.ui.viewer.ViewerJump
import com.papyrus.app.ui.viewer.ZoomState
import com.papyrus.app.ui.viewer.copyToClipboard
import com.papyrus.app.ui.viewer.openLink
import com.papyrus.app.ui.viewer.rememberLingerVisible
import com.papyrus.app.ui.viewer.rememberZoomState
import com.papyrus.app.viewer.PdfLinkTarget
import com.papyrus.app.viewer.officeOutline
import kotlinx.coroutines.launch

/** Same inset as Home, so the find pill lines up with the Home search pill. */
private val ScreenPadding = 16.dp

/** Inset from the left, top and right edges so a highlighted match never sits flush against them. */
private val ViewerContentMargin = 12.dp

/** Find, zoom and overflow state live here, so a format switch does not reset them. */
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val tts by viewModel.tts.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // A fresh composition (a rotation, say) starts at the zoom the viewer last had, not at 1x.
    val zoom = rememberZoomState(initialScale = remember { viewModel.restoreTarget()?.zoom ?: REST_SCALE })
    // Re-read when a (re)load hands over a new request, not on every position change; the viewers apply it once.
    val restore = remember(state.restore) { viewModel.restoreTarget() }
    // The one scroll request of every format (links, outline, go to page): a new object is a new jump.
    // Keyed on the content so a reload or an encoding change never replays the last one.
    var jump by remember(state.content) { mutableStateOf<ViewerJump?>(null) }
    val position = remember(restore, viewModel, jump) {
        PositionHooks(restore, viewModel::onRestored, viewModel::onPosition, jump)
    }
    // The document may arrive after this screen is composed; its zoom is set before its content shows.
    LaunchedEffect(restore) {
        restore?.zoom?.let { if (it != zoom.committedScale) zoom.set(it) }
    }
    // The process can be killed once the app is in the background, and onCleared is not guaranteed then.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flushPosition() }
    val snackbar = remember { SnackbarHostState() }
    val selectionScope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.action_share)
    val shareDocument: () -> Unit = {
        viewModel.share { send -> context.startActivity(Intent.createChooser(send, chooserTitle)) }
    }

    // One opening path for every surface in this screen, so an unopenable link reports the same way.
    val followLink: (String) -> Unit = remember(context) {
        { url ->
            if (!openLink(context, url)) Toast.makeText(context, R.string.viewer_link_failed, Toast.LENGTH_SHORT).show()
        }
    }
    // A link to another page of the PDF only reveals it: nothing moves if it is already on screen.
    val followPdfLink: (PdfLinkTarget) -> Unit = remember(context) {
        { target ->
            when (target) {
                is PdfLinkTarget.External -> followLink(target.url)
                is PdfLinkTarget.Internal -> jump = ViewerJump(ReadingLocation(LocationKind.PAGE, target.page), revealOnly = true)
            }
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var encodingOpen by remember { mutableStateOf(false) }
    var outlineOpen by remember { mutableStateOf(false) }
    var goToOpen by remember { mutableStateOf(false) }

    // Only a document with an outline has the menu item: an Office document that used heading styles, or a
    // PDF whose bookmarks have been read (off the main thread, so they arrive after the first page).
    val officeBlocks = (state.content as? ViewerContent.Office)?.blocks
    val officeEntries = remember(officeBlocks) { officeBlocks?.let { officeOutline(it) }.orEmpty() }
    val isPdf = state.content is ViewerContent.Pdf
    val outline = if (isPdf) state.pdfOutline else officeEntries
    // Go to page needs enough pages to be worth numbering; the menu item and Ctrl+G both ask this.
    val pageCount = (state.content as? ViewerContent.Pdf)?.source?.pageCount ?: 0
    val goToAvailable = pageCount >= MIN_GO_TO_PAGES

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

    // Read aloud is offered whenever find is (the same speakable formats), and the bar shows only
    // while *this* document is the one being read. An engine failure counts, so the reason stays on
    // screen and the bar's close button can clear it.
    val readAloudAvailable = findAvailable
    val ttsDocumentId = state.document?.id?.toString().orEmpty()
    val ttsActive = tts.documentId == ttsDocumentId && ttsDocumentId.isNotEmpty() &&
        (tts.isPlaying || tts.isPaused || tts.hasError)
    // PDF follow-along reuses the page-jump machinery: each new spoken page reveals like a link would.
    LaunchedEffect(tts.anchor, ttsActive, isPdf) {
        if (ttsActive && isPdf && tts.anchor >= 0) pageJump = PdfPageJump(tts.anchor)
    }

    // The charset applies only to text decoded from bytes: plain text, code and Markdown.
    // PDF and Office files have none.
    val encodingAvailable = state.content is ViewerContent.PlainText || state.content is ViewerContent.Markdown

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
        // In landscape, stepping through matches hides a docked keyboard so the whole page shows.
        // A floating keyboard reports no inset, so this stays false and it is left open.
        val dismissKeyboardOnStep = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            WindowInsets.ime.getBottom(LocalDensity.current) > 0

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Consuming the Scaffold padding first stops the navigation-bar inset being counted twice.
                .consumeWindowInsets(padding)
                // The keyboard takes its height out of the viewer, so a match scrolled into view and
                // the last page's bottom edge stay above it rather than being covered.
                .imePadding()
                // Ctrl+F wins over a focused field; on the column so it also covers the find field.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Ctrl only: no connected Android keyboard emits Meta, so it is untestable.
                    if (event.key == Key.F && event.isCtrlPressed && findAvailable) {
                        viewModel.openFind()
                        true
                    } else if (event.key == Key.G && event.isCtrlPressed && goToAvailable) {
                        goToOpen = true
                        true
                    } else {
                        false
                    }
                },
        ) {
            ViewerHeader(
                title = state.document?.title.orEmpty(),
                findAvailable = findAvailable,
                outlineAvailable = outline.isNotEmpty(),
                goToAvailable = goToAvailable,
                encodingAvailable = encodingAvailable,
                menuOpen = menuOpen,
                selectionActive = selectionState.hasSelection,
                showShare = state.document != null,
                showSaveToLibrary = state.canSaveToLibrary,
                savingToLibrary = state.savingToLibrary,
                savedToLibrary = state.savedToLibrary,
                readAloudAvailable = readAloudAvailable,
                readAloudActive = ttsActive,
                onMenuOpenChange = { menuOpen = it },
                onBack = onBack,
                onFind = viewModel::openFind,
                onOutline = { outlineOpen = true },
                onGoTo = { goToOpen = true },
                onEncoding = { encodingOpen = true },
                onInfo = { infoOpen = true },
                onShare = shareDocument,
                onSaveToLibrary = viewModel::saveToLibrary,
                onReadAloud = { if (ttsActive) viewModel.stopReadAloud() else viewModel.startReadAloud() },
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
                    dismissKeyboardOnStep = dismissKeyboardOnStep,
                    // End inset smaller: the close button's own padding brings its glyph to 16dp.
                    modifier = Modifier.padding(start = ScreenPadding, end = 4.dp, bottom = 8.dp),
                )
            }

            // In the flow like find, so opening it pushes the document down rather than covering it.
            if (ttsActive) {
                TtsControls(
                    state = tts,
                    onPlayPause = viewModel::ttsPlayPause,
                    onSkipBack = viewModel::ttsSkipBack,
                    onSkipForward = viewModel::ttsSkipForward,
                    onClose = viewModel::stopReadAloud,
                    onSpeedChange = viewModel::ttsSetSpeed,
                    // Same insets as the find bar, so the two stack as one column: the close button's
                    // own padding brings its glyph to 16dp, matching find's close.
                    modifier = Modifier.padding(start = ScreenPadding, end = 4.dp, bottom = 8.dp),
                )
            }

            // Inset from the left, top and right; the bottom is the keyboard inset above. Clipped so a
            // zoom preview can never paint over the header or the find bar.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = ViewerContentMargin, top = ViewerContentMargin, end = ViewerContentMargin)
                    .clipToBounds(),
            ) {
                CompositionLocalProvider(LocalLinkOpener provides followLink) {
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
                            loadLinks = viewModel::pageLinks,
                            onLinkTarget = followPdfLink,
                            position = position,
                        )
                        is ViewerContent.Markdown -> MarkdownViewer(
                            markwon = content.markwon,
                            source = content.source,
                            text = content.text,
                            findQuery = find.query,
                            zoom = zoom,
                            position = position,
                            ttsOffset = tts.anchor.takeIf { ttsActive && it >= 0 },
                        )
                        is ViewerContent.PlainText -> {
                            // Keyed on the fields, not the content: comparing two contents would walk every chunk.
                            val options = remember(
                                content.language, content.lexStates, content.lineCount, content.maxLineColumns, content.truncated,
                                settings.wrapLines, settings.showLineNumbers, settings.syntaxHighlighting,
                            ) {
                                PlainTextOptions(
                                    wrapLines = settings.wrapLines,
                                    showLineNumbers = settings.showLineNumbers,
                                    language = content.language.takeIf { settings.syntaxHighlighting },
                                    lexStates = content.lexStates,
                                    lineCount = content.lineCount,
                                    maxLineColumns = content.maxLineColumns,
                                    truncated = content.truncated,
                                )
                            }
                            PlainTextViewer(
                                chunks = content.chunks,
                                findQuery = find.query,
                                activeHit = find.activeIndex,
                                activeOccurrence = find.activeOccurrence,
                                zoom = zoom,
                                options = options,
                                position = position,
                                ttsChunk = tts.anchor.takeIf { ttsActive && it >= 0 },
                            )
                        }
                        is ViewerContent.Office -> OfficeViewer(
                            blocks = content.blocks,
                            findQuery = find.query,
                            activeHit = find.activeIndex,
                            activeOccurrence = find.activeOccurrence,
                            zoom = zoom,
                            position = position,
                            ttsBlock = tts.anchor.takeIf { ttsActive && it >= 0 },
                        )
                        // The dialog covers the page; only an in-flight retry is worth showing behind it.
                        is ViewerContent.NeedsPassword ->
                            if (content.checking) CircularProgressIndicator(Modifier.align(Alignment.Center))
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

    (state.content as? ViewerContent.NeedsPassword)?.let { prompt ->
        PasswordDialog(
            incorrect = prompt.incorrect,
            checking = prompt.checking,
            onUnlock = viewModel::submitPassword,
            onCancel = viewModel::cancelPassword,
        )
    }

    if (encodingOpen && encodingAvailable) {
        EncodingDialog(
            override = state.encodingOverride,
            onSelect = { charsetName ->
                viewModel.setEncoding(charsetName)
                encodingOpen = false
            },
            onDismiss = { encodingOpen = false },
        )
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

    if (outlineOpen && outline.isNotEmpty()) {
        OutlineSheet(
            entries = outline,
            collapsible = isPdf,
            onSelect = { entry ->
                // An outline entry is a page of a PDF and a block of an Office document: the same jump, in the
                // location kind of the format.
                val kind = if (isPdf) LocationKind.PAGE else LocationKind.BLOCK
                jump = ViewerJump(ReadingLocation(kind, entry.index))
                outlineOpen = false
            },
            onDismiss = { outlineOpen = false },
        )
    }

    if (goToOpen && goToAvailable) {
        GoToDialog(
            title = stringResource(R.string.viewer_goto_page_title),
            label = stringResource(R.string.viewer_goto_page_label),
            error = stringResource(R.string.viewer_goto_page_error, pageCount),
            max = pageCount,
            onGo = { page ->
                // The reader counts from 1; the viewer from 0.
                jump = ViewerJump(ReadingLocation(LocationKind.PAGE, page - 1))
                goToOpen = false
            },
            onDismiss = { goToOpen = false },
        )
    }
}

private const val FIND_ANIMATION_MS = 200

/** Below this many pages the page list is short enough to scroll, so Go to page stays out of the menu. */
private const val MIN_GO_TO_PAGES = 6

/** Flat title row like Home's; a selection swaps the overflow menu for Copy and dismiss. */
@Composable
private fun ViewerHeader(
    title: String,
    findAvailable: Boolean,
    outlineAvailable: Boolean,
    goToAvailable: Boolean,
    encodingAvailable: Boolean,
    menuOpen: Boolean,
    selectionActive: Boolean,
    showShare: Boolean,
    showSaveToLibrary: Boolean,
    savingToLibrary: Boolean,
    savedToLibrary: Boolean,
    readAloudAvailable: Boolean,
    readAloudActive: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onFind: () -> Unit,
    onOutline: () -> Unit,
    onGoTo: () -> Unit,
    onEncoding: () -> Unit,
    onInfo: () -> Unit,
    onShare: () -> Unit,
    onSaveToLibrary: () -> Unit,
    onReadAloud: () -> Unit,
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
                    // Read aloud needs the same speakable formats find does; active always shows the stop label.
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (readAloudActive) R.string.viewer_read_aloud_stop else R.string.viewer_read_aloud,
                                ),
                            )
                        },
                        enabled = readAloudAvailable || readAloudActive,
                        onClick = { onMenuOpenChange(false); onReadAloud() },
                    )
                    if (outlineAvailable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_outline_title)) },
                            onClick = { onMenuOpenChange(false); onOutline() },
                        )
                    }
                    // Only for a PDF long enough to number: [MIN_GO_TO_PAGES] or more.
                    if (goToAvailable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_goto_page_title)) },
                            onClick = { onMenuOpenChange(false); onGoTo() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_info_title)) },
                        onClick = { onMenuOpenChange(false); onInfo() },
                    )
                    if (encodingAvailable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_encoding_title)) },
                            onClick = { onMenuOpenChange(false); onEncoding() },
                        )
                    }
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
