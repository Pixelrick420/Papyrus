package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SVG subset on its own: what becomes geometry, what is dropped, and the caps. */
class EpubSvgTest {

    private fun scene(inner: String, attrs: String = "viewBox=\"0 0 100 50\"", limits: EpubLimits = EpubLimits()): SvgScene? =
        parseSvg(EpubFixtures.svg(inner, attrs).toByteArray(), { stream -> OfficeFixtures.parser().apply { setInput(stream, null) } }, limits)

    private fun ops(inner: String) = scene(inner)!!.shapes.single().ops

    @Test
    fun `the aspect comes from the view box, or from width and height without one`() {
        assertEquals(2f, scene("<rect width=\"1\" height=\"1\"/>")!!.aspect, 0.001f)
        assertEquals(0.5f, scene("<rect width=\"1\" height=\"1\"/>", "width=\"50px\" height=\"100\"")!!.aspect, 0.001f)
        // A view box wins: it is the space the coordinates are written in.
        assertEquals(4f, scene("<rect width=\"1\" height=\"1\"/>", "viewBox=\"0 0 400 100\" width=\"10\" height=\"10\"")!!.aspect, 0.001f)
    }

    @Test
    fun `a missing size is taken from what is drawn`() {
        val s = scene("<rect x=\"0\" y=\"0\" width=\"30\" height=\"10\"/>", "")!!
        assertEquals(30f, s.width, 0.001f)
        assertEquals(10f, s.height, 0.001f)
        // One side alone says nothing about the shape.
        assertEquals(3f, scene("<rect width=\"30\" height=\"10\"/>", "width=\"500\"")!!.aspect, 0.001f)
    }

    @Test
    fun `a view box that does not start at the origin moves the drawing to it`() {
        val first = scene("<rect x=\"100\" y=\"100\" width=\"10\" height=\"10\"/>", "viewBox=\"100 100 50 50\"")!!.shapes.single().ops.first()
        assertEquals(PathOp.MoveTo(0f, 0f), first)
    }

    @Test
    fun `every primitive becomes lines`() {
        assertEquals(
            listOf(PathOp.MoveTo(1f, 2f), PathOp.LineTo(11f, 2f), PathOp.LineTo(11f, 7f), PathOp.LineTo(1f, 7f), PathOp.Close),
            ops("<rect x=\"1\" y=\"2\" width=\"10\" height=\"5\"/>"),
        )
        assertEquals(listOf(PathOp.MoveTo(1f, 2f), PathOp.LineTo(3f, 4f)), ops("<line x1=\"1\" y1=\"2\" x2=\"3\" y2=\"4\" stroke=\"red\"/>"))
        assertEquals(
            listOf(PathOp.MoveTo(0f, 0f), PathOp.LineTo(10f, 0f), PathOp.LineTo(10f, 10f)),
            ops("<polyline points=\"0,0 10,0 10,10\"/>"),
        )
        assertEquals(
            listOf(PathOp.MoveTo(0f, 0f), PathOp.LineTo(10f, 0f), PathOp.LineTo(10f, 10f), PathOp.Close),
            ops("<polygon points=\"0 0 10 0 10 10\"/>"),
        )
    }

    @Test
    fun `circles and ellipses are thirty two sided polygons`() {
        val circle = ops("<circle cx=\"20\" cy=\"20\" r=\"10\"/>")
        assertEquals(33, circle.size)
        assertEquals(PathOp.Close, circle.last())
        assertEquals(PathOp.MoveTo(30f, 20f), circle.first())
        val ellipse = ops("<ellipse cx=\"20\" cy=\"20\" rx=\"10\" ry=\"5\"/>")
        assertEquals(33, ellipse.size)
        assertEquals(PathOp.MoveTo(30f, 20f), ellipse.first())
    }

    @Test
    fun `a degenerate primitive draws nothing`() {
        assertNull(scene("<rect width=\"0\" height=\"5\"/><circle r=\"0\"/><polygon points=\"1,2\"/><polyline points=\"a b c d\"/>"))
    }

