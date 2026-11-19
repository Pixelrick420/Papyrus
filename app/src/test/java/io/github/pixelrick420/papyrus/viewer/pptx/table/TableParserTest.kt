package io.github.pixelrick420.papyrus.viewer.pptx.table

import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.ShapeTreeParser
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame
import io.github.pixelrick420.papyrus.viewer.pptx.GraphicKind
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.SceneSize
import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import io.github.pixelrick420.papyrus.viewer.pptx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableParserTest {

    @Test
    fun `parses grid merges margins borders and caps`() {
        val deck = pptx {
            slide {
                table(0, 0, listOf(1000, 2000)) {
                    row(400) {
                        cell("a", gridSpan = 2)
                    }
                }
            }
        }
        val pkg = PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)
        PresentationParser(OfficeFixtures::parser).parse(pkg.part(pkg.mainPart)!!)
        val scene = ShapeTreeParser(OfficeFixtures::parser).parse(pkg, "ppt/slides/slide1.xml", SceneSize(1.0, 1.0))
        val node = scene.scene.nodes.single() as SceneGraphicFrame
        assertEquals(GraphicKind.TABLE, node.kind)
        val table = node.table!!
        assertEquals(listOf(1000.0, 2000.0), table.grid.columnWidths)
        assertEquals(1, table.rows.size)
        assertEquals(2, table.rows[0].cells.size)
        assertEquals(2, table.rows[0].cells[0].gridSpan)
        assertTrue(table.rows[0].cells[1].horizontalMerge)
    }

    @Test
    fun `drops tables over caps`() {
        val deck = pptx {
            slide {
                table(0, 0, List(3) { 1000L }) {
                    row(100, "a", "b", "c")
                }
            }
        }
        val pkg = PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)
        val scene = ShapeTreeParser(OfficeFixtures::parser, PptxLimits(maxTableColumns = 2))
            .parse(pkg, "ppt/slides/slide1.xml", SceneSize(1.0, 1.0))
        val node = scene.scene.nodes.single() as SceneGraphicFrame
        assertNull(node.table)
    }
}
