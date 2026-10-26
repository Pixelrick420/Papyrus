package com.papyrus.app.ui.viewer

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import com.papyrus.app.viewer.BlockAlign
import com.papyrus.app.viewer.BlockDirection
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A block's direction as the viewer sets it: the style's direction, never a physical alignment. */
class OfficeDirectionTest {

    private val base = TextStyle()

    @Test
    fun `the model's direction maps to the toolkit's`() {
        assertEquals(TextDirection.Ltr, BlockDirection.LTR.toTextDirection())
        assertEquals(TextDirection.Rtl, BlockDirection.RTL.toTextDirection())
    }

    @Test
    fun `a centred right-to-left block gets both its direction and its alignment`() {
        val style = base.withLayout(BlockAlign.CENTER, BlockDirection.RTL)

        assertEquals(TextDirection.Rtl, style.textDirection)
        assertEquals(TextAlign.Center, style.textAlign)
    }

    @Test
    fun `a block that starts where it starts keeps start, which the direction then turns to the right`() {
        val style = base.withLayout(BlockAlign.START, BlockDirection.RTL)

        assertEquals(TextDirection.Rtl, style.textDirection)
        // Start, not End and not Right: Start follows the direction, so nothing here is physical.
        assertEquals(base.textAlign, style.textAlign)
    }

    @Test
    fun `an end aligned right-to-left block keeps end`() {
        assertEquals(TextAlign.End, base.withLayout(BlockAlign.END, BlockDirection.RTL).textAlign)
    }

    @Test
    fun `a left-to-right block is the style it always was`() {
        assertEquals(base, base.withLayout(BlockAlign.START, BlockDirection.LTR))
        assertEquals(base.copy(textAlign = TextAlign.Center), base.withLayout(BlockAlign.CENTER, BlockDirection.LTR))
        assertEquals(base.copy(textAlign = TextAlign.End), base.withLayout(BlockAlign.END, BlockDirection.LTR))
    }

    @Test
    fun `every text block can be asked its direction and the others have none`() {
        assertEquals(BlockDirection.RTL, OfficeBlock.Heading("h", 1, direction = BlockDirection.RTL).direction)
        assertEquals(BlockDirection.RTL, OfficeBlock.Paragraph("p", direction = BlockDirection.RTL).direction)
        assertEquals(BlockDirection.LTR, OfficeBlock.ListItem("i", "•", 0, direction = BlockDirection.LTR).direction)
        assertEquals(BlockDirection.RTL, OfficeBlock.Note("1", "n", direction = BlockDirection.RTL).direction)
        assertNull(OfficeBlock.Paragraph("p").direction)
        assertNull(OfficeBlock.Table(emptyList()).direction)
        assertNull(OfficeBlock.Divider.direction)
        assertNull(OfficeBlock.Image(java.io.File("x"), "image/png").direction)
    }
}
