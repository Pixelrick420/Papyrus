package com.papyrus.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papyrus.app.data.LibrarySort
import com.papyrus.app.tts.TtsState
import com.papyrus.app.ui.components.SearchPill
import com.papyrus.app.ui.screens.BulkAction
import com.papyrus.app.ui.screens.BulkProgress
import com.papyrus.app.ui.screens.SelectionHeader
import com.papyrus.app.ui.screens.SortFilterButton
import com.papyrus.app.ui.viewer.FindInFileBar
import com.papyrus.app.ui.viewer.TtsControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The order a screen reader reaches the controls of each bar in, and which of them it reaches first.
 *
 * The order is the semantics tree's: composition order, which TalkBack follows along a row and down a
 * column unless a traversal index says otherwise (none is set in the app). Reading it off the tree
 * keeps these tests independent of how wide the test device is. Whole screens need a library and a
 * document behind them, so their order is walked by hand: see "Focus order" in ACCESSIBILITY.md.
 */
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
            rule.onRoot().performKeyInput { pressKey(Key.Tab) }
            rule.onNodeWithContentDescription(label).assertIsFocused()
        }
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

    /** What a screen reader would stop on, in the order it would reach it: a label, then its actions. */
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
