package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.ImageBounds
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlReader
import io.github.pixelrick420.papyrus.viewer.pptx.PictureCrop
import io.github.pixelrick420.papyrus.viewer.pptx.PictureFill
import io.github.pixelrick420.papyrus.viewer.pptx.PictureFills
import io.github.pixelrick420.papyrus.viewer.pptx.PictureInsets
import io.github.pixelrick420.papyrus.viewer.pptx.PictureSource
import io.github.pixelrick420.papyrus.viewer.pptx.PictureTile
import io.github.pixelrick420.papyrus.viewer.pptx.PictureTileAlignment
import io.github.pixelrick420.papyrus.viewer.pptx.PictureTileFlip
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePicture
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.ShapeTreeParser
import io.github.pixelrick420.papyrus.viewer.pptx.SlideScene
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import io.github.pixelrick420.papyrus.viewer.pptx.contentRect
import io.github.pixelrick420.papyrus.viewer.pptx.firstTile
import io.github.pixelrick420.papyrus.viewer.pptx.stretchRect
import io.github.pixelrick420.papyrus.viewer.pptx.tileOrigin
import io.github.pixelrick420.papyrus.viewer.pptx.tileSize
import io.github.pixelrick420.papyrus.viewer.pptx.visibleTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureFillTest {

    private fun read(xml: String): PictureSource? =
        PictureFills.read(parse("""<p:blipFill $NS>$xml</p:blipFill>"""))

    private fun parse(xml: String): XNode = OoxmlReader(OfficeFixtures::parser).readTree(xml.byteInputStream())!!

    private fun slide(shapes: String): SlideScene {
        val deck = pptx { slide(); part("ppt/slides/slide1.xml", SlideXml.slide(shapes)) }
        val pkg = io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)
        return ShapeTreeParser(OfficeFixtures::parser).parse(pkg, "ppt/slides/slide1.xml", io.github.pixelrick420.papyrus.viewer.pptx.SceneSize(12_192_000.0, 6_858_000.0))
    }

    @Test
    fun `a plain blip keeps its relationship and stretches with no insets`() {
        val source = read("""<a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch>""")!!

        assertEquals("rId2", source.relationshipId)
        assertEquals(1.0, source.alpha, 1e-9)
        assertEquals(PictureCrop.NONE, source.crop)
        assertTrue(source.fill is PictureFill.Stretch)
        assertEquals(PictureInsets.NONE, (source.fill as PictureFill.Stretch).insets)
    }

    @Test
    fun `a srcRect keeps its fractions including a negative side`() {
        val source = read("""<a:blip r:embed="rId2"/><a:srcRect l="10000" t="25000" r="-25000" b="5000"/>""")!!

        assertEquals(0.1, source.crop.left, 1e-9)
        assertEquals(0.25, source.crop.top, 1e-9)
        assertEquals(-0.25, source.crop.right, 1e-9)
        assertEquals(0.05, source.crop.bottom, 1e-9)
        assertEquals(1.15, source.crop.width, 1e-9)
        assertEquals(0.7, source.crop.height, 1e-9)
    }

    @Test
    fun `a fillRect inset is a fraction of the frame and may be negative`() {
        val source = read(
            """<a:blip r:embed="rId2"/><a:stretch><a:fillRect l="10000" t="-20000" r="0" b="5000"/></a:stretch>""",
        )!!
        val insets = (source.fill as PictureFill.Stretch).insets

        assertEquals(0.1, insets.left, 1e-9)
        assertEquals(-0.2, insets.top, 1e-9)
        assertEquals(0.0, insets.right, 1e-9)
        assertEquals(0.05, insets.bottom, 1e-9)
    }

    @Test
    fun `a tile keeps its offsets, scale, alignment and flip`() {
        val source = read(
            """<a:blip r:embed="rId2"/>""" +
                """<a:tile tx="19050" ty="-38100" sx="50000" sy="200000" algn="ctr" flip="xy"/>""",
        )!!
        val tile = (source.fill as PictureFill.Tile).tile

        assertEquals(19_050.0, tile.offsetXEmu, 1e-9)
        assertEquals(-38_100.0, tile.offsetYEmu, 1e-9)
        assertEquals(0.5, tile.scaleX, 1e-9)
        assertEquals(2.0, tile.scaleY, 1e-9)
        assertEquals(PictureTileAlignment.CENTRE, tile.alignment)
        assertEquals(PictureTileFlip.BOTH, tile.flip)
    }

    @Test
    fun `a tile flip is either axis, both or none`() {
        assertEquals(PictureTileFlip.HORIZONTAL, PictureTileFlip.of("x"))
        assertEquals(PictureTileFlip.VERTICAL, PictureTileFlip.of("y"))
        assertEquals(PictureTileFlip.BOTH, PictureTileFlip.of("xy"))
        assertEquals(PictureTileFlip.NONE, PictureTileFlip.of("sideways"))
        assertFalse(PictureTileFlip.NONE.horizontal)
        assertTrue(PictureTileFlip.BOTH.vertical)
    }

    @Test
    fun `alpha effects multiply and clamp`() {
        val source = read(
            """<a:blip r:embed="rId2"><a:alphaModFix amt="40000"/><a:alphaMod amt="50000"/></a:blip>""",
        )!!

        assertEquals(0.2, source.alpha, 1e-9)
        assertEquals(0.0, read("""<a:blip r:embed="rId2"><a:alphaModFix amt="-10000"/></a:blip>""")!!.alpha, 1e-9)
    }

    @Test
    fun `missing and malformed values fall back to their defaults`() {
        val source = read("""<a:blip/><a:tile sx="0" sy="nope" tx="nan"/>""")!!

        assertNull(source.relationshipId)
        val tile = (source.fill as PictureFill.Tile).tile
        assertEquals(1.0, tile.scaleX, 1e-9)
        assertEquals(1.0, tile.scaleY, 1e-9)
        assertEquals(0.0, tile.offsetXEmu, 1e-9)
        assertEquals(PictureTileAlignment.TOP_LEFT, tile.alignment)
    }

    @Test
    fun `a slide picture carries its source and mask`() {
        val picture =
            """<p:pic><p:nvPicPr><p:cNvPr id="2" name="P"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>""" +
                """<p:blipFill><a:blip r:embed="rId7"/><a:srcRect l="5000"/>""" +
                """<a:stretch><a:fillRect r="10000"/></a:stretch></p:blipFill>""" +
                """<p:spPr><a:xfrm rot="5400000" flipH="1"><a:off x="0" y="0"/>""" +
                """<a:ext cx="100" cy="200"/></a:xfrm>""" +
                """<a:prstGeom prst="ellipse"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""

        val node = slide(picture).scene.nodes.single() as ScenePicture

        assertEquals("rId7", node.source?.relationshipId)
        assertEquals(0.05, node.source!!.crop.left, 1e-9)
        assertEquals(0.1, (node.source!!.fill as PictureFill.Stretch).insets.right, 1e-9)
        assertEquals("ellipse", node.mask?.preset)
        assertEquals(SceneRect(0.0, 0.0, 100.0, 200.0), node.frame)
    }

    @Test
    fun `a stretch inset trims the frame`() {
        val frame = SceneRect(100.0, 200.0, 1_000.0, 500.0)

        val rect = stretchRect(frame, PictureInsets(0.1, 0.2, 0.05, 0.1))

        assertEquals(200.0, rect.x, 1e-9)
        assertEquals(300.0, rect.y, 1e-9)
        assertEquals(850.0, rect.width, 1e-9)
        assertEquals(350.0, rect.height, 1e-9)
    }

    @Test
    fun `a crop maps the whole image beyond the visible rectangle`() {
        val destination = SceneRect(0.0, 0.0, 100.0, 100.0)

        val positive = contentRect(destination, PictureCrop(0.1, 0.2, 0.1, 0.1))
        val negative = contentRect(destination, PictureCrop(left = -0.1))

        assertEquals(-12.5, positive.x, 1e-9)
        assertEquals(-28.571428, positive.y, 1e-6)
        assertEquals(125.0, positive.width, 1e-9)
        assertEquals(142.857142, positive.height, 1e-6)
        assertEquals(100.0 / 1.1, negative.width, 1e-9)
        assertEquals(100.0 - 100.0 / 1.1, negative.x, 1e-6)
    }

    @Test
    fun `a crop that removes the whole image has no visible area`() {
        val crop = PictureCrop(left = 0.6, right = 0.5)

        assertFalse(crop.isVisible)
        assertEquals(0.0, contentRect(SceneRect(0.0, 0.0, 100.0, 100.0), crop).width, 1e-9)
    }

    @Test
    fun `a tile is the image's native 96 dpi size scaled`() {
        val size = tileSize(ImageBounds(100, 50), PictureTile(scaleX = 0.5, scaleY = 2.0))

        assertEquals(476_250.0, size.width, 1e-9)
        assertEquals(952_500.0, size.height, 1e-9)
    }

    @Test
    fun `a tile origin follows its alignment`() {
        val frame = SceneRect(0.0, 0.0, 1_000.0, 500.0)
        val size = io.github.pixelrick420.papyrus.viewer.pptx.SceneSize(200.0, 100.0)

        assertEquals(0.0, tileOrigin(frame, size, PictureTileAlignment.TOP_LEFT).x, 1e-9)
        assertEquals(400.0, tileOrigin(frame, size, PictureTileAlignment.CENTRE).x, 1e-9)
        assertEquals(200.0, tileOrigin(frame, size, PictureTileAlignment.CENTRE).y, 1e-9)
        assertEquals(800.0, tileOrigin(frame, size, PictureTileAlignment.BOTTOM_RIGHT).x, 1e-9)
        assertEquals(400.0, tileOrigin(frame, size, PictureTileAlignment.BOTTOM_RIGHT).y, 1e-9)
    }

    @Test
    fun `a positive origin starts one tile back and coverage rounds up`() {
        assertEquals(-20.0, firstTile(30.0, 50.0), 1e-9)
        assertEquals(-30.0, firstTile(-30.0, 50.0), 1e-9)
        assertEquals(3, visibleTiles(-20.0, 50.0, 100.0))
        assertEquals(0, visibleTiles(0.0, 0.0, 100.0))
        assertEquals(0, visibleTiles(120.0, 50.0, 100.0))
    }

    private companion object {
        const val NS = """xmlns:a="${SlideXml.A}" xmlns:p="${SlideXml.P}" xmlns:r="${SlideXml.R}""""
    }
}
