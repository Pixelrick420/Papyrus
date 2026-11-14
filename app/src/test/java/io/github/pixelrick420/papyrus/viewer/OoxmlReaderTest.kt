package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlCursor
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlName
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlReader
import io.github.pixelrick420.papyrus.viewer.pptx.PptxFormatException
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

class OoxmlReaderTest {

    private fun structure(xml: String): List<String> = structure(OoxmlReader(OfficeFixtures::parser), xml)

    private fun structure(reader: OoxmlReader, xml: String): List<String> {
        val found = mutableListOf<String>()
        val cursor = reader.open(xml.byteInputStream(), "test.xml")
        while (cursor.next() != XmlPullParser.END_DOCUMENT) {
            when (cursor.eventType) {
                XmlPullParser.START_TAG -> {
                    val attributes = cursor.attributes.joinToString { "${it.namespace}|${it.localName}=${it.value}" }
                    found += "start ${cursor.name.namespace}|${cursor.name.localName} $attributes"
                }
                XmlPullParser.END_TAG -> found += "end ${cursor.name.namespace}|${cursor.name.localName}"
                XmlPullParser.TEXT -> cursor.text.trim().takeIf { it.isNotEmpty() }?.let { found += "text $it" }
            }
        }
        return found
    }

    @Test
    fun `a strict part reads the same as its transitional twin`() {
        val transitional = """
            <p:presentation xmlns:p="${OoxmlNamespace.P}" xmlns:a="${OoxmlNamespace.A}" xmlns:r="${OoxmlNamespace.R}">
                <p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>
                <p:defaultTextStyle><a:lvl1pPr marL="0"><a:defRPr sz="1800">Text</a:defRPr></a:lvl1pPr></p:defaultTextStyle>
            </p:presentation>
        """.trimIndent()
        val strict = """
            <p:presentation xmlns:p="http://purl.oclc.org/ooxml/presentationml/main"
                xmlns:a="http://purl.oclc.org/ooxml/drawingml/main"
                xmlns:r="http://purl.oclc.org/ooxml/officeDocument/relationships">
                <p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>
                <p:defaultTextStyle><a:lvl1pPr marL="0"><a:defRPr sz="1800">Text</a:defRPr></a:lvl1pPr></p:defaultTextStyle>
            </p:presentation>
        """.trimIndent()

        assertEquals(structure(transitional), structure(strict))
    }

    @Test
    fun `a part read with a prefix of its own still names the same namespaces`() {
        val xml = """
            <deck:presentation xmlns:deck="${OoxmlNamespace.P}" xmlns:draw="${OoxmlNamespace.A}">
                <deck:sldIdLst><draw:blip/></deck:sldIdLst>
            </deck:presentation>
        """.trimIndent()

        assertEquals(
            listOf(
                "start ${OoxmlNamespace.P}|presentation ",
                "start ${OoxmlNamespace.P}|sldIdLst ",
                "start ${OoxmlNamespace.A}|blip ",
                "end ${OoxmlNamespace.A}|blip",
                "end ${OoxmlNamespace.P}|sldIdLst",
                "end ${OoxmlNamespace.P}|presentation",
            ),
            structure(xml),
        )
    }

    @Test
    fun `a choice is taken when every namespace it requires is implemented`() {
        val xml = """
            <p:sp xmlns:p="${OoxmlNamespace.P}" xmlns:a="${OoxmlNamespace.A}"
                xmlns:mc="${OoxmlNamespace.MC}" xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main">
                <mc:AlternateContent>
                    <mc:Choice Requires="a14"><a:blip>equation</a:blip></mc:Choice>
                    <mc:Fallback><a:blip>picture</a:blip></mc:Fallback>
                </mc:AlternateContent>
            </p:sp>
        """.trimIndent()

        val reader = OoxmlReader(OfficeFixtures::parser, implemented = OoxmlNamespace.CORE + "http://schemas.microsoft.com/office/drawing/2010/main")

        assertEquals(
            listOf(
                "start ${OoxmlNamespace.P}|sp ",
                "start ${OoxmlNamespace.A}|blip ",
                "text equation",
                "end ${OoxmlNamespace.A}|blip",
                "end ${OoxmlNamespace.P}|sp",
            ),
            structure(reader, xml),
        )
    }

    @Test
    fun `a choice requiring a namespace the reader does not implement falls back`() {
        val xml = """
            <p:sp xmlns:p="${OoxmlNamespace.P}" xmlns:a="${OoxmlNamespace.A}"
                xmlns:mc="${OoxmlNamespace.MC}" xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main">
                <mc:AlternateContent>
                    <mc:Choice Requires="a14"><a:blip>equation</a:blip></mc:Choice>
                    <mc:Fallback><a:blip>picture</a:blip></mc:Fallback>
                </mc:AlternateContent>
            </p:sp>
        """.trimIndent()

        assertEquals(
            listOf(
                "start ${OoxmlNamespace.P}|sp ",
                "start ${OoxmlNamespace.A}|blip ",
                "text picture",
                "end ${OoxmlNamespace.A}|blip",
                "end ${OoxmlNamespace.P}|sp",
            ),
            structure(xml),
        )
    }

