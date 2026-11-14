package io.github.pixelrick420.papyrus.viewer.svg

import io.github.pixelrick420.papyrus.viewer.EpubFixtures
import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SvgDataUriTest {

    private val svg = EpubFixtures.svg("""<rect width="10" height="10" fill="red"/>""")

    private fun base64(text: String, params: String = ";base64") =
        "data:image/svg+xml$params,${Base64.getEncoder().encodeToString(text.toByteArray())}"

    private fun escaped(text: String) = "data:image/svg+xml," + text.map { c ->
        if (c.isLetterOrDigit() || c in "-_.~/ ") c.toString() else c.toString().toByteArray().joinToString("") { "%%%02X".format(it) }
    }.joinToString("").replace(" ", "%20")

    private fun render(raw: String, rasteriser: SvgRasteriser = EpubFixtures.FakeRasteriser()) =
        renderSvgDataUri(raw, { stream -> OfficeFixtures.parser().apply { setInput(stream, null) } }, rasteriser)

    @Test
    fun `a base64 uri decodes to the drawing`() {
        assertEquals(svg, SvgDataUri.decode(base64(svg))!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `a percent-escaped uri decodes to the drawing`() {
        assertEquals(svg, SvgDataUri.decode(escaped(svg))!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `text that is not escaped at all is taken as it is, as a browser takes it`() {
        val plain = """data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg"><rect width="100%" height="5"/></svg>"""

        assertEquals(
            """<svg xmlns="http://www.w3.org/2000/svg"><rect width="100%" height="5"/></svg>""",
            SvgDataUri.decode(plain)!!.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `a percent sign that starts no escape stays a percent sign`() {
        for ((raw, expected) in listOf(
            "data:image/svg+xml,100%" to "100%",
            "data:image/svg+xml,a%zzb" to "a%zzb",
            "data:image/svg+xml,a%4" to "a%4",
            "data:image/svg+xml,a%%41" to "a%A",
            "data:image/svg+xml,%41%42%43" to "ABC",
        )) {
            assertEquals(raw, expected, SvgDataUri.decode(raw)!!.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `a plus sign is a plus sign, not a space, and characters beyond ascii survive both ways`() {
        assertEquals("a+b", SvgDataUri.decode("data:image/svg+xml,a+b")!!.toString(Charsets.UTF_8))
        assertEquals("\u20ac", SvgDataUri.decode("data:image/svg+xml,%E2%82%AC")!!.toString(Charsets.UTF_8))
        assertEquals("\u20ac", SvgDataUri.decode("data:image/svg+xml,\u20ac")!!.toString(Charsets.UTF_8))
        assertEquals("\uD83D\uDE00", SvgDataUri.decode("data:image/svg+xml,\uD83D\uDE00")!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `parameters, case and a wrapped payload do not matter`() {
        val expected = svg.toByteArray()
        val wrapped = Base64.getMimeEncoder(8, "\r\n".toByteArray()).encodeToString(expected)

        assertArrayEquals(expected, SvgDataUri.decode(base64(svg, ";charset=utf-8;base64")))
        assertArrayEquals(expected, SvgDataUri.decode("DATA:IMAGE/SVG+XML;BASE64,${Base64.getEncoder().encodeToString(expected)}"))
        assertArrayEquals(expected, SvgDataUri.decode("data:image/svg+xml;base64,$wrapped"))
        assertArrayEquals(expected, SvgDataUri.decode("data: image/svg+xml ; base64 ,${Base64.getEncoder().encodeToString(expected)}"))
    }

    @Test
    fun `base64 may carry its padding and its plus and slash as percent escapes, or lack its padding`() {
        val bytes = "\u00fb\u00ff\u00fe??>>".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        assertTrue(encoded.contains('+') || encoded.contains('/') || encoded.endsWith("="))
        val escapedForm = encoded.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")

        assertArrayEquals(bytes, SvgDataUri.decode("data:image/svg+xml;base64,$escapedForm"))
        assertArrayEquals(bytes, SvgDataUri.decode("data:image/svg+xml;base64,${encoded.trimEnd('=')}"))
    }

    @Test
    fun `only an svg media type is one`() {
        assertTrue(SvgDataUri.isSvg(base64(svg)))
        assertTrue(SvgDataUri.isSvg("data:image/svg+xml,%3Csvg/%3E"))
        for (other in listOf(
            "data:image/png;base64,iVBORw0KGgo=",
            "data:image/jpeg;base64,/9j/4AAQ",
            "data:text/plain,hello",
            "data:;base64,AAAA",
            "data:,svg",
            "data:image/svg+xml-compressed,abc",
            "data:image/svg,abc",
            "data:application/xml,<svg/>",
            "https://example.com/a.svg",
            "file:///sdcard/a.svg",
            "image/svg+xml,<svg/>",
            "",
        )) {
            assertFalse(other, SvgDataUri.isSvg(other))
            assertNull(other, SvgDataUri.decode(other))
        }
    }

    @Test
    fun `an svg data uri with no data part has no bytes`() {
        assertNull(SvgDataUri.decode("data:image/svg+xml"))
        assertNull(SvgDataUri.decode("data:image/svg+xml;base64"))
        assertEquals(0, SvgDataUri.decode("data:image/svg+xml,")!!.size)
        assertEquals(0, SvgDataUri.decode("data:image/svg+xml;base64,")!!.size)
    }

    @Test
    fun `base64 with anything off its alphabet, or broken padding, has no bytes`() {
        for (bad in listOf("!!!!", "AAAA*AAA", "A", "AAAAA", "AA=A", "====", "PHN2Zz4=!", "\u00e9\u00e9\u00e9\u00e9")) {
            assertNull(bad, SvgDataUri.decode("data:image/svg+xml;base64,$bad"))
        }
    }

    @Test
    fun `the limit is on the drawing decoded, and a drawing exactly at it is kept`() {
        val bytes = ByteArray(300) { 'a'.code.toByte() }
        val b64 = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(bytes)
        val escapedForm = "data:image/svg+xml," + "%61".repeat(300)
        val literal = "data:image/svg+xml," + "a".repeat(300)

        for (raw in listOf(b64, escapedForm, literal)) {
            assertEquals(raw.take(30), 300, SvgDataUri.decode(raw, maxBytes = 300)!!.size)
            assertNull(raw.take(30), SvgDataUri.decode(raw, maxBytes = 299))
        }
    }

    @Test
    fun `characters that take several bytes to write are counted as bytes`() {
        // 100 euro signs are 100 characters but 300 bytes.
        val raw = "data:image/svg+xml," + "\u20ac".repeat(100)

        assertEquals(300, SvgDataUri.decode(raw, maxBytes = 300)!!.size)
        assertNull(SvgDataUri.decode(raw, maxBytes = 299))
    }

    @Test
    fun `an enormous string is refused without being decoded`() {
        val raw = "data:image/svg+xml;base64," + "A".repeat(SvgDataUri.MAX_BYTES * 2)

        assertNull(SvgDataUri.decode(raw))
        assertNull(SvgDataUri.decode("data:image/svg+xml," + "a".repeat(SvgDataUri.MAX_BYTES * 4)))
    }

    @Test
    fun `a drawing is rendered to the rasteriser's picture`() {
        val png = byteArrayOf(1, 2, 3)
        val rasteriser = EpubFixtures.FakeRasteriser(result = png)

        for (raw in listOf(base64(svg), escaped(svg))) {
            assertArrayEquals(raw.take(40), png, render(raw, rasteriser))
        }

        assertEquals(2, rasteriser.scenes.size)
        val scene = rasteriser.scenes.first()
        assertEquals(100f, scene.width, 0f)
        assertEquals(50f, scene.height, 0f)
        assertEquals(0xFF0000, scene.shapes.single().fill)
    }

    @Test
    fun `what cannot be shown has no picture, and the rasteriser is not asked`() {
        val rasteriser = EpubFixtures.FakeRasteriser()
        val notXml = base64("this is not xml at all")
        val broken = base64(EpubFixtures.svg("<g><rect/>").dropLast(6))
        val empty = "data:image/svg+xml,"
        val nothingDrawn = base64(EpubFixtures.svg("""<text x="1" y="1">hi</text>"""))
        val referencesOnly = base64(EpubFixtures.svg("""<image href="https://example.com/a.png" width="5" height="5"/><use href="#x"/>"""))
        val tooBig = "data:image/svg+xml;base64," + "A".repeat(SvgDataUri.MAX_BYTES * 2)

        for (raw in listOf(notXml, broken, empty, nothingDrawn, referencesOnly, tooBig, "data:image/png;base64,AAAA", "http://x/a.svg")) {
            assertNull(raw.take(50), render(raw, rasteriser))
        }
        assertTrue(rasteriser.scenes.isEmpty())
    }

    @Test
    fun `a rasteriser that cannot draw is no picture too`() {
        assertNull(render(base64(svg), EpubFixtures.FakeRasteriser(result = null)))
        assertNotNull(render(base64(svg), EpubFixtures.FakeRasteriser()))
    }
}
