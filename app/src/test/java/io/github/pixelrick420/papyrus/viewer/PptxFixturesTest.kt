package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.zip.ZipInputStream

// If one of these fails, the fixture is wrong, not the parser under test.
class PptxFixturesTest {

    private class Element(val name: String, val attrs: Map<String, String>) {
        var text = ""
    }

    private fun parse(xml: ByteArray): List<Element> {
        val parser = OfficeFixtures.parser()
        parser.setInput(ByteArrayInputStream(xml), null)
        val all = mutableListOf<Element>()
        val open = ArrayDeque<Element>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                    Element(parser.name, attrs).also {
                        all += it
                        open.addLast(it)
                    }
                }
                XmlPullParser.END_TAG -> open.removeLast()
                XmlPullParser.TEXT -> open.lastOrNull()?.let { it.text += parser.text }
            }
        }
        return all
    }

    private fun Deck.elements(part: String) = parse(parts.getValue(part))

    private fun List<Element>.named(name: String) = filter { it.name == name }

    private fun xmlParts(deck: Deck) = deck.parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }

    private fun targetOf(relsPart: String, target: String): String =
        URI("/" + relsPart.substringBeforeLast("_rels/")).resolve(target).path.removePrefix("/")

    private fun sample() = pptx {
        slideSize(12_192_000, 6_858_000)
        theme { accent1("4472C4"); majorFont("Calibri Light"); minorFont("Calibri") }
        master { background(scheme = "bg1"); titleStyle { sz(4400) } }
        layout("Title and Content") {
            placeholder(Type.TITLE, idx = 0, x = 838_200, y = 365_125, w = 10_515_600, h = 1_325_563)
            placeholder(Type.BODY, idx = 1, x = 838_200, y = 1_825_625, w = 10_515_600, h = 4_351_338)
        }
        slide(layout = 0) {
            title("Hello")
            body { para("First", level = 0); para("Nested", level = 1) }
            shape(Preset.ROUND_RECT, x = 1_000_000, y = 5_000_000, w = 2_000_000, h = 800_000) { fill("accent1") }
        }
        slide(layout = 0) {
            picture(OfficeFixtures.PNG_1X1, x = 100, y = 200, w = 300, h = 400, description = "dot")
            table(x = 0, y = 0, columns = listOf(1_000_000, 2_000_000)) {
                row(400_000, "a", "b")
                row(400_000) { cell("wide", gridSpan = 2) }
            }
        }
    }

    @Test
    fun `the bytes are a zip with the content types first and every part in it`() {
        val deck = sample()
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(deck.toBytes())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { names += it.name }
        }
        assertEquals("[Content_Types].xml", names.first())
        assertEquals(deck.parts.keys.toList(), names)
        listOf(
            "_rels/.rels", "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "ppt/theme/theme1.xml",
            "ppt/slideMasters/slideMaster1.xml", "ppt/slideLayouts/slideLayout1.xml",
            "ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/slides/_rels/slide1.xml.rels", "ppt/media/image1.png",
        ).forEach { assertTrue("missing $it", it in names) }
    }

    @Test
    fun `every xml part is well formed`() {
        xmlParts(sample()).forEach { (name, bytes) ->
            assertTrue("$name is empty", parse(bytes).isNotEmpty())
        }
    }

    @Test
    fun `every part has a content type and every override names a part`() {
        val deck = sample()
        val types = deck.elements("[Content_Types].xml")
        val defaults = types.named("Default").map { it.attrs.getValue("Extension") }.toSet()
        val overrides = types.named("Override").map { it.attrs.getValue("PartName").removePrefix("/") }.toSet()
        deck.parts.keys.filter { it != "[Content_Types].xml" }.forEach { name ->
            assertTrue("no content type for $name", name in overrides || name.substringAfterLast('.') in defaults)
        }
        overrides.forEach { assertTrue("override for a missing part: $it", it in deck.parts) }
    }

    @Test
    fun `every relationship target exists`() {
        val deck = sample()
        deck.parts.keys.filter { it.endsWith(".rels") }.forEach { rels ->
            deck.elements(rels).named("Relationship").forEach {
                val target = targetOf(rels, it.attrs.getValue("Target"))
                assertTrue("$rels points at a missing part: $target", target in deck.parts)
            }
        }
    }

    @Test
    fun `every relationship id a part uses is declared in its rels`() {
        val deck = sample()
        deck.parts.keys.filter { it.endsWith(".xml") && !it.startsWith("[") }.forEach { part ->
            val relsName = part.substringBeforeLast('/') + "/_rels/" + part.substringAfterLast('/') + ".rels"
            val declared = deck.parts[relsName]?.let { parse(it).named("Relationship").map { r -> r.attrs.getValue("Id") } }.orEmpty()
            val used = deck.elements(part).flatMap { e -> e.attrs.filterKeys { it.startsWith("r:") }.values }
            used.forEach { assertTrue("$part uses $it, which $relsName does not declare", it in declared) }
        }
    }

    @Test
    fun `the presentation lists its slides and master`() {
        val deck = sample()
        val presentation = deck.elements("ppt/presentation.xml")
        assertEquals(2, presentation.named("p:sldId").size)
        assertEquals(1, presentation.named("p:sldMasterId").size)
        val size = presentation.named("p:sldSz").single().attrs
        assertEquals("12192000", size["cx"])
        assertEquals("6858000", size["cy"])
        val order = presentation.map { it.name }
        assertTrue(order.indexOf("p:sldMasterIdLst") < order.indexOf("p:sldIdLst"))
        assertTrue(order.indexOf("p:sldIdLst") < order.indexOf("p:sldSz"))
    }

    @Test
    fun `shape ids are unique within each shape tree`() {
        val deck = sample()
        deck.parts.keys.filter { it.startsWith("ppt/slide") && it.endsWith(".xml") && "_rels" !in it }.forEach { part ->
            val ids = deck.elements(part).named("p:cNvPr").map { it.attrs.getValue("id") }
            assertEquals("$part repeats an id", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun `slide placeholders match the layout by type and index`() {
        val deck = sample()
        val layout = deck.elements("ppt/slideLayouts/slideLayout1.xml").named("p:ph").map { it.attrs["type"] to it.attrs["idx"] }
        val slide = deck.elements("ppt/slides/slide1.xml").named("p:ph").map { it.attrs["type"] to it.attrs["idx"] }
        assertEquals(listOf("title" to "0", "body" to "1"), layout)
        assertEquals(listOf("title" to null, "body" to "1"), slide)
    }

    @Test
    fun `the plan example carries its text, levels and fill`() {
        val slide = sample().elements("ppt/slides/slide1.xml")
        assertEquals(listOf("Hello", "First", "Nested"), slide.named("a:t").map { it.text })
        assertEquals(listOf("1"), slide.named("a:pPr").map { it.attrs.getValue("lvl") })
        assertEquals("roundRect", slide.named("a:prstGeom").single().attrs["prst"])
        assertEquals("accent1", slide.named("a:schemeClr").single().attrs["val"])
        assertEquals("5000000", slide.named("a:off").single { it.attrs["x"] == "1000000" }.attrs["y"])
    }

    @Test
    fun `the layout frame, theme and master style are written as given`() {
        val deck = sample()
        val frame = deck.elements("ppt/slideLayouts/slideLayout1.xml").named("a:off").first { it.attrs["x"] != "0" }.attrs
        assertEquals("838200" to "365125", frame["x"] to frame["y"])
        val theme = deck.elements("ppt/theme/theme1.xml")
        assertEquals("4472C4", theme.named("a:accent1").let { theme[theme.indexOf(it.single()) + 1] }.attrs["val"])
        assertEquals(listOf("Calibri Light", "Calibri"), theme.named("a:latin").map { it.attrs["typeface"] })
        val master = deck.elements("ppt/slideMasters/slideMaster1.xml")
        val title = master.dropWhile { it.name != "p:titleStyle" }.first { it.name == "a:defRPr" }
        assertEquals("4400", title.attrs["sz"])
        assertEquals(9, master.dropWhile { it.name != "p:bodyStyle" }.takeWhile { it.name != "p:otherStyle" }.named("a:buChar").size)
    }

    @Test
    fun `a colour map override reaches the master`() {
        val deck = pptx { master { clrMap("bg1" to "dk1", "tx1" to "lt1") } }
        val map = deck.elements("ppt/slideMasters/slideMaster1.xml").named("p:clrMap").single().attrs
        assertEquals("dk1", map["bg1"])
        assertEquals("lt1", map["tx1"])
        assertEquals("accent1", map["accent1"])
    }

    @Test
    fun `text is escaped and reads back unchanged`() {
        val text = "A & B <c> \"d\" é"
        val deck = pptx { layout("L"); slide { textBox(0, 0, 100, 100) { para(text) } } }
        assertEquals(text, deck.elements("ppt/slides/slide1.xml").named("a:t").single().text)
    }

    @Test
    fun `a text body writes its anchor, insets and autofit`() {
        val deck = pptx {
            layout("L")
            slide {
                textBox(0, 0, 100, 100) { anchor("ctr"); wrap(false); insets(1, 2, 3, 4); shrinkOnOverflow(90, 20); para("x") }
            }
        }
        val body = deck.elements("ppt/slides/slide1.xml").named("a:bodyPr").single().attrs
        assertEquals(mapOf("wrap" to "none", "lIns" to "1", "tIns" to "2", "rIns" to "3", "bIns" to "4", "anchor" to "ctr"), body)
        val fit = deck.elements("ppt/slides/slide1.xml").named("a:normAutofit").single().attrs
        assertEquals("90000", fit["fontScale"])
        assertEquals("20000", fit["lnSpcReduction"])
    }

    @Test
    fun `a table has a grid, merged cells and rows that cover it`() {
        val table = sample().elements("ppt/slides/slide2.xml")
        assertEquals(listOf("1000000", "2000000"), table.named("a:gridCol").map { it.attrs["w"] })
        assertEquals(2, table.named("a:tr").size)
        assertEquals("2", table.named("a:tc").first { "gridSpan" in it.attrs }.attrs["gridSpan"])
        assertEquals(1, table.named("a:tc").count { "hMerge" in it.attrs })
        assertEquals("800000", table.named("a:ext").single { it.attrs["cx"] == "3000000" }.attrs["cy"])
        assertThrows(IllegalArgumentException::class.java) {
            pptx { layout("L"); slide { table(0, 0, listOf(10, 10)) { row(10) { cell("only one") } } } }
        }
    }

    @Test
    fun `a picture is stored as media and referenced from its slide`() {
        val deck = sample()
        assertArrayEquals(OfficeFixtures.PNG_1X1, deck.parts.getValue("ppt/media/image1.png"))
        val embed = deck.elements("ppt/slides/slide2.xml").named("a:blip").single().attrs.getValue("r:embed")
        val rel = deck.elements("ppt/slides/_rels/slide2.xml.rels").named("Relationship").single { it.attrs["Id"] == embed }
        assertEquals("ppt/media/image1.png", targetOf("ppt/slides/_rels/slide2.xml.rels", rel.attrs.getValue("Target")))
        assertTrue(deck.elements("[Content_Types].xml").named("Default").any { it.attrs["Extension"] == "png" })
    }

    @Test
    fun `a hidden slide and a first slide number are written`() {
        val deck = pptx { firstSlideNumber(5); layout("L"); slide(hidden = true) }
        assertEquals("0", deck.elements("ppt/slides/slide1.xml").named("p:sld").single().attrs["show"])
        assertEquals("5", deck.elements("ppt/presentation.xml").named("p:presentation").single().attrs["firstSlideNum"])
    }

    @Test
    fun `a deck without layouts or slides still opens`() {
        val deck = pptx { }
        assertEquals(1, deck.elements("ppt/slideMasters/slideMaster1.xml").named("p:sldLayoutId").size)
        assertTrue(deck.elements("ppt/presentation.xml").named("p:sldIdLst").isEmpty())
        assertTrue("ppt/slideLayouts/slideLayout1.xml" in deck.parts)
    }

    @Test
    fun `invalid input is refused instead of written`() {
        assertThrows(IllegalArgumentException::class.java) { pptx { theme { accent1("blue") } } }
        assertThrows(IllegalArgumentException::class.java) { pptx { layout("L"); slide(layout = 3) } }
        assertThrows(IllegalArgumentException::class.java) { pptx { master { clrMap("nope" to "dk1") } } }
        assertThrows(IllegalArgumentException::class.java) {
            pptx { layout("L"); slide { shape(Preset.RECT, 0, 0, 1, 1) { fillRgb("12") } } }
        }
    }

    @Test
    fun `building twice gives the same parts`() {
        assertEquals(sample().parts.keys, sample().parts.keys)
        sample().parts.forEach { (name, bytes) -> assertArrayEquals(name, bytes, sample().parts.getValue(name)) }
        assertFalse(sample().parts.isEmpty())
    }
}
