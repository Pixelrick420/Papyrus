package com.papyrus.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papyrus.app.R
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.OpenPersistableDocument
import com.papyrus.app.ui.AppViewModelProvider
import com.papyrus.app.ui.asString
import com.papyrus.app.ui.viewer.FileInfoSheet
import com.papyrus.app.ui.viewer.FindInFileBar
import com.papyrus.app.ui.viewer.MarkdownViewer
import com.papyrus.app.ui.viewer.OfficeViewer
import com.papyrus.app.ui.viewer.PdfViewer
import com.papyrus.app.ui.viewer.PlainTextViewer
import com.papyrus.app.ui.viewer.rememberZoomState

/** Find, zoom and overflow state live here so a format switch does not reset them. */
@OptIn(ExperimentalMaterial3Api::class)
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

    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }

    val find = state.find
    // Only surfaces with searchable content; a failed load has neither.
    val findAvailable = when (state.content) {
        is ViewerContent.Pdf, is ViewerContent.PlainText, is ViewerContent.Office, is ViewerContent.Markdown -> true
        else -> false
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.document?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (find.open) {
                        IconButton(onClick = { focusManager.clearFocus(); viewModel.closeFind() }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.viewer_find_close))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.viewer_menu))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_find_title)) },
                                enabled = findAvailable,
                                onClick = { menuOpen = false; viewModel.openFind() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_info_title)) },
                                onClick = { menuOpen = false; infoOpen = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Preview key handling so Ctrl+F wins over a focused text field.
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
                when (val content = state.content) {
                    ViewerContent.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is ViewerContent.Pdf -> PdfViewer(
                        source = content.source,
                        aspectRatios = content.aspectRatios,
                        zoom = zoom,
                        pageHits = find.hits,
                        pageRects = find.pageRects,
                        activePage = find.activeIndex,
                    )
                    is ViewerContent.Markdown -> MarkdownViewer(
                        markwon = content.markwon,
                        text = content.text,
                        findQuery = find.query,
                        zoom = zoom,
                    )
                    is ViewerContent.PlainText -> PlainTextViewer(
                        chunks = content.chunks,
                        findQuery = find.query,
                        activeHit = find.activeIndex,
                        zoom = zoom,
                    )
                    is ViewerContent.Office -> OfficeViewer(
                        blocks = content.blocks,
                        findQuery = find.query,
                        activeHit = find.activeIndex,
                        zoom = zoom,
                    )
                    is ViewerContent.Failed -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(content.message.asString(), textAlign = TextAlign.Center)
                        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
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

                // Top-anchored: the activity uses adjustNothing, so no IME inset handling is needed.
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (find.open) {
                        FindInFileBar(
                            query = find.query,
                            matchCount = find.count,
                            currentMatch = find.position.coerceAtLeast(0),
                            // Markdown has no step target, so the bar shows the total and greys the arrows.
                            canNavigate = find.navigable,
                            onQueryChange = viewModel::updateFindQuery,
                            onNext = viewModel::nextMatch,
                            onPrevious = viewModel::previousMatch,
                            onClose = { focusManager.clearFocus(); viewModel.closeFind() },
                            // Caps width so tablets get a corner card; portrait phones are narrower and fill it.
                            modifier = Modifier.widthIn(max = FIND_BAR_MAX_WIDTH),
                        )
                    }
                    ZoomBadge(zoom)
                }
            }
        }
    }

    if (infoOpen && state.document != null) {
        FileInfoSheet(document = state.document!!, onDismiss = { infoOpen = false })
    }
}

private val FIND_BAR_MAX_WIDTH = 420.dp

/** Stays until acted on: until the re-pick, what the viewer shows is stale. */
@Composable
private fun RegrantBanner(modifier: Modifier = Modifier, onRegrant: () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
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

/** Readout only: pinch and double-tap are the interaction; the scale has no step size. */
@Composable
private fun ZoomBadge(zoom: com.papyrus.app.ui.viewer.ZoomState, modifier: Modifier = Modifier) {
    if (!zoom.isZoomed) return
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
    ) {
        Text(
            zoom.percentLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
