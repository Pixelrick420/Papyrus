package io.github.pixelrick420.papyrus.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pixelrick420.papyrus.data.LibrarySort
import io.github.pixelrick420.papyrus.tts.TtsState
import io.github.pixelrick420.papyrus.ui.components.SearchPill
import io.github.pixelrick420.papyrus.ui.screens.BulkAction
import io.github.pixelrick420.papyrus.ui.screens.BulkProgress
import io.github.pixelrick420.papyrus.ui.screens.SelectionHeader
import io.github.pixelrick420.papyrus.ui.screens.SortFilterButton
import io.github.pixelrick420.papyrus.ui.viewer.FindInFileBar
import io.github.pixelrick420.papyrus.ui.viewer.TtsControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusOrderTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun selectionHeaderIsReachedBackThenCountThenActionsThenSelectAll() {
        rule.setContent { Header(progress = null) }

        assertEquals(listOf("Back", "12 selected", "Remove", "Share", "Select all"), focusStops())
    }

    @Test
    fun selectionHeaderKeepsItsOrderWhileABulkActionRuns() {
        rule.setContent { Header(progress = BulkProgress(BulkAction.REMOVE, done = 3, total = 12)) }

        // The count gives way to the progress in the same place, so a swipe lands where it did.
        assertEquals(listOf("Back", "Removing 3 of 12", "Remove", "Share", "Select all"), focusStops())
    }

    @Test
    fun selectionHeaderTakesFocusFromBackFirstAndTabsThroughItsControls() {
        rule.setContent { Header(progress = null) }

        // The count is text, not a control, so the keyboard skips it.
        for (label in listOf("Back", "Remove", "Share", "Select all")) {
            pressTab()
            rule.onNodeWithContentDescription(label).assertIsFocused()
        }
    }

    /** performKeyInput only feeds an already-focused node; a real key press focuses the first control. */
    private fun pressTab() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        rule.waitForIdle()
    }

    @Test
    fun homeSearchRowIsReachedSearchFieldThenSortAndFilter() {
        rule.setContent {
            Row {
                SearchPill(text = "", onTextChange = {}, hint = "Search files", onSearch = {}, modifier = Modifier.weight(1f))
                SortFilterButton(sort = LibrarySort.LAST_OPENED, activeCount = 2, onClick = {})
            }
        }

        assertEquals(listOf("Search files", "Sort: Last opened, 2 filters active"), focusStops())
    }

    @Test
    fun findBarIsReachedFieldThenCounterThenStepsThenClose() {
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

        assertEquals(listOf("Find", "2 of 7", "Previous match", "Next match", "Close find"), focusStops())
    }

    @Test
    fun readAloudBarIsReachedTransportThenLockThenSpeedThenStop() {
        rule.setContent { ReadAloud(TtsState(isPlaying = true)) }

        assertEquals(
            listOf("Skip back", "Pause", "Skip forward", "Unlock scrolling", "Speed 1×", "Stop read aloud"),
            focusStops(),
        )
    }

    @Test
    fun readAloudErrorIsReachedBeforeTheControlsItExplains() {
        rule.setContent {
            ReadAloud(TtsState(hasError = true, errorMessage = "Speech stopped unexpectedly"))
        }

        assertEquals(
            listOf(
                "Speech stopped unexpectedly",
                "Skip back",
                "Play",
                "Skip forward",
                "Lock scrolling to the line being read",
                "Speed 1×",
                "Stop read aloud",
            ),
            focusStops(),
        )
    }

    @Composable
    private fun Header(progress: BulkProgress?) {
        SelectionHeader(
            count = 12,
            allSelected = false,
            progress = progress,
            onExit = {},
            onToggleAll = {},
            onShare = {},
            onRemove = {},
        )
    }

    @Composable
    private fun ReadAloud(state: TtsState) {
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

    private fun focusStops(): List<String> =
        rule.onAllNodes(SemanticsMatcher("is a screen reader stop") { it.isStop() })
            .fetchSemanticsNodes()
            .map { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                    ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            }

    private fun SemanticsNode.isStop(): Boolean =
        config.contains(SemanticsProperties.ContentDescription) ||
            config.contains(SemanticsProperties.Text) ||
            config.contains(SemanticsProperties.EditableText) ||
            config.contains(SemanticsActions.OnClick)
}
