package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun `latex factory detects no ranges in empty string`() {
        val factory = LatexSpanFactory(null, "")
        assertEquals(0, factory.ranges.size)
    }

    @Test
    fun `latex factory detects inline formula`() {
        val factory = LatexSpanFactory(null, "${'$'}${'$'}E=mc^2${'$'}${'$'}")
        assertEquals(1, factory.ranges.size)
        val range = factory.ranges[0]
        assertEquals(0, range.start)
        assertEquals(10, range.end)
        assertEquals("E=mc^2", range.latex)
        assertFalse(range.isBlock)
    }

    @Test
    fun `latex factory detects inline formula within text`() {
        val factory = LatexSpanFactory(null, "Hello ${'$'}${'$'}E=mc^2${'$'}${'$'} world")
        assertEquals(1, factory.ranges.size)
        val range = factory.ranges[0]
        assertEquals(6, range.start)
        assertEquals(16, range.end)
        assertEquals("E=mc^2", range.latex)
        assertFalse(range.isBlock)
    }

    @Test
    fun `latex factory does not match single dollar price`() {
        val factory = LatexSpanFactory(null, "Price ${'$'}5 is cheap")
        assertEquals(0, factory.ranges.size)
    }

    @Test
    fun `latex factory detects block formula on its own line`() {
        val factory = LatexSpanFactory(null, "${'$'}${'$'}\nE=mc^2\n${'$'}${'$'}")
        assertEquals(1, factory.ranges.size)
        val range = factory.ranges[0]
        assertEquals("E=mc^2", range.latex)
        assertTrue(range.isBlock)
    }

    @Test
    fun `latex factory detects multiple formulas`() {
        val factory = LatexSpanFactory(null, "${'$'}${'$'}E=mc^2${'$'}${'$'} and ${'$'}${'$'}F=ma${'$'}${'$'}")
        assertEquals(2, factory.ranges.size)
        assertFalse(factory.ranges[0].isBlock)
        assertFalse(factory.ranges[1].isBlock)
        assertEquals("E=mc^2", factory.ranges[0].latex)
        assertEquals("F=ma", factory.ranges[1].latex)
    }

    @Test
    fun `latex factory treats formula with trailing text as inline`() {
        val factory = LatexSpanFactory(null, "${'$'}${'$'}E=mc^2${'$'}${'$'} more text")
        assertEquals(1, factory.ranges.size)
        assertFalse(factory.ranges[0].isBlock)
    }

    @Test
    fun `latex factory treats formula with leading text as inline`() {
        val factory = LatexSpanFactory(null, "more text ${'$'}${'$'}E=mc^2${'$'}${'$'}")
        assertEquals(1, factory.ranges.size)
        assertFalse(factory.ranges[0].isBlock)
    }
}
