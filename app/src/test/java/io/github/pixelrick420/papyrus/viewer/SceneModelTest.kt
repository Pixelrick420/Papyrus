package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.GraphicKind
import io.github.pixelrick420.papyrus.viewer.pptx.LaidOutText
import io.github.pixelrick420.papyrus.viewer.pptx.PlaceholderIdentity
import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.SceneConnector
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGroup
import io.github.pixelrick420.papyrus.viewer.pptx.SceneParagraph
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePicture
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRun
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRunStyle
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.SceneSize
import io.github.pixelrick420.papyrus.viewer.pptx.SceneTextFrame
import io.github.pixelrick420.papyrus.viewer.pptx.TextAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneModelTest {

    @Test
    fun `a frame keeps its emu coordinates and exposes its far edges`() {
        val frame = SceneRect(x = 838_200.0, y = 365_125.0, width = 10_515_600.0, height = 1_325_563.0)

        assertEquals(11_353_800.0, frame.right, 0.0)
        assertEquals(1_690_688.0, frame.bottom, 0.0)
    }

    @Test
    fun `a scene holds every node kind and caches laid-out text per frame`() {
        val title = SceneTextFrame(
            paragraphs = listOf(
                SceneParagraph(
                    runs = listOf(SceneRun("Title", SceneRunStyle(typeface = "Calibri", sizePt = 44.0, bold = true))),
                ),
            ),
            anchor = TextAnchor.MIDDLE,
        )
        val shape = SceneShape(
            id = 2,
            name = "Title 1",
            frame = SceneRect(0.0, 0.0, 9_144_000.0, 1_000_000.0),
            placeholder = PlaceholderIdentity("title", 0),
            text = title,
        )
        val picture = ScenePicture(3, "Picture 2", SceneRect(0.0, 1_000_000.0, 500_000.0, 500_000.0), relationshipId = "rId2")
        val group = SceneGroup(
            id = 4,
            name = "Group 3",
            frame = SceneRect(0.0, 0.0, 1_000_000.0, 1_000_000.0),
            childFrame = SceneRect(0.0, 0.0, 2_000_000.0, 2_000_000.0),
            children = listOf(shape),
        )
        val connector = SceneConnector(
            5,
            "Connector 4",
            SceneRect(0.0, 0.0, 1_000_000.0, 1_000_000.0),
            start = ScenePoint(0.0, 0.0),
            end = ScenePoint(1_000_000.0, 1_000_000.0),
        )
        val table = SceneGraphicFrame(6, "Table 5", SceneRect(0.0, 0.0, 3_000_000.0, 1_000_000.0), kind = GraphicKind.TABLE)
        val scene = Scene(SceneSize(9_144_000.0, 6_858_000.0), listOf(group, picture, connector, table))

        assertTrue(scene.textLayouts.isEmpty())
        scene.textLayouts[title] = object : LaidOutText {}
        assertEquals(1, scene.textLayouts.size)
        assertTrue(scene.textLayouts.containsKey(title))

        assertEquals(4, scene.nodes.size)
        assertEquals(listOf(shape), group.children)
        assertEquals(ScenePoint(1_000_000.0, 1_000_000.0), connector.end)
        assertEquals(GraphicKind.TABLE, table.kind)
        assertEquals("rId2", picture.relationshipId)
        assertFalse(shape.hidden)
        assertEquals("Title", shape.text?.paragraphs?.single()?.runs?.single()?.text)
    }
}