    @Test
    fun `a path of moves lines horizontals verticals and closes is read`() {
        assertEquals(
            listOf(PathOp.MoveTo(0f, 0f), PathOp.LineTo(10f, 0f), PathOp.LineTo(10f, 5f), PathOp.LineTo(0f, 5f), PathOp.Close),
            ops("<path d=\"M0,0 H10 V5 L0 5 Z\"/>"),
        )
        // Relative commands, implicit lines after a move and numbers run together.
        assertEquals(
            listOf(PathOp.MoveTo(1f, 1f), PathOp.LineTo(3f, 1f), PathOp.LineTo(3f, 4f), PathOp.LineTo(2f, 4f), PathOp.Close),
            ops("<path d=\"m1 1 2 0 0 3h-1z\"/>"),
        )
        assertEquals(2, ops("<path d=\"M0 0L1e1 -1.5\"/>").size)
    }

    @Test
    fun `a closed subpath returns to where it began`() {
        val s = scene("<path d=\"M10 10 l5 0 z l0 5\"/>")!!.shapes.single().ops
        assertEquals(PathOp.LineTo(10f, 15f), s.last())
    }

    @Test
    fun `a path with a curve or an arc is dropped whole and its neighbours are not`() {
        val s = scene("<path d=\"M0 0 C 1 1 2 2 3 3\"/><path d=\"M0 0 L 5 5 A 2 2 0 0 1 9 9\"/><path d=\"M0 0 Q1 1 2 2\"/><path d=\"M0 0 L 5 5\"/>")!!
        assertEquals(1, s.shapes.size)
        assertEquals(listOf(PathOp.MoveTo(0f, 0f), PathOp.LineTo(5f, 5f)), s.shapes.single().ops)
    }

    @Test
    fun `a path that is not a path is dropped`() {
        assertNull(scene("<path d=\"L 1 1\"/><path d=\"M\"/><path d=\"M 1\"/><path d=\"M a b\"/><path d=\"\"/><path/><path d=\"M1 1 X 2 2\"/>"))
    }

    @Test
    fun `translates accumulate and any other transform drops the element and its children`() {
        val s = scene(
            """<g transform="translate(5,5)"><g transform="translate(10)"><rect width="2" height="2"/></g>
               <g transform="rotate(45)"><rect width="9" height="9"/><circle r="3"/></g>
               <rect width="1" height="1" transform="scale(2)"/>
               <rect width="3" height="3" transform="translate(1 1)"/></g>""",
        )!!
        assertEquals(2, s.shapes.size)
        assertEquals(PathOp.MoveTo(15f, 5f), s.shapes[0].ops.first())
        assertEquals(PathOp.MoveTo(6f, 6f), s.shapes[1].ops.first())
    }

    @Test
    fun `a stroke with no fill is a stroked shape`() {
        val shape = scene("<rect width=\"4\" height=\"4\" fill=\"none\" stroke=\"#00f\" stroke-width=\"3\"/>")!!.shapes.single()
        assertNull(shape.fill)
        assertEquals(0x0000FF, shape.stroke)
        assertEquals(3f, shape.strokeWidth, 0.001f)
        assertTrue(shape.closed)
    }

    @Test
    fun `a shape with nothing to paint is not kept`() {
        assertNull(scene("<rect width=\"4\" height=\"4\" fill=\"none\"/><line x1=\"0\" y1=\"0\" x2=\"4\" y2=\"4\"/>"))
    }

    @Test
    fun `fill and stroke are inherited through groups and a style attribute counts`() {
        val s = scene("""<g fill="#112233" stroke="red"><rect width="2" height="2"/><rect width="2" height="2" fill="none"/></g><rect width="1" height="1" style="fill:#fff;stroke:none"/>""")!!
        assertEquals(0x112233, s.shapes[0].fill)
        assertEquals(0xFF0000, s.shapes[0].stroke)
        assertNull(s.shapes[1].fill)
        assertEquals(0xFFFFFF, s.shapes[2].fill)
        assertNull(s.shapes[2].stroke)
    }

