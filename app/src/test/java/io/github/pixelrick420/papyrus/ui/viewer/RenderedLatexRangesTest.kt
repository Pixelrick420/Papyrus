package io.github.pixelrick420.papyrus.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderedLatexRangesTest {

    private companion object {
        const val DD = "\$\$"
    }

    @Test
    fun `finds rendered math`() {
        val text = "before ${DD}E=mc^2${DD} after"
        val ranges = renderedLatexRanges(text, emptyList())
        assertEquals(listOf(text.indexOf(DD)..(text.lastIndexOf(DD) + 1)), ranges)
    }

    @Test
    fun `skips math whose start falls inside a code span`() {
        val text = "`${DD}code${DD}` and ${DD}F=ma${DD}"
        val ranges = renderedLatexRanges(text, listOf(0 until text.indexOf(" and ")))
        assertEquals(1, ranges.size)
        assertEquals("F=ma", text.substring(ranges[0].first + 2, ranges[0].last - 1))
    }
}