    @Test
    fun `one unimplemented namespace in requires is enough to fall back`() {
        val xml = """
            <p:sp xmlns:p="${OoxmlNamespace.P}" xmlns:a="${OoxmlNamespace.A}"
                xmlns:mc="${OoxmlNamespace.MC}" xmlns:svg="http://schemas.microsoft.com/office/drawing/2016/SVG/main">
                <mc:AlternateContent>
                    <mc:Choice Requires="p svg"><a:blip>svg</a:blip></mc:Choice>
                    <mc:Fallback><a:blip>picture</a:blip></mc:Fallback>
                </mc:AlternateContent>
            </p:sp>
        """.trimIndent()

        assertEquals(
            listOf(
                "start ${OoxmlNamespace.P}|sp ",
                "start ${OoxmlNamespace.A}|blip ",
                "text picture",
                "end ${OoxmlNamespace.A}|blip",
                "end ${OoxmlNamespace.P}|sp",
            ),
            structure(xml),
        )
    }

    @Test
    fun `the chosen branch keeps its own attributes and nested alternate content`() {
        val xml = """
            <p:sp xmlns:p="${OoxmlNamespace.P}" xmlns:a="${OoxmlNamespace.A}"
                xmlns:mc="${OoxmlNamespace.MC}" xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main">
                <mc:AlternateContent>
                    <mc:Choice Requires="p">
                        <a:blip r:embed="rId1" xmlns:r="${OoxmlNamespace.R}">
                            <mc:AlternateContent>
                                <mc:Choice Requires="a14"><a:ext>equation</a:ext></mc:Choice>
                                <mc:Fallback><a:ext>picture</a:ext></mc:Fallback>
                            </mc:AlternateContent>
                        </a:blip>
                    </mc:Choice>
                    <mc:Fallback><a:blip>fallback</a:blip></mc:Fallback>
                </mc:AlternateContent>
            </p:sp>
        """.trimIndent()

        assertEquals(
            listOf(
                "start ${OoxmlNamespace.P}|sp ",
                "start ${OoxmlNamespace.A}|blip ${OoxmlNamespace.R}|embed=rId1",
                "start ${OoxmlNamespace.A}|ext ",
                "text picture",
                "end ${OoxmlNamespace.A}|ext",
                "end ${OoxmlNamespace.A}|blip",
                "end ${OoxmlNamespace.P}|sp",
            ),
            structure(xml),
        )
    }

    @Test
    fun `namespaces the root marks ignorable are skipped with their subtree`() {
        val xml = """
            <p:presentation xmlns:p="${OoxmlNamespace.P}" xmlns:mc="${OoxmlNamespace.MC}"
                xmlns:p14="http://schemas.microsoft.com/office/powerpoint/2010/main" mc:Ignorable="p14">
                <p:sldIdLst/>
                <p14:creationId><p14:deep/></p14:creationId>
                <p:notes/>
            </p:presentation>
        """.trimIndent()

        assertEquals(
            listOf(
                "start ${OoxmlNamespace.P}|presentation ${OoxmlNamespace.MC}|Ignorable=p14",
                "start ${OoxmlNamespace.P}|sldIdLst ",
                "end ${OoxmlNamespace.P}|sldIdLst",
                "start ${OoxmlNamespace.P}|notes ",
                "end ${OoxmlNamespace.P}|notes",
                "end ${OoxmlNamespace.P}|presentation",
            ),
            structure(xml),
        )
    }

    @Test
    fun `the element budget fails the part at its boundary`() {
        val xml = """
            <p:presentation xmlns:p="${OoxmlNamespace.P}">
                <p:sldIdLst/>
                <p:notes/>
            </p:presentation>
        """.trimIndent()

        structure(OoxmlReader(OfficeFixtures::parser, limits = PptxLimits(maxXmlElements = 3)), xml)

        val failure = runCatching {
            structure(OoxmlReader(OfficeFixtures::parser, limits = PptxLimits(maxXmlElements = 2)), xml)
        }.exceptionOrNull()

        assertTrue(failure is PptxFormatException)
        assertEquals("test.xml has more than 2 elements", failure?.message)
    }

