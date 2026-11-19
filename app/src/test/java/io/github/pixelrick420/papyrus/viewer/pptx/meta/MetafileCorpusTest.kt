package io.github.pixelrick420.papyrus.viewer.pptx.meta

import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.render.DrawOp
import io.github.pixelrick420.papyrus.viewer.pptx.render.PaintSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetafileCorpusTest {

    @Test
    fun emfTextAndFontProduceAGlyphRun() {
        val file = MetafileFixtures.emf(
            records = listOf(
                MetafileFixtures.record(0x18, 0x0000FF),
                MetafileFixtures.emfFont(underline = true),
                MetafileFixtures.record(0x25, 3),
                MetafileFixtures.emfTextOut("Hello", 100, 200),
                MetafileFixtures.record(0x0E),
            ),
        )
        val image = Metafiles.parse(file, "emf", MAX_EDGE, MAX_PIXELS)
        assertNotNull(image)
        val runs = image!!.displayList.ops.filterIsInstance<DrawOp.DrawGlyphRun>().map { it.run }
        assertEquals(1, runs.size)
        assertEquals("Hello", runs.single().text)
        assertTrue(runs.single().size > 0f)
        assertTrue(runs.single().rules.isNotEmpty())
    }

    @Test
    fun wmfWithPlaceableHeaderRendersItsDrawingRecords() {
        val file = MetafileFixtures.wmf(
            listOf(
                MetafileFixtures.wmfRecord(0x02FA, byteArrayOf(0, 0, 20, 0, 0, 0, 255.toByte(), 0, 0, 0)),
                MetafileFixtures.wmfRecord(0x02FC, byteArrayOf(0, 0, 0, 255.toByte(), 0, 0, 0, 0)),
                MetafileFixtures.wmfRecord(0x012D, MetafileFixtures.words(0)),
                MetafileFixtures.wmfRecord(0x012D, MetafileFixtures.words(1)),
                MetafileFixtures.wmfRecord(0x041B, MetafileFixtures.words(80, 120, 20, 10)),
                MetafileFixtures.wmfRecord(0x0418, MetafileFixtures.words(200, 300, 120, 180)),
                MetafileFixtures.wmfRecord(0x0214, MetafileFixtures.words(80, 10)),
                MetafileFixtures.wmfRecord(0x0213, MetafileFixtures.words(80, 120)),
                MetafileFixtures.wmfRecord(0x0000, byteArrayOf()),
            ),
        )
        val image = Metafiles.parse(file, "wmf", MAX_EDGE, MAX_PIXELS)
        assertNotNull(image)
        val ops = image!!.displayList.ops
        assertEquals(2, ops.filterIsInstance<DrawOp.FillPath>().size)
        assertEquals(3, ops.filterIsInstance<DrawOp.StrokePath>().size)
    }

    @Test
    fun wmfWithoutPlaceableHeaderUsesItsWindowExtent() {
        val text = byteArrayOf('W'.code.toByte(), 'M'.code.toByte(), 'F'.code.toByte(), 0)
        val file = MetafileFixtures.wmf(
            listOf(
                MetafileFixtures.wmfRecord(0x020C, MetafileFixtures.words(800, 1_000)),
                MetafileFixtures.wmfRecord(0x02FB, fontParameters()),
                MetafileFixtures.wmfRecord(0x012D, MetafileFixtures.words(0)),
                MetafileFixtures.wmfRecord(
                    0x0521,
                    MetafileFixtures.words(3) + text + MetafileFixtures.words(100, 20),
                ),
                MetafileFixtures.wmfRecord(0x0A32, MetafileFixtures.words(100, 20, 3, 0) + text),
                MetafileFixtures.wmfRecord(0x0000, byteArrayOf()),
            ),
            placeable = false,
        )
        val image = Metafiles.parse(file, "wmf", MAX_EDGE, MAX_PIXELS)
        assertNotNull(image)
        val runs = image!!.displayList.ops.filterIsInstance<DrawOp.DrawGlyphRun>().map { it.run }
        assertEquals(2, runs.size)
        assertTrue(runs.all { it.text.startsWith("WMF") })
        assertTrue(runs.all { it.size > 0f })
    }

    @Test
    fun aRealEmfUsesTheHandleFirstRecordFields() {
        val image = Metafiles.parse(resource("wrench.emf"), "emf", MAX_EDGE, MAX_PIXELS)
        assertNotNull(image)
        val fills = image!!.displayList.ops.filterIsInstance<DrawOp.FillPath>()
        assertTrue(fills.isNotEmpty())
        assertTrue(image.displayList.ops.any { it is DrawOp.StrokePath })
        assertTrue(fills.any { (it.paint as? PaintSpec.Solid)?.color == Rgba(191, 191, 191) })
    }

    @Test
    fun realWmfsResolveTheirZeroBasedObjectTable() {
        for (name in listOf("santa.wmf", "60677.wmf")) {
            val image = Metafiles.parse(resource(name), "wmf", MAX_EDGE, MAX_PIXELS)
            assertNotNull(image)
            assertTrue(image!!.bounds.width > 0 && image.bounds.height > 0)
            assertTrue(image.displayList.ops.isNotEmpty())
        }
    }

    @Test
    fun corpusStaysInsideTheCapsAndSkipsUnsupportedRecords() {
        val files = listOf(
            "emf" to MetafileFixtures.emf(
                records = listOf(
                    MetafileFixtures.record(0x79, 1, 2, 3),
                    MetafileFixtures.record(0x2B, 0, 0, 10, 10),
                    MetafileFixtures.record(0x0E),
                ),
            ),
            "wmf" to MetafileFixtures.wmf(
                listOf(
                    MetafileFixtures.wmfRecord(0x1234, MetafileFixtures.words(1, 2)),
                    MetafileFixtures.wmfRecord(0x041B, MetafileFixtures.words(10, 10, 0, 0)),
                    MetafileFixtures.wmfRecord(0x0000, byteArrayOf()),
                ),
            ),
        )
        files.forEach { (extension, file) ->
            val image = Metafiles.parse(file, extension, 1_024, 400)
            assertNotNull(image)
            assertTrue(image!!.bounds.width <= 1_024)
            assertTrue(image.bounds.height <= 1_024)
            assertTrue(image.bounds.pixels <= 400)
        }
    }

    private fun resource(name: String): ByteArray = checkNotNull(
        javaClass.getResourceAsStream("/io/github/pixelrick420/papyrus/viewer/$name"),
    ) { "missing test resource $name" }.use { it.readBytes() }

    private fun fontParameters(): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(50).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(120)
        repeat(3) { buffer.putShort(0) }
        buffer.putShort(400)
        repeat(8) { buffer.put(0) }
        repeat(32) { buffer.put(0) }
        return buffer.array()
    }

    private companion object {
        const val MAX_EDGE = 4_096
        const val MAX_PIXELS = 64_000_000L
    }
}
