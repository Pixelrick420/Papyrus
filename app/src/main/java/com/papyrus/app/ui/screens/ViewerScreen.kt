package com.papyrus.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papyrus.app.MainApplication
import com.papyrus.app.R
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.Highlight
import com.papyrus.app.data.LocationKind
import com.papyrus.app.data.OpenPersistableDocument
import com.papyrus.app.data.ReadingLocation
import com.papyrus.app.data.exportableRectsByPage
import com.papyrus.app.data.marksByPage
import com.papyrus.app.data.touchedBy
import com.papyrus.app.ui.AppViewModelProvider
import com.papyrus.app.ui.asString
import com.papyrus.app.ui.components.ControlHeight
import com.papyrus.app.ui.viewer.ARRIVAL_FADE_MILLIS
import com.papyrus.app.ui.viewer.BookmarksSheet
import com.papyrus.app.ui.viewer.EncodingDialog
import com.papyrus.app.ui.viewer.FileInfoSheet
import com.papyrus.app.ui.viewer.FindInFileBar
import com.papyrus.app.ui.viewer.GoToDialog
import com.papyrus.app.ui.viewer.LocalBookLinkOpener
import com.papyrus.app.ui.viewer.LocalLinkOpener
import com.papyrus.app.ui.viewer.MarkdownViewer
import com.papyrus.app.ui.viewer.OfficeViewer
import com.papyrus.app.ui.viewer.OutlineSheet
import com.papyrus.app.ui.viewer.PasswordDialog
import com.papyrus.app.ui.viewer.PdfAnnotationSheet
import com.papyrus.app.ui.viewer.PdfFormSheet
import com.papyrus.app.ui.viewer.PdfSelectionState
import com.papyrus.app.ui.viewer.PdfViewer
import com.papyrus.app.ui.viewer.PlainTextOptions
import com.papyrus.app.ui.viewer.PlainTextViewer
import com.papyrus.app.ui.viewer.PositionHooks
import com.papyrus.app.ui.viewer.REST_SCALE
import com.papyrus.app.ui.viewer.ReadoutBadge
import com.papyrus.app.ui.viewer.TtsControls
import com.papyrus.app.ui.viewer.TtsHighlight
import com.papyrus.app.ui.viewer.ViewerJump
import com.papyrus.app.ui.viewer.ZoomState
import com.papyrus.app.ui.viewer.copyToClipboard
import com.papyrus.app.ui.viewer.openLink
import com.papyrus.app.ui.viewer.rememberLingerVisible
import com.papyrus.app.ui.viewer.rememberZoomState
import com.papyrus.app.viewer.BookLink
import com.papyrus.app.viewer.PdfLinkTarget
import com.papyrus.app.viewer.locateLine
import com.papyrus.app.viewer.numberedLines
import com.papyrus.app.viewer.officeOutline
import kotlinx.coroutines.launch