    @Test
    fun `the depth budget fails the part at its boundary`() {
        val xml = """
            <p:sp xmlns:p="${OoxmlNamespace.P}" xmlns:a="${OoxmlNamespace.A}">
                <p:txBody><a:p/></p:txBody>
            </p:sp>
        """.trimIndent()

        structure(OoxmlReader(OfficeFixtures::parser, limits = PptxLimits(maxXmlDepth = 3)), xml)

        val failure = runCatching {
            structure(OoxmlReader(OfficeFixtures::parser, limits = PptxLimits(maxXmlDepth = 2)), xml)
        }.exceptionOrNull()

        assertTrue(failure is PptxFormatException)
        assertEquals("test.xml is nested deeper than 2", failure?.message)
    }

    @Test
    fun `a doctype that declares an entity fails the part rather than expanding it`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE p:presentation [<!ENTITY boom "boom">]>
            <p:presentation xmlns:p="${OoxmlNamespace.P}">&boom;</p:presentation>
        """.trimIndent()

        val failure = runCatching { structure(xml) }.exceptionOrNull()

        assertTrue(failure is PptxFormatException)
        assertEquals("test.xml is not valid xml", failure?.message)
    }

    @Test
    fun `an undefined entity fails the part`() {
        val xml = """<p:presentation xmlns:p="${OoxmlNamespace.P}">&xxe;</p:presentation>"""

        val failure = runCatching { structure(xml) }.exceptionOrNull()

        assertTrue(failure is PptxFormatException)
        assertEquals("test.xml is not valid xml", failure?.message)
    }

    @Test
    fun `a doctype that declares nothing still reads`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE p:presentation>
            <p:presentation xmlns:p="${OoxmlNamespace.P}"><p:sldIdLst/></p:presentation>
        """.trimIndent()

        assertEquals(
            listOf(
                "start ${OoxmlNamespace.P}|presentation ",
                "start ${OoxmlNamespace.P}|sldIdLst ",
                "end ${OoxmlNamespace.P}|sldIdLst",
                "end ${OoxmlNamespace.P}|presentation",
            ),
            structure(xml),
        )
    }

    @Test
    fun `readTree keeps names, attributes, children and text`() {
        val xml = """
            <a:lstStyle xmlns:a="${OoxmlNamespace.A}" xmlns:r="${OoxmlNamespace.R}">
                <a:lvl1pPr marL="0" r:embed="rId1"><a:defRPr sz="1800">Text</a:defRPr></a:lvl1pPr>
            </a:lstStyle>
        """.trimIndent()

        val root = OoxmlReader(OfficeFixtures::parser).readTree(xml.byteInputStream(), "test.xml")

        assertNotNull(root)
        assertEquals(OoxmlName(OoxmlNamespace.A, "lstStyle"), root?.name)
        val level = root?.child(OoxmlNamespace.A, "lvl1pPr")
        assertNotNull(level)
        assertEquals("0", level?.attribute("marL"))
        assertEquals("rId1", level?.attribute(OoxmlNamespace.R, "embed"))
        assertEquals("Text", level?.child(OoxmlNamespace.A, "defRPr")?.text)
        assertEquals(1, root?.childrenOf(OoxmlNamespace.A, "lvl1pPr")?.size)
    }

    @Test
    fun `skipSubtree leaves the stream after the skipped element`() {
        val xml = """
            <p:sp xmlns:p="${OoxmlNamespace.P}" xmlns:a="${OoxmlNamespace.A}">
                <p:txBody><a:p/></p:txBody>
                <p:notes/>
            </p:sp>
        """.trimIndent()

        val cursor = OoxmlReader(OfficeFixtures::parser).open(xml.byteInputStream(), "test.xml")

        assertEquals(XmlPullParser.START_TAG, cursor.next())
        assertEquals(XmlPullParser.START_TAG, nextTag(cursor))
        cursor.skipSubtree()
        assertEquals(XmlPullParser.START_TAG, nextTag(cursor))
        assertTrue(cursor.name.matches(OoxmlNamespace.P, "notes"))
    }

    private fun nextTag(cursor: OoxmlCursor): Int {
        var event = cursor.next()
        while (event == XmlPullParser.TEXT) event = cursor.next()
        return event
    }

    @Test
    fun `a strict relationship attribute maps onto the transitional one`() {
        val cursor = OoxmlReader(OfficeFixtures::parser).open(
            """<p:pic xmlns:p="${OoxmlNamespace.P}" xmlns:r="http://purl.oclc.org/ooxml/officeDocument/relationships" r:embed="rId4"/>"""
                .byteInputStream(),
            "test.xml",
        )

        assertEquals(XmlPullParser.START_TAG, cursor.next())
        assertEquals(OoxmlNamespace.P, cursor.name.namespace)
        assertEquals("rId4", cursor.attribute(OoxmlNamespace.R, "embed"))
    }
}
