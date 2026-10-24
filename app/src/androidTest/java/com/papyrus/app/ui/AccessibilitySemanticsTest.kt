package com.papyrus.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papyrus.app.data.DocumentEntity
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.LibrarySort
import com.papyrus.app.tts.TtsState
import com.papyrus.app.ui.components.SearchPill
import com.papyrus.app.ui.components.SnippetText
import com.papyrus.app.ui.screens.SortFilterButton
import com.papyrus.app.ui.viewer.FileInfoSheet
import com.papyrus.app.ui.viewer.FindInFileBar
import com.papyrus.app.ui.viewer.OutlineSheet
import com.papyrus.app.ui.viewer.TtsControls
import com.papyrus.app.viewer.OutlineEntry
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

    /** Touch bounds, not layout bounds: Material enforces the 48dp minimum on the touch target. */
    private fun minTouchTarget(size: Dp): SemanticsMatcher {
        val minimum = with(rule.density) { size.toPx() }
        return SemanticsMatcher("touch target is at least $size") { node ->
            val bounds = node.touchBoundsInRoot
            bounds.width >= minimum && bounds.height >= minimum
        }
    }
}
