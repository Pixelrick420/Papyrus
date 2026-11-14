package io.github.pixelrick420.papyrus.ui

import android.graphics.Bitmap
import android.text.SpannableString
import android.text.Spanned
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pixelrick420.papyrus.R
import io.github.pixelrick420.papyrus.data.DocumentEntity
import io.github.pixelrick420.papyrus.data.DocumentFormat
import io.github.pixelrick420.papyrus.data.LibrarySort
import io.github.pixelrick420.papyrus.tts.TtsState
import io.github.pixelrick420.papyrus.ui.components.SearchPill
import io.github.pixelrick420.papyrus.ui.components.SnippetText
import io.github.pixelrick420.papyrus.ui.navigation.PanePlaceholder
import io.github.pixelrick420.papyrus.ui.screens.BulkAction
import io.github.pixelrick420.papyrus.ui.screens.BulkProgress
import io.github.pixelrick420.papyrus.ui.screens.BulkProgressBar
import io.github.pixelrick420.papyrus.ui.screens.SelectionHeader
import io.github.pixelrick420.papyrus.ui.screens.SortFilterButton
import io.github.pixelrick420.papyrus.ui.viewer.FileInfoSheet
import io.github.pixelrick420.papyrus.ui.viewer.FindInFileBar
import io.github.pixelrick420.papyrus.ui.viewer.OfficeBlockView
import io.github.pixelrick420.papyrus.ui.viewer.OutlineSheet
import io.github.pixelrick420.papyrus.ui.viewer.TtsControls
import io.github.pixelrick420.papyrus.ui.viewer.exposeMarkdownSemantics
import io.github.pixelrick420.papyrus.viewer.OfficeBlock
import io.github.pixelrick420.papyrus.viewer.MarkdownRenderer
import io.github.pixelrick420.papyrus.viewer.OutlineEntry
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.HeadingSpan
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    private val document = DocumentEntity(
        id = 1,
        uri = "content://test/report.pdf",
        title = "report.pdf",
        format = DocumentFormat.PDF,
        sizeBytes = 1024,
        createdAt = 0L,
        lastOpenedAt = 0L,
    )

    @Test
    fun searchPillIsOneLabelledField() {
        rule.setContent {
            SearchPill(text = "", onTextChange = {}, hint = "Search files", onSearch = {})
        }

        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.onNode(hasSetTextAction() and hasContentDescription("Search files")).assertExists()
        rule.onAllNodesWithText("Search files").assertCountEquals(0)
    }

    @Test
    fun ttsSpeedChipIsActionable() {
        rule.setContent {
            TtsControls(
                state = TtsState(isPlaying = true, speed = 1.5f),
                onPlayPause = {},
                onSkipBack = {},
                onSkipForward = {},
                onClose = {},
                onSpeedChange = {},
                onToggleScrollLock = {},
            )
        }

        rule.onNodeWithContentDescription("Speed 1.5×")
            .assertHasClickAction()
            .assertIsEnabled()
            .assert(minTouchTarget(48.dp))
        rule.onNodeWithContentDescription("Pause").assert(minTouchTarget(48.dp))
    }

    @Test
    fun sortFilterButtonIsOneActionableNode() {
        rule.setContent {
            SortFilterButton(sort = LibrarySort.LAST_OPENED, activeCount = 2, onClick = {})
        }

        rule.onNodeWithContentDescription("Sort: Last opened, 2 filters active")
            .assertHasClickAction()
            .assert(minTouchTarget(48.dp))
        rule.onAllNodesWithContentDescription("2 filters active").assertCountEquals(0)
    }

    @Test
    fun fileInfoRowsReadAsOneNode() {
        rule.setContent {
            FileInfoSheet(document = document, pageCount = null, mimeType = null, onDismiss = {})
        }

        rule.onNode(hasText("Name") and hasText("report.pdf")).assertExists()
        rule.onNode(hasText("Format") and hasText("PDF")).assertExists()
        rule.onNodeWithText("File info")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun outlineTitleIsHeading() {
        rule.setContent {
            OutlineSheet(
                entries = listOf(OutlineEntry(index = 0, depth = 0, title = "Chapter 1")),
                collapsible = false,
                onSelect = {},
                onDismiss = {},
            )
        }

        rule.onNodeWithText("Outline")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun findCounterIsPoliteLiveRegion() {
        rule.setContent {
            FindInFileBar(
                query = "papyrus",
                matchCount = 7,
                currentMatch = 1,
                canNavigate = true,
                onQueryChange = {},
                onNext = {},
                onPrevious = {},
                onClose = {},
            )
        }

        rule.onNodeWithText("2 of 7").assert(
            SemanticsMatcher("live region is polite") { node ->
                node.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
            },
        )
    }

    @Test
    fun searchSnippetReadsAsOneTextNode() {
        rule.setContent {
            SnippetText(text = "the scale of paper", highlights = listOf(4..8))
        }

        // One node, not one per highlighted range: a screen reader must read the excerpt as a
        // sentence rather than stop at every emphasised word.
        rule.onAllNodesWithText("the scale of paper").assertCountEquals(1)
    }

    @Test
    fun officeHeadingIsExposedAsHeading() {
        rule.setContent {
            OfficeBlockView(OfficeBlock.Heading(text = "Chapter 1", level = 1))
        }

        rule.onNodeWithText("Chapter 1")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun imageAltTextIsAnnouncedAndMissingAltIsSkipped() {
        val described = OfficeBlock.Image(pngFile("described"), "image/png", altText = "Sales by quarter")
        val plain = OfficeBlock.Image(pngFile("plain"), "image/png")
        rule.setContent {
            OfficeBlockView(described)
            OfficeBlockView(plain)
        }

        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(loadingLabel()).fetchSemanticsNodes().isEmpty()
        }
        rule.onAllNodesWithContentDescription("Sales by quarter").assertCountEquals(1)
        // The image with no alt text adds no node of its own, so TalkBack has nothing to stop on.
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .assertCountEquals(1)
    }

    @Test
    fun markdownHeadingsAreVirtualHeadingNodes() {
        lateinit var view: TextView
        rule.setContent {
            AndroidView(
                factory = { context ->
                    val text = SpannableString("Intro text\n\nSetup\n\nBody text")
                    val start = text.indexOf("Setup")
                    text.setSpan(
                        HeadingSpan(MarkwonTheme.create(context), 2),
                        start,
                        start + "Setup".length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    TextView(context).apply {
                        this.text = text
                        exposeMarkdownSemantics()
                        view = this
                    }
                },
            )
        }

        rule.waitForIdle()
        var info: AccessibilityNodeInfoCompat? = null
        rule.runOnUiThread {
            val provider = view.accessibilityNodeProvider
            info = provider?.createAccessibilityNodeInfo(0)?.let { AccessibilityNodeInfoCompat.wrap(it) }
        }
        assertEquals("Setup", info?.text?.toString())
        assertTrue(info?.isHeading == true)
    }

    @Test
    fun markdownImagesWithAltTextAreVirtualImageNodesAndMissingAltIsSkipped() {
        val source = "# Report\n\n![Sales by quarter]($PIXEL)\n\n![]($PIXEL)\n\nClosing words"
        lateinit var view: TextView
        rule.setContent {
            AndroidView(
                factory = { context ->
                    TextView(context).apply {
                        MarkdownRenderer.create(context, source).markwon.setMarkdown(this, source)
                        exposeMarkdownSemantics()
                        view = this
                    }
                },
            )
        }

        rule.waitForIdle()
        var children = -1
        var heading: AccessibilityNodeInfoCompat? = null
        var image: AccessibilityNodeInfoCompat? = null
        rule.runOnUiThread {
            val provider = view.accessibilityNodeProvider
            children = provider
                ?.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)
                ?.let { AccessibilityNodeInfoCompat.wrap(it).childCount } ?: -1
            heading = provider?.createAccessibilityNodeInfo(0)?.let { AccessibilityNodeInfoCompat.wrap(it) }
            image = provider?.createAccessibilityNodeInfo(1)?.let { AccessibilityNodeInfoCompat.wrap(it) }
        }
        // The heading and the described image: the image with no alt text adds no node of its own.
        assertEquals(2, children)
        assertEquals("Report", heading?.text?.toString())
        assertTrue(heading?.isHeading == true)
        assertEquals("Sales by quarter", image?.contentDescription?.toString())
        assertEquals(ImageView::class.java.name, image?.className?.toString())
        assertTrue(image?.isHeading != true)
    }

    private fun string(@StringRes id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun loadingLabel(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.viewer_office_image_loading)

    private fun pngFile(name: String): File {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(dir, "a11y-$name.png").also { file ->
            file.outputStream().use { out ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    private fun minTouchTarget(size: Dp): SemanticsMatcher {
        val minimum = with(rule.density) { size.toPx() }
        return SemanticsMatcher("touch target is at least $size") { node ->
            val bounds = node.touchBoundsInRoot
            bounds.width >= minimum && bounds.height >= minimum
        }
    }

    @Test
    fun selectionHeaderReadsItsProgressAsOneLabelAndNeverAnnouncesItAsItMoves() {
        rule.setContent {
            SelectionHeader(
                count = 12,
                allSelected = false,
                progress = BulkProgress(BulkAction.REMOVE, done = 3, total = 12),
                onExit = {},
                onToggleAll = {},
                onShare = {},
                onRemove = {},
            )
        }

        rule.onNodeWithText("Removing 3 of 12").assertExists()
        // The count gives way to the progress rather than sitting beside it.
        rule.onAllNodesWithText("12 selected").assertCountEquals(0)
        // Neither action can be started again over the one that is running.
        rule.onNodeWithContentDescription("Remove").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Share").assertIsNotEnabled()
        // TalkBack announces a progress bar as it moves and a live region whenever it changes, which would read a running tally and then the finish a second time.
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
    }

    @Test
    fun selectionHeaderWithNoActionRunningShowsTheCountAndItsActions() {
        rule.setContent {
            SelectionHeader(
                count = 12,
                allSelected = false,
                progress = null,
                onExit = {},
                onToggleAll = {},
                onShare = {},
                onRemove = {},
            )
        }

        rule.onNodeWithText("12 selected").assertExists()
        rule.onNodeWithContentDescription("Remove").assertIsEnabled()
        rule.onNodeWithContentDescription("Share").assertIsEnabled()
    }

    @Test
    fun bulkProgressBarIsDrawnForTheEyesOnly() {
        rule.setContent {
            BulkProgressBar(BulkProgress(BulkAction.SHARE, done = 5, total = 12))
        }

        // A bar TalkBack can see is announced as it moves; this one must leave the tree.
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
    }

    @Test
    fun bulkProgressSteppingReplacesTheLabelInPlaceAndHandsBackTheCount() {
        val progress = mutableStateOf<BulkProgress?>(BulkProgress(BulkAction.SHARE, done = 3, total = 12))
        rule.setContent {
            SelectionHeader(
                count = 12,
                allSelected = false,
                progress = progress.value,
                onExit = {},
                onToggleAll = {},
                onShare = {},
                onRemove = {},
            )
        }

        rule.onNodeWithText("Preparing 3 of 12").assertExists()
        rule.runOnIdle { progress.value = BulkProgress(BulkAction.SHARE, done = 4, total = 12) }
        // Each step swaps the one text node; none is added beside it and none is a live region.
        rule.onNodeWithText("Preparing 4 of 12").assertExists()
        rule.onAllNodesWithText("Preparing 3 of 12").assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)

        rule.runOnIdle { progress.value = null }
        rule.onNodeWithText("12 selected").assertExists()
        rule.onAllNodesWithText("Preparing 4 of 12").assertCountEquals(0)
        rule.onNodeWithContentDescription("Remove").assertIsEnabled()
        rule.onNodeWithContentDescription("Share").assertIsEnabled()
        rule.onNodeWithContentDescription("Select all").assertIsEnabled()
    }

    @Test
    fun selectAllIsDimmedWhileABulkActionRuns() {
        rule.setContent {
            SelectionHeader(
                count = 12,
                allSelected = false,
                progress = BulkProgress(BulkAction.REMOVE, done = 3, total = 12),
                onExit = {},
                onToggleAll = {},
                onShare = {},
                onRemove = {},
            )
        }

        rule.onNodeWithContentDescription("Select all").assertIsNotEnabled()
    }

    @Test
    fun readAloudBarIsAbsentUntilSomethingIsBeingRead() {
        rule.setContent { ReadAloudBar(TtsState()) }

        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun readAloudPlayPauseNamesTheActionItWillTake() {
        val state = mutableStateOf(TtsState(isPlaying = true))
        rule.setContent { ReadAloudBar(state.value) }

        rule.onNodeWithContentDescription("Pause").assertHasClickAction().assert(isButton())
        rule.onAllNodesWithContentDescription("Play").assertCountEquals(0)

        rule.runOnIdle { state.value = TtsState(isPaused = true) }
        rule.onNodeWithContentDescription("Play").assertHasClickAction().assert(isButton())
        rule.onAllNodesWithContentDescription("Pause").assertCountEquals(0)
    }

    @Test
    fun readAloudScrollLockNamesWhatPressingItDoes() {
        val state = mutableStateOf(TtsState(isPlaying = true, scrollLocked = true))
        rule.setContent { ReadAloudBar(state.value) }

        rule.onNodeWithContentDescription(string(R.string.tts_scroll_unlock)).assertHasClickAction().assert(isButton())
        rule.onAllNodesWithContentDescription(string(R.string.tts_scroll_lock)).assertCountEquals(0)

        rule.runOnIdle { state.value = TtsState(isPlaying = true, scrollLocked = false) }
        rule.onNodeWithContentDescription(string(R.string.tts_scroll_lock)).assertHasClickAction().assert(isButton())
        rule.onAllNodesWithContentDescription(string(R.string.tts_scroll_unlock)).assertCountEquals(0)
    }

    @Test
    fun readAloudBarIsSixLabelledStopsAndAnnouncesNothingOnItsOwn() {
        rule.setContent { ReadAloudBar(TtsState(isPlaying = true, speed = 1.5f)) }

        // Skip back, play/pause, skip forward, lock, speed and stop: one node each, no separate glyph.
        rule.onAllNodes(hasClickAction()).assertCountEquals(6)
        rule.onNodeWithContentDescription("Skip back").assertHasClickAction()
        rule.onNodeWithContentDescription("Skip forward").assertHasClickAction()
        rule.onNodeWithContentDescription(string(R.string.viewer_read_aloud_stop)).assertHasClickAction()
        rule.onNodeWithContentDescription("Speed 1.5×").assertHasClickAction()
        // The speed chip's own "1.5×" text is folded into its label rather than read twice.
        rule.onAllNodesWithText("1.5×").assertCountEquals(0)
        // Playback state changes as the voice moves on; the bar must not talk over it.
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
    }

    @Test
    fun readAloudEngineFailureKeepsPlayAndDimsTheRest() {
        rule.setContent {
            ReadAloudBar(TtsState(hasError = true, errorMessage = "Speech stopped unexpectedly"))
        }

        rule.onNodeWithText("Speech stopped unexpectedly").assertExists()
        // Play stays so the viewer can rebuild the queue; everything that needs the engine is off.
        rule.onNodeWithContentDescription("Play").assertIsEnabled()
        rule.onNodeWithContentDescription("Skip back").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Skip forward").assertIsNotEnabled()
        // A failed read cannot hold the page, so the lock reads as the action it would take.
        rule.onNodeWithContentDescription(string(R.string.tts_scroll_lock)).assertIsNotEnabled()
        rule.onNodeWithContentDescription("Speed 1×").assertIsNotEnabled()
        rule.onNodeWithContentDescription(string(R.string.viewer_read_aloud_stop)).assertIsEnabled()
    }

    @Composable
    private fun ReadAloudBar(state: TtsState) {
        TtsControls(
            state = state,
            onPlayPause = {},
            onSkipBack = {},
            onSkipForward = {},
            onClose = {},
            onSpeedChange = {},
            onToggleScrollLock = {},
        )
    }

    private fun isButton(): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun snackbarMessageIsAnnouncedOnceAsOnePoliteLiveRegion() {
        val message = string(R.string.home_removed_count, 12)
        val host = SnackbarHostState()
        lateinit var scope: CoroutineScope
        rule.setContent {
            scope = rememberCoroutineScope()
            SnackbarHost(host)
        }

        rule.runOnIdle { scope.launch { host.showSnackbar(message, duration = SnackbarDuration.Indefinite) } }
        waitForText(message)

        // The bulk Remove ends with this and only this: one message, in one live region.
        rule.onAllNodesWithText(message).assertCountEquals(1)
        rule.onAllNodes(politeLiveRegion()).assertCountEquals(1)
        rule.onNode(politeLiveRegion()).assert(hasText(message) or hasAnyDescendant(hasText(message)))
    }

    @Test
    fun snackbarUndoIsOneButtonInsideTheSameAnnouncement() {
        val message = string(R.string.home_removed)
        val undo = string(R.string.action_undo)
        val host = SnackbarHostState()
        val result = AtomicReference<SnackbarResult?>(null)
        lateinit var scope: CoroutineScope
        rule.setContent {
            scope = rememberCoroutineScope()
            SnackbarHost(host)
        }

        rule.runOnIdle {
            scope.launch {
                result.set(host.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Indefinite))
            }
        }
        waitForText(message)

        rule.onAllNodesWithText(undo).assertCountEquals(1)
        rule.onNodeWithText(undo).assertHasClickAction().assert(isButton())
        // The action is read with the message, not announced as a second live region.
        rule.onAllNodes(politeLiveRegion()).assertCountEquals(1)
        rule.onNode(politeLiveRegion()).assert(hasAnyDescendant(hasText(undo)) or hasText(undo))

        rule.onNodeWithText(undo).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { result.get() == SnackbarResult.ActionPerformed }
    }

    @Test
    fun aQueuedSnackbarWaitsForTheOneOnScreenAndNeverSharesItsLiveRegion() {
        val first = string(R.string.home_removed)
        val second = string(R.string.home_removed_count, 3)
        val host = SnackbarHostState()
        lateinit var scope: CoroutineScope
        rule.setContent {
            scope = rememberCoroutineScope()
            SnackbarHost(host)
        }

        rule.runOnIdle {
            scope.launch { host.showSnackbar(first, duration = SnackbarDuration.Indefinite) }
            scope.launch { host.showSnackbar(second, duration = SnackbarDuration.Indefinite) }
        }
        waitForText(first)
        rule.onAllNodesWithText(second).assertCountEquals(0)
        rule.onAllNodes(politeLiveRegion()).assertCountEquals(1)

        rule.runOnIdle { host.currentSnackbarData?.dismiss() }
        waitForText(second)
        // The old one fades out as the new one fades in, so wait for it to leave before counting.
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText(first).fetchSemanticsNodes().isEmpty() }
        rule.onAllNodes(politeLiveRegion()).assertCountEquals(1)
    }

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun politeLiveRegion(): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun twoPaneEmptyStateIsOneInertTextNode() {
        val prompt = string(R.string.home_pane_placeholder)
        rule.setContent { PanePlaceholder() }

        rule.onAllNodesWithText(prompt).assertCountEquals(1)
        // Nothing to press and nothing to announce on its own: it is read when focus reaches it.
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEquals(0)
        rule.onNodeWithText(prompt).assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
}

private const val PIXEL =
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