    @Test
    fun `colours are read in the forms books use`() {
        fun fill(value: String) = scene("<rect width=\"1\" height=\"1\" fill=\"$value\"/>")!!.shapes.single().fill
        assertEquals(0xFF0000, fill("#f00"))
        assertEquals(0x336699, fill("#336699"))
        assertEquals(0x0A141E, fill("rgb(10, 20, 30)"))
        assertEquals(0xFF0000, fill("RED"))
        // A gradient or a name it does not know is a grey, not an invisible shape.
        assertEquals(0x808080, fill("url(#g)"))
    }

    @Test
    fun `colourless art is tintable and any named colour is not`() {
        assertTrue(scene("<rect width=\"1\" height=\"1\"/>")!!.tintable)
        assertTrue(scene("<rect width=\"1\" height=\"1\" fill=\"currentColor\"/>")!!.tintable)
        assertTrue(scene("<rect width=\"1\" height=\"1\" fill=\"none\" stroke=\"currentColor\"/>")!!.tintable)
        assertFalse(scene("<rect width=\"1\" height=\"1\" fill=\"#f00\"/>")!!.tintable)
        assertFalse(scene("<rect width=\"1\" height=\"1\"/><circle r=\"2\" stroke=\"black\"/>")!!.tintable)
    }

    @Test
    fun `text and the definition elements draw nothing and neither do their children`() {
        assertNull(
            scene(
                """<text x="1" y="1"><tspan><rect width="5" height="5"/></tspan>words</text>
                   <defs><rect width="5" height="5"/></defs><clipPath id="c"><rect width="5" height="5"/></clipPath>
                   <mask><circle r="3"/></mask><title><rect width="1" height="1"/></title><style>.a{}</style>
                   <symbol><rect width="5" height="5"/></symbol><use href="#c"/><image href="a.png"/>""",
            ),
        )
    }

    @Test
    fun `an unknown element is walked for its children`() {
        assertEquals(1, scene("<a href=\"x\"><switch2><rect width=\"2\" height=\"2\"/></switch2></a>")!!.shapes.size)
    }

    @Test
    fun `a hidden element is not drawn`() {
        assertNull(scene("<rect width=\"2\" height=\"2\" display=\"none\"/><g style=\"display:none\"><rect width=\"2\" height=\"2\"/></g>"))
    }

    @Test
    fun `element and op caps stop growth without throwing`() {
        val many = (1..50).joinToString("") { "<rect width=\"1\" height=\"1\"/>" }
        assertEquals(10, scene(many, limits = EpubLimits(maxSvgElements = 11))!!.shapes.size)
        // 5 ops per rectangle: 12 ops fit two of them.
        assertEquals(2, scene(many, limits = EpubLimits(maxSvgOps = 12))!!.shapes.size)
        assertEquals(1, scene("<g><g><g><rect width=\"1\" height=\"1\"/></g></g></g><g><g><g><g><rect width=\"1\" height=\"1\"/></g></g></g></g>", limits = EpubLimits(maxDepth = 5))!!.shapes.size)
    }

    @Test
    fun `a group left open still gives what was drawn`() {
        val builder = SvgSceneBuilder()
        val none = SvgAttributeReader { null }
        builder.start("svg") { if (it == "viewBox") "0 0 10 10" else null }
        builder.start("g", none)
        builder.start("rect") { when (it) { "width", "height" -> "4"; else -> null } }
        builder.end("rect")
        // END_DOCUMENT here: neither the g nor the svg was ended.
        val scene = builder.scene()
        assertNotNull(scene)
        assertEquals(1, scene!!.shapes.size)
    }

    @Test
    fun `text is ignored`() {
        val builder = SvgSceneBuilder()
        builder.text("anything")
        assertNull(builder.scene())
    }

    @Test
    fun `a file that is not well formed throws for the caller to drop`() {
        try {
            parseSvg("<svg><g></svg>".toByteArray(), { stream -> OfficeFixtures.parser().apply { setInput(stream, null) } }, EpubLimits())
            throw AssertionError("expected a parse error")
        } catch (_: org.xmlpull.v1.XmlPullParserException) {
            // expected
        }
    }
}