private val ScreenPadding = 16.dp

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
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val tts by viewModel.tts.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // A fresh composition (a rotation, say) starts at the zoom the viewer last had, not at 1x.
    val zoom = rememberZoomState(initialScale = remember { viewModel.restoreTarget()?.zoom ?: REST_SCALE })
    // Re-read when a (re)load hands over a new request, not on every position change; the viewers apply it once.
    val restore = remember(state.restore) { viewModel.restoreTarget() }
    // The one scroll request of every format; a new object is a new jump, keyed on the content so a reload never replays the last one.
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
    // While this viewer is in the foreground the background indexer keeps out of its way.
    val readers = remember(context) { (context.applicationContext as MainApplication).readerPresence }
    LifecycleResumeEffect(readers) {
        readers.entered()
        onPauseOrDispose { readers.left() }
    }
    val snackbar = remember { SnackbarHostState() }
    val selectionScope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.action_share)
    val shareDocument: () -> Unit = {
        viewModel.share { send -> context.startActivity(Intent.createChooser(send, chooserTitle)) }
    }

    val followLink: (String) -> Unit = remember(context) {
        { url ->
            if (!openLink(context, url)) Toast.makeText(context, R.string.viewer_link_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val followBookLink: (BookLink) -> Unit = remember(state.content, context) {
        { link ->
            val target = (state.content as? ViewerContent.Office)?.bookLinks?.get(link)
            if (target == null) {
                Toast.makeText(context, R.string.viewer_link_failed, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.ttsReleaseScroll()
                jump = ViewerJump(ReadingLocation(LocationKind.BLOCK, target))
            }
        }
    }
    val followPdfLink: (PdfLinkTarget) -> Unit = remember(context) {
        { target ->
            when (target) {
                is PdfLinkTarget.External -> followLink(target.url)
                is PdfLinkTarget.Internal -> {
                    viewModel.ttsReleaseScroll()
                    jump = ViewerJump(ReadingLocation(LocationKind.PAGE, target.page), revealOnly = true)
                }
            }
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var encodingOpen by remember { mutableStateOf(false) }
    var outlineOpen by remember { mutableStateOf(false) }
    var goToOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var formOpen by remember { mutableStateOf(false) }
    var noteMark by remember { mutableStateOf<Highlight?>(null) }

    // A PDF's bookmarks are read off the main thread, so they arrive after the first page.
    val office = state.content as? ViewerContent.Office
    // A document's own table of contents (an EPUB's) wins; otherwise the outline is its headings.
    val officeEntries = remember(office) { office?.let { it.outline ?: officeOutline(it.blocks) }.orEmpty() }
    val isPdf = state.content is ViewerContent.Pdf
    val outline = if (isPdf) state.pdfOutline else officeEntries
    val pageCount = (state.content as? ViewerContent.Pdf)?.source?.pageCount ?: 0
    val plain = state.content as? ViewerContent.PlainText
    val lineCount = remember(plain) {
        plain?.let { numberedLines(it.lineCount, it.chunks.lastOrNull()?.endsWith('\n') == true) } ?: 0
    }
    val goToLines = lineCount >= MIN_GO_TO_LINES
    val goToAvailable = pageCount >= MIN_GO_TO_PAGES || goToLines

    // Held here (not in [PdfViewer]) so the header's Copy can reach it; keyed on the source.
    val selectionState = remember((state.content as? ViewerContent.Pdf)?.source) {
        PdfSelectionState(
            scope = selectionScope,
            loadText = viewModel::pageText,
            onNoText = { Toast.makeText(context, R.string.viewer_pdf_no_text, Toast.LENGTH_SHORT).show() },
            loadSelection = viewModel::platformSelection,
        )
    }
    // The selection takes over the header's overflow slot, so an open menu would pop up behind it.
    LaunchedEffect(selectionState.hasSelection) {
        if (selectionState.hasSelection) menuOpen = false
    }

    val pageHighlights = remember(highlights) { highlights.marksByPage() }
    // Only a plain highlight can be written into a PDF, so a document whose marks are all notes has
    // nothing to offer here.
    val exportableHighlights = remember(highlights) { highlights.exportableRectsByPage() }
    val highlightCopyAvailable = state.highlightsAvailable &&
        (state.content as? ViewerContent.Pdf)?.source?.canAnnotate == true &&
        exportableHighlights.isNotEmpty()
    // Remove is offered only when the selection sits on a stored highlight; worked out once the selected pages have their text.
    val touchedHighlights by produceState<List<Highlight>>(emptyList(), selectionState.selection, highlights) {
        value = if (highlights.isEmpty()) emptyList() else highlights.touchedBy(selectionState.selectedRects())
    }

    val find = state.find
    val arrival = state.arrival
    // The mark arrives opaque, holds while the ViewModel waits, then this fades it out.
    val highlightAlpha by animateFloatAsState(
        targetValue = if (arrival?.fading == true) 0f else 1f,
        animationSpec = tween(ARRIVAL_FADE_MILLIS.toInt()),
        label = "arrivalAlpha",
    )
    // Markdown has no hit to reveal, so its arrival is one character offset to scroll to.
    LaunchedEffect(arrival?.offset) {
        arrival?.offset?.let { jump = ViewerJump(ReadingLocation(LocationKind.CHAR, it)) }
    }
    val findAvailable = when (state.content) {
        is ViewerContent.Pdf, is ViewerContent.PlainText, is ViewerContent.Office, is ViewerContent.Markdown -> true
        else -> false
    }
    // A bookmark needs a place on screen (the same surfaces as find) and a library row to hang from, which a
    // handed-over document lacks until it is saved. Ctrl+D and the menu both ask this.
    val bookmarkableContent = findAvailable
    val bookmarksAvailable = bookmarkableContent && state.bookmarksAvailable
    val formAvailable = state.formFields.isNotEmpty()

    val readAloudAvailable = findAvailable &&
        state.document?.format != DocumentFormat.CSV &&
        state.speakableText != false
    val ttsDocumentId = state.document?.id?.toString().orEmpty()
    val ttsActive = tts.documentId == ttsDocumentId && ttsDocumentId.isNotEmpty() &&
        (tts.isPlaying || tts.isPaused || tts.hasError)
    // What the viewers mark: the sentence and word being spoken, whether or not the page is locked to
    // them. Found again only when the voice moves, not on every recomposition.
    val spoken = remember(ttsActive, tts.anchor, tts.progress) {
        if (ttsActive && tts.anchor >= 0) TtsHighlight(tts.anchor, tts.progress) else null
    }
    // Locked, the page follows the voice and cannot be scrolled. A failed read holds nothing.
    val voiceLocksScroll = ttsActive && tts.followsVoice
    // Opening find is the reader going looking, so the voice stops holding the page; the same goes for
    // the outline, go to, bookmarks and links, which release it before they jump.
    val openFind: () -> Unit = {
        viewModel.ttsReleaseScroll()
        viewModel.openFind()
    }

    // The charset applies only to text decoded from bytes: plain text, code, Markdown and CSV.
    // PDF and the other Office files have none.
    val encodingAvailable = state.content.hasEncoding

    val closeFind: () -> Unit = {
        focusManager.clearFocus()
        viewModel.closeFind()
    }

    BackHandler(enabled = find.open, onBack = closeFind)

    // SAF grants have no runtime dialog, so re-access means a re-pick through the picker.
    val regrantFile = rememberLauncherForActivityResult(OpenPersistableDocument()) { uri ->
        if (uri != null) viewModel.regrant(uri) else viewModel.consumeRegrantRequest()
    }

    // The read-aloud notification is optional: asked once, at the first Read aloud on Android 13+,
    // and the voice starts either way - a refusal only leaves out the system notification.
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.startReadAloud() }

    // Only successful copy creation closes the sheet, preserving error messages on cancellation.
    val formCopyName = pdfCopyFileName(state.document?.title, stringResource(R.string.viewer_form_copy_name))
    val saveFormCopy = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PDF_MIME_TYPE),
    ) { uri ->
        if (uri != null) {
            formOpen = false
            viewModel.saveFormCopy(uri)
        }
    }
    val highlightCopyName =
        pdfCopyFileName(state.document?.title, stringResource(R.string.viewer_highlight_copy_name))
    val saveHighlightCopy = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PDF_MIME_TYPE),
    ) { uri -> if (uri != null) viewModel.saveHighlightCopy(uri) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.asString(context))
            viewModel.consumeMessage()
        }
    }

    // An added bookmark offers Undo; anything else a bookmark action has to say is a plain message. Each is
    // shown from its own coroutine so one waiting out its time never holds up the next event.
    val bookmarkScope = rememberCoroutineScope()
    val bookmarkAddedText = stringResource(R.string.bookmark_added)
    val undoText = stringResource(R.string.action_undo)
    LaunchedEffect(viewModel) {
        viewModel.bookmarkEvents.collect { event ->
            bookmarkScope.launch {
                when (event) {
                    is BookmarkEvent.Added -> {
                        snackbar.currentSnackbarData?.dismiss()
                        val result = snackbar.showSnackbar(
                            message = bookmarkAddedText,
                            actionLabel = undoText,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoAddBookmark(event.bookmark)
                    }
                    is BookmarkEvent.Notice -> {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(event.text.asString(context))
                    }
                }
            }
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
                        openFind()
                        true
                    } else if (event.key == Key.G && event.isCtrlPressed && goToAvailable) {
                        goToOpen = true
                        true
                    } else if (event.key == Key.D && event.isCtrlPressed && bookmarksAvailable) {
                        viewModel.addBookmark()
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
                goToLines = goToLines,
                bookmarkableContent = bookmarkableContent,
                bookmarksAvailable = bookmarksAvailable,
                formAvailable = formAvailable,
                highlightCopyAvailable = highlightCopyAvailable,
                encodingAvailable = encodingAvailable,
                menuOpen = menuOpen,
                selectionActive = selectionState.hasSelection,
                highlightAvailable = state.highlightsAvailable,
                canRemoveHighlight = touchedHighlights.isNotEmpty(),
                showShare = state.document != null,
                showMenu = !state.isCrashReport,
                showSaveToLibrary = state.canSaveToLibrary,
                savingToLibrary = state.savingToLibrary,
                savedToLibrary = state.savedToLibrary,
                readAloudAvailable = readAloudAvailable,
                readAloudActive = ttsActive,
                onMenuOpenChange = { menuOpen = it },
                onBack = onBack,
                onFind = openFind,
                onOutline = { outlineOpen = true },
                onGoTo = { goToOpen = true },
                onAddBookmark = viewModel::addBookmark,
                onBookmarks = { bookmarksOpen = true },
                onForm = { formOpen = true },
                onSaveHighlightCopy = { saveHighlightCopy.launch(highlightCopyName) },
                onEncoding = { encodingOpen = true },
                onInfo = { infoOpen = true },
                onShare = shareDocument,
                onSaveToLibrary = viewModel::saveToLibrary,
                onReadAloud = {
                    if (ttsActive) {
                        viewModel.stopReadAloud()
                    } else if (viewModel.needsNotificationPrompt()) {
                        viewModel.markNotificationPromptShown()
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.startReadAloud()
                    }
                },
                onCopySelection = {
                    selectionScope.launch {
                        selectionState.selectedText()?.let { copyToClipboard(context, it) }
                        selectionState.clear()
                    }
                },
                onHighlight = {
                    selectionScope.launch {
                        viewModel.addHighlights(selectionState.selectedRects())
                        selectionState.clear()
                    }
                },
                onRemoveHighlight = {
                    viewModel.removeHighlights(touchedHighlights.map { it.id })
                    selectionState.clear()
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

            if (ttsActive) {
                TtsControls(
                    state = tts,
                    onPlayPause = viewModel::ttsPlayPause,
                    onSkipBack = viewModel::ttsSkipBack,
                    onSkipForward = viewModel::ttsSkipForward,
                    onClose = viewModel::stopReadAloud,
                    onSpeedChange = viewModel::ttsSetSpeed,
                    onToggleScrollLock = viewModel::ttsToggleScrollLock,
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
                CompositionLocalProvider(LocalLinkOpener provides followLink, LocalBookLinkOpener provides followBookLink) {
                    when (val content = state.content) {
                        ViewerContent.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        is ViewerContent.Pdf -> PdfViewer(
                            source = content.source,
                            aspectRatios = content.aspectRatios,
                            zoom = zoom,
                            selectionState = selectionState,
                            nightMode = settings.nightMode,
                            pageHits = find.hits,
                            pageMatches = find.pageMatches,
                            highlights = pageHighlights,
                            onMark = { noteMark = it },
                            activePage = find.activeIndex,
                            activeOccurrence = find.activeOccurrence,
                            loadLinks = viewModel::pageLinks,
                            onLinkTarget = followPdfLink,
                            position = position,
                            speaking = spoken,
                            ttsLocked = voiceLocksScroll,
                            highlightAlpha = highlightAlpha,
                            onlyActive = arrival != null,
                        )
                        is ViewerContent.Markdown -> MarkdownViewer(
                            markwon = content.markwon,
                            source = content.source,
                            text = content.text,
                            latexFactory = content.latexFactory,
                            // Empty during an arrival: the mark below is the only one it may light.
                            findQuery = if (arrival != null) "" else find.query,
                            zoom = zoom,
                            position = position,
                            speaking = spoken,
                            ttsLocked = voiceLocksScroll,
                            arrival = arrival,
                            highlightAlpha = highlightAlpha,
                        )
                        is ViewerContent.PlainText -> {
                            // Keyed on the fields, not the content: comparing two contents would walk every chunk.
                            val options = remember(
                                content.language, content.lexStates, content.lineCount, content.maxLineColumns, content.truncated,
                                content.prose, settings.wrapLines, settings.hyphenation, settings.showLineNumbers,
                                settings.syntaxHighlighting,
                            ) {
                                PlainTextOptions(
                                    wrapLines = settings.wrapLines,
                                    hyphenate = settings.hyphenation && content.prose,
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
                                speaking = spoken,
                                ttsLocked = voiceLocksScroll,
                                highlightAlpha = highlightAlpha,
                                onlyActive = arrival != null,
                            )
                        }
                        is ViewerContent.Office -> OfficeViewer(
                            blocks = content.blocks,
                            findQuery = find.query,
                            activeHit = find.activeIndex,
                            activeOccurrence = find.activeOccurrence,
                            zoom = zoom,
                            position = position,
                            speaking = spoken,
                            ttsLocked = voiceLocksScroll,
                            incomplete = content.incomplete,
                            highlightAlpha = highlightAlpha,
                            onlyActive = arrival != null,
                            direction = content.direction,
                            pageStarts = content.pageStarts,
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
                viewModel.ttsReleaseScroll()
                jump = ViewerJump(ReadingLocation(kind, entry.index))
                outlineOpen = false
            },
            onDismiss = { outlineOpen = false },
        )
    }

    if (goToOpen && goToLines) {
        GoToDialog(
            title = stringResource(R.string.viewer_goto_line_title),
            label = stringResource(R.string.viewer_goto_line_label),
            error = stringResource(R.string.viewer_goto_line_error, lineCount),
            max = lineCount,
            onGo = { line ->
                // The chunk the line is in, and how far down it: exact where lines do not wrap.
                locateLine(line, lineCount)?.let { target ->
                    viewModel.ttsReleaseScroll()
                    jump = ViewerJump(ReadingLocation(LocationKind.CHUNK, target.chunk, target.fraction))
                }
                goToOpen = false
            },
            onDismiss = { goToOpen = false },
        )
    } else if (goToOpen && goToAvailable) {
        GoToDialog(
            title = stringResource(R.string.viewer_goto_page_title),
            label = stringResource(R.string.viewer_goto_page_label),
            error = stringResource(R.string.viewer_goto_page_error, pageCount),
            max = pageCount,
            onGo = { page ->
                // The reader counts from 1; the viewer from 0.
                viewModel.ttsReleaseScroll()
                jump = ViewerJump(ReadingLocation(LocationKind.PAGE, page - 1))
                goToOpen = false
            },
            onDismiss = { goToOpen = false },
        )
    }

    if (bookmarksOpen && bookmarksAvailable) {
        BookmarksSheet(
            bookmarks = bookmarks,
            unitCount = state.content.unitCount(),
            onJump = { bookmark ->
                // The same jump as the outline's; a bookmark whose place has gone is clamped by the viewer.
                viewModel.ttsReleaseScroll()
                jump = ViewerJump(bookmark.location)
                bookmarksOpen = false
            },
            onRename = viewModel::renameBookmark,
            onDelete = viewModel::deleteBookmark,
            onUndoDelete = viewModel::restoreBookmark,
            onDismiss = { bookmarksOpen = false },
        )
    }

    if (formOpen && formAvailable) {
        PdfFormSheet(
            fields = state.formFields,
            onSetText = viewModel::setFormText,
            onChoose = viewModel::selectFormChoice,
            onToggle = viewModel::toggleFormField,
            onSaveCopy = { saveFormCopy.launch(formCopyName) },
            onDismiss = { formOpen = false },
        )
    }

    noteMark?.let { mark ->
        PdfAnnotationSheet(
            mark = mark,
            onSave = { note ->
                viewModel.setHighlightNote(mark.id, note)
                noteMark = null
            },
            onDelete = {
                viewModel.removeHighlights(listOf(mark.id))
                noteMark = null
            },
            onDismiss = { noteMark = null },
        )
    }
}

private const val FIND_ANIMATION_MS = 200

private const val PDF_MIME_TYPE = "application/pdf"

private fun pdfCopyFileName(title: String?, fallback: String): String {
    val base = title?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
}

private const val MIN_GO_TO_PAGES = 6
private const val MIN_GO_TO_LINES = 100

@Composable
private fun ViewerHeader(
    title: String,
    findAvailable: Boolean,
    outlineAvailable: Boolean,
    goToAvailable: Boolean,
    goToLines: Boolean,
    bookmarkableContent: Boolean,
    bookmarksAvailable: Boolean,
    formAvailable: Boolean,
    highlightCopyAvailable: Boolean,
    encodingAvailable: Boolean,
    menuOpen: Boolean,
    selectionActive: Boolean,
    highlightAvailable: Boolean,
    canRemoveHighlight: Boolean,
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
    onAddBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onForm: () -> Unit,
    onSaveHighlightCopy: () -> Unit,
    onEncoding: () -> Unit,
    onInfo: () -> Unit,
    onShare: () -> Unit,
    onSaveToLibrary: () -> Unit,
    onReadAloud: () -> Unit,
    onCopySelection: () -> Unit,
    onHighlight: () -> Unit,
    onRemoveHighlight: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
    showMenu: Boolean = true,
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

        if (selectionActive) {
            IconButton(onClick = onCopySelection) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.viewer_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (highlightAvailable) {
                IconButton(onClick = onHighlight) {
                    Icon(
                        painter = painterResource(R.drawable.ic_highlight),
                        contentDescription = stringResource(R.string.viewer_highlight),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canRemoveHighlight) {
                    IconButton(onClick = onRemoveHighlight) {
                        Icon(
                            painter = painterResource(R.drawable.ic_remove),
                            contentDescription = stringResource(R.string.viewer_highlight_remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
            if (showMenu) {
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
                        if (readAloudAvailable || readAloudActive) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (readAloudActive) R.string.viewer_read_aloud_stop else R.string.viewer_read_aloud,
                                        ),
                                    )
                                },
                                onClick = { onMenuOpenChange(false); onReadAloud() },
                            )
                        }
                        if (outlineAvailable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_outline_title)) },
                                onClick = { onMenuOpenChange(false); onOutline() },
                            )
                        }
                        if (formAvailable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_form_title)) },
                                onClick = { onMenuOpenChange(false); onForm() },
                            )
                        }
                        if (highlightCopyAvailable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_save_copy_with_highlights)) },
                                onClick = { onMenuOpenChange(false); onSaveHighlightCopy() },
                            )
                        }
                        if (goToAvailable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (goToLines) R.string.viewer_goto_line_title else R.string.viewer_goto_page_title)) },
                                onClick = { onMenuOpenChange(false); onGoTo() },
                            )
                        }
                        if (bookmarkableContent && bookmarksAvailable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bookmark_add)) },
                                onClick = { onMenuOpenChange(false); onAddBookmark() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bookmarks_title)) },
                                onClick = { onMenuOpenChange(false); onBookmarks() },
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
}

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

@Composable
private fun ZoomBadge(zoom: ZoomState, modifier: Modifier = Modifier) {
    // Held while the fingers are down, so a pinch that pauses keeps its readout.
    val visible by rememberLingerVisible(hold = { zoom.isZooming }) { zoom.effectiveScale }
    ReadoutBadge(
        text = zoom.percentLabel(),
        visible = visible,
        transformOrigin = TransformOrigin(1f, 0f),
        textStyle = MaterialTheme.typography.labelSmall,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = modifier,
    )
}
