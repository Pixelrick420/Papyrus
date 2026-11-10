package com.papyrus.app.ui.navigation

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class WindowLayoutTest {

    private fun twoPanes(widthDp: Int, heightDp: Int) =
        WindowSizeClass.calculateFromSize(DpSize(widthDp.dp, heightDp.dp)).showsListAndViewer()

    @Test
    fun `a phone in portrait keeps its single pane`() {
        assertFalse(twoPanes(widthDp = 412, heightDp = 915))
    }

    @Test
    fun `a phone on its side keeps its single pane though it is as wide as a small tablet`() {
        val phoneOnItsSide = WindowSizeClass.calculateFromSize(DpSize(915.dp, 412.dp))
        // The reason the width alone is not enough: this is wide enough for two panes...
        assertTrue(phoneOnItsSide.widthSizeClass >= WindowWidthSizeClass.Medium)
        // ...and it still gets one.
        assertFalse(phoneOnItsSide.showsListAndViewer())
    }

    @Test
    fun `a tablet shows two panes in either orientation`() {
        assertTrue("a small tablet upright", twoPanes(widthDp = 600, heightDp = 960))
        assertTrue("a large tablet upright", twoPanes(widthDp = 800, heightDp = 1280))
        assertTrue("a large tablet on its side", twoPanes(widthDp = 1280, heightDp = 800))
        assertTrue("a small tablet on its side", twoPanes(widthDp = 960, heightDp = 600))
    }

    @Test
    fun `a foldable has two panes open and one closed`() {
        assertTrue("unfolded", twoPanes(widthDp = 884, heightDp = 1104))
        assertFalse("folded, the cover screen", twoPanes(widthDp = 360, heightDp = 800))
    }

    @Test
    fun `medium width starts at 600dp`() {
        assertFalse(twoPanes(widthDp = 599, heightDp = 900))
        assertTrue(twoPanes(widthDp = 600, heightDp = 900))
    }

    @Test
    fun `a window that is tall enough starts at 480dp`() {
        assertFalse(twoPanes(widthDp = 700, heightDp = 479))
        assertTrue(twoPanes(widthDp = 700, heightDp = 480))
    }

    @Test
    fun `a tablet window narrowed by split screen goes back to one pane`() {
        assertFalse(twoPanes(widthDp = 400, heightDp = 1200))
    }

    @Test
    fun `the list takes two fifths of the window within its bounds`() {
        // Narrow: two fifths would be 240dp, too little for a row, so the floor holds.
        assertEquals(280f, listPaneWidth(600.dp).value, 0.01f)
        assertEquals(320f, listPaneWidth(800.dp).value, 0.01f)
        // Wide: two fifths of 1280dp would crowd the document, so the ceiling holds.
        assertEquals(400f, listPaneWidth(1280.dp).value, 0.01f)
        assertEquals(400f, listPaneWidth(2000.dp).value, 0.01f)
    }
}
