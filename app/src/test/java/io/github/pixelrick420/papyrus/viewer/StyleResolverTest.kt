package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.DeckScenes
import io.github.pixelrick420.papyrus.viewer.pptx.PlaceholderIdentity
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.SceneBullet
import io.github.pixelrick420.papyrus.viewer.pptx.SceneFormat
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGroup
import io.github.pixelrick420.papyrus.viewer.pptx.SceneLine
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneParagraphStyle
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRunStyle
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.SceneTextFrame
import io.github.pixelrick420.papyrus.viewer.pptx.StyleResolver
import io.github.pixelrick420.papyrus.viewer.pptx.TextAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleResolverTest {

    private fun resolve(deck: Deck): DeckScenes {
        val pkg = PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)
        val presentation = PresentationParser(OfficeFixtures::parser)
            .parse(deck.parts.getValue("ppt/presentation.xml"))
        return StyleResolver(OfficeFixtures::parser).resolveAll(pkg, presentation)
    }

    private fun scene(deck: Deck): Scene = resolve(deck).slides.single().scene

    private fun shapes(scene: Scene): List<SceneShape> = scene.nodes.filterIsInstance<SceneShape>()

    private fun shape(scene: Scene, name: String): SceneShape = shapes(scene).single { it.name == name }

    private fun solid(paint: ScenePaint?): Rgba {
        assertTrue("not a solid paint: $paint", paint is ScenePaint.Solid)
        return (paint as ScenePaint.Solid).color
    }

    private fun frame(shape: SceneShape): SceneTextFrame = shape.text ?: error("no text frame on ${shape.name}")

    private fun formatOf(shape: SceneShape): SceneFormat = shape.format ?: error("no format on ${shape.name}")

    @Test
    fun `background falls back from slide to layout to master to theme`() {
        val slideOwn = pptx {
            theme { lt1("0A0B0C") }
            master { noBackground() }
            layout("Blank") { noBackground() }
            slide { background(rgb = "112233") }
        }
        assertEquals(Rgba(0x11, 0x22, 0x33), solid(scene(slideOwn).background))

        val layoutOwn = pptx {
            theme { lt1("0A0B0C") }
            master { noBackground() }
            layout("Blank") { background(rgb = "223344") }
            slide {}
        }
        assertEquals(Rgba(0x22, 0x33, 0x44), solid(scene(layoutOwn).background))

        val masterOwn = pptx {
            theme { lt1("0A0B0C") }
            master { background(rgb = "334455") }
            layout("Blank") { noBackground() }
            slide {}
        }
        assertEquals(Rgba(0x33, 0x44, 0x55), solid(scene(masterOwn).background))

        val themeOwn = pptx {
            theme { lt1("0A0B0C") }
            master { noBackground() }
            layout("Blank") { noBackground() }
            slide {}
        }
        assertEquals(Rgba(0x0A, 0x0B, 0x0C), solid(scene(themeOwn).background))
    }

    @Test
    fun `a background reference resolves through the master colour map`() {
        val deck = pptx {
            theme { dk1("123456"); lt1("FFFFFF") }
            master {
                clrMap("bg1" to "dk1")
                backgroundRef()
            }
            layout("Blank") { noBackground() }
            slide {}
        }

        assertEquals(Rgba(0x12, 0x34, 0x56), solid(scene(deck).background))
    }

    @Test
    fun `draw order is master then layout then slide, with placeholders left undrawn`() {
        val deck = pptx {
            master {
                shape(Preset.RECT, 0, 0, 10, 10, name = "master shape")
                placeholder(Type.TITLE, idx = 0, x = 1, y = 2, w = 3, h = 4)
            }
            layout("Title") {
                shape(Preset.RECT, 10, 10, 10, 10, name = "layout shape")
                placeholder(Type.TITLE, idx = 0, x = 5, y = 6, w = 7, h = 8)
            }
            slide {
                shape(Preset.RECT, 20, 20, 10, 10, name = "slide shape")
                title("Title")
            }
        }

        val resolved = scene(deck).nodes.mapNotNull { it.name }
        assertEquals(listOf("master shape", "layout shape", "slide shape", "title placeholder 3"), resolved)
        assertEquals(1, shapes(scene(deck)).count { it.placeholder != null })
    }

    @Test
    fun `a layout that hides master shapes hides only the master's`() {
        val deck = pptx {
            master { shape(Preset.RECT, 0, 0, 10, 10, name = "master shape") }
            layout("Hidden master", showMasterSp = false) {
                shape(Preset.RECT, 10, 10, 10, 10, name = "layout shape")
            }
            slide {}
        }

        assertEquals(listOf("layout shape"), scene(deck).nodes.mapNotNull { it.name })
    }

    @Test
    fun `a slide that hides master shapes hides the layout's`() {
        val deck = pptx {
            master { shape(Preset.RECT, 0, 0, 10, 10, name = "master shape") }
            layout("Hidden layout") { shape(Preset.RECT, 10, 10, 10, 10, name = "layout shape") }
            slide(showMasterSp = false) { shape(Preset.RECT, 20, 20, 10, 10, name = "slide shape") }
        }

        assertEquals(listOf("master shape", "slide shape"), scene(deck).nodes.mapNotNull { it.name })
    }

    @Test
    fun `a hidden shape and a hidden group take their contents with them`() {
        val deck = pptx {
            master { shape(Preset.RECT, 0, 0, 10, 10, name = "hidden master", hidden = true) }
            layout("Blank")
            slide {
                shape(Preset.RECT, 0, 0, 10, 10, name = "seen")
                shape(Preset.RECT, 0, 0, 10, 10, name = "hidden shape", hidden = true)
                group(0, 0, 100, 100, name = "hidden group", hidden = true) {
                    shape(Preset.RECT, 0, 0, 10, 10)
                }
                group(0, 0, 100, 100, name = "group") {
                    shape(Preset.RECT, 0, 0, 10, 10, hidden = true)
                    shape(Preset.RECT, 10, 10, 10, 10)
                }
            }
        }

        val nodes = scene(deck).nodes
        assertEquals(listOf("seen", "group"), nodes.mapNotNull { it.name })
        val group = nodes.last() as SceneGroup
        assertEquals(listOf("Shape 3"), group.children.mapNotNull { it.name })
    }

    @Test
    fun `a slide placeholder matches the layout by idx and the master by type`() {
        val deck = pptx {
            master { placeholder(Type.TITLE, idx = 0, x = 1, y = 2, w = 3, h = 4) }
            layout("Title") { placeholder(Type.TITLE, idx = 5, x = 100, y = 200, w = 300, h = 400) }
            slide { placeholder(Type.TITLE, idx = 5) }
        }

        val placeholder = shapes(scene(deck)).single { it.placeholder != null }
        assertEquals(SceneRect(100.0, 200.0, 300.0, 400.0), placeholder.frame)
        assertEquals(PlaceholderIdentity("title", 5, indexExplicit = true), placeholder.placeholder)
    }

    @Test
    fun `an unmatched idx falls back to the placeholder type`() {
        val deck = pptx {
            master { placeholder(Type.TITLE, idx = 0, x = 1, y = 2, w = 3, h = 4) }
            layout("Title") { placeholder(Type.TITLE, idx = 5, x = 100, y = 200, w = 300, h = 400) }
            slide { placeholder(Type.TITLE, idx = 9) }
        }

        assertEquals(
            SceneRect(100.0, 200.0, 300.0, 400.0),
            shapes(scene(deck)).single { it.placeholder != null }.frame,
        )
    }

    @Test
    fun `ctrTitle and title are the same placeholder across the chain`() {
        val deck = pptx {
            master { placeholder(Type.TITLE, idx = 0, x = 1, y = 2, w = 3, h = 4) }
            layout("Title") { placeholder(Type.CENTER_TITLE, idx = 0) }
            slide { placeholder(Type.TITLE, idx = 3) }
        }

        assertEquals(
            SceneRect(1.0, 2.0, 3.0, 4.0),
            shapes(scene(deck)).single { it.placeholder != null }.frame,
        )
    }

    @Test
    fun `a slide overriding only an offset keeps the layout's extent`() {
        val deck = pptx {
            layout("Title") { placeholder(Type.TITLE, idx = 0, x = 100, y = 200, w = 300, h = 400) }
            slide { placeholder(Type.TITLE, idx = 0, x = 150) }
        }

        assertEquals(
            SceneRect(150.0, 200.0, 300.0, 400.0),
            shapes(scene(deck)).single { it.placeholder != null }.frame,
        )
    }

    @Test
    fun `a slide overriding only an extent keeps the layout's offset`() {
        val deck = pptx {
            layout("Title") { placeholder(Type.TITLE, idx = 0, x = 100, y = 200, w = 300, h = 400) }
            slide { placeholder(Type.TITLE, idx = 0, w = 500) }
        }

        assertEquals(
            SceneRect(100.0, 200.0, 500.0, 400.0),
            shapes(scene(deck)).single { it.placeholder != null }.frame,
        )
    }

    @Test
    fun `body properties inherit one attribute at a time`() {
        val deck = pptx {
            layout("Body") {
                placeholder(
                    Type.BODY,
                    idx = 1,
                    body = {
                        anchor("ctr")
                        wrap(false)
                        insets(10, 20, 30, 40)
                    },
                )
            }
            slide {
                placeholder(Type.BODY, idx = 1, body = { anchor("b") })
            }
        }

        val text = frame(shapes(scene(deck)).single { it.placeholder != null })
        assertEquals(TextAnchor.BOTTOM, text.anchor)
        assertFalse(text.wrap)
        assertEquals(10.0, text.inset.left, 0.0)
        assertEquals(20.0, text.inset.top, 0.0)
        assertEquals(30.0, text.inset.right, 0.0)
        assertEquals(40.0, text.inset.bottom, 0.0)
    }

    @Test
    fun `fill and line inherit independently`() {
        val deck = pptx {
            layout("Body") {
                placeholder(Type.BODY, idx = 1, decoration = { fill("accent1") })
            }
            slide {
                placeholder(Type.BODY, idx = 1, decoration = { line(rgb = "FF0000") })
            }
        }

        val format = formatOf(shapes(scene(deck)).single { it.placeholder != null })
        assertEquals(Rgba(0x44, 0x72, 0xC4), solid(format.fill))
        assertEquals(12_700.0, format.line!!.widthEmu)
        assertEquals(Rgba(0xFF, 0, 0), solid(format.line!!.paint))
    }

    @Test
    fun `a p style reference resolves through the theme format scheme`() {
        val deck = pptx {
            layout("Blank")
            slide {
                shape(Preset.RECT, 0, 0, 100, 100, name = "styled") {
                    style(fillRef = 1, lineRef = 1, effectRef = 1, scheme = "accent2")
                }
            }
        }

        val format = formatOf(shape(scene(deck), "styled"))
                assertEquals(Rgba(0xED, 0x7D, 0x31), solid(format.fill))
        assertEquals(6_350.0, format.line!!.widthEmu)
        assertEquals(Rgba(0xED, 0x7D, 0x31), solid(format.line!!.paint))
        assertNotNull(format.effects)
    }

    @Test
    fun `an explicit fill beats the style reference`() {
        val deck = pptx {
            layout("Blank")
            slide {
                shape(Preset.RECT, 0, 0, 100, 100, name = "styled") {
                    fillRgb("00FF00")
                    style(fillRef = 1, scheme = "accent2")
                }
            }
        }

        assertEquals(Rgba(0, 0xFF, 0), solid(formatOf(shape(scene(deck), "styled")).fill))
    }

    @Test
    fun `run properties stop at the first level that defines them`() {
        val deck = pptx {
            defaultTextStyle { level(1) { sz(1100); font("+mn-lt") } }
            master {
                noTextStyles()
                placeholder(Type.BODY, idx = 1, listStyle = { level(1) { sz(2200) } })
            }
            layout("Body") {
                placeholder(Type.BODY, idx = 1, listStyle = { level(1) { sz(3300) } })
            }
            slide {
                placeholder(
                    Type.BODY,
                    idx = 1,
                    listStyle = { level(1) { sz(4400) } },
                    body = { para("slide") },
                )
            }
        }

        assertEquals(44.0, frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single().runs.single().style.sizePt!!, 0.0)
    }

    @Test
    fun `the layout's list style outranks the master's and the presentation default`() {
        val deck = pptx {
            defaultTextStyle { level(1) { sz(1100) } }
            master {
                bodyStyle { sz(2200) }
                placeholder(Type.BODY, idx = 1, listStyle = { level(1) { sz(2300) } })
            }
            layout("Body") { placeholder(Type.BODY, idx = 1, listStyle = { level(1) { sz(3300) } }) }
            slide { placeholder(Type.BODY, idx = 1, body = { para("layout") }) }
        }

        assertEquals(33.0, frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single().runs.single().style.sizePt!!, 0.0)
    }

    @Test
    fun `the master txStyles outrank the presentation default`() {
        val deck = pptx {
            defaultTextStyle { level(1) { sz(1100) } }
            master {}
            layout("Body") { placeholder(Type.BODY, idx = 1) }
            slide { placeholder(Type.BODY, idx = 1, body = { para("master") }) }
        }

        assertEquals(28.0, frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single().runs.single().style.sizePt!!, 0.0)
    }

    @Test
    fun `the presentation default supplies what the master leaves out`() {
        val deck = pptx {
            defaultTextStyle { level(1) { sz(1100); font("+mj-lt") } }
            master { noTextStyles() }
            layout("Body") { placeholder(Type.BODY, idx = 1) }
            slide { placeholder(Type.BODY, idx = 1, body = { para("default") }) }
        }

        val style = frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single().runs.single().style
        assertEquals(11.0, style.sizePt!!, 0.0)
        assertEquals("Calibri Light", style.typeface)
    }

    @Test
    fun `the built in default is 18 point in the theme's minor font and tx1`() {
        val deck = pptx {
            theme { minorFont("Aptos"); dk1("010203") }
            master { noTextStyles() }
            layout("Body") { placeholder(Type.BODY, idx = 1) }
            slide { placeholder(Type.BODY, idx = 1, body = { para("built in") }) }
        }

        val style = frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single().runs.single().style
        assertEquals(18.0, style.sizePt!!, 0.0)
        assertEquals("Aptos", style.typeface)
        assertEquals(Rgba(1, 2, 3), style.color)
    }

    @Test
    fun `paragraph properties follow the same chain`() {
        val deck = pptx {
            defaultTextStyle { level(1) { algn("r"); bullet("*") } }
            master { noTextStyles() }
            layout("Body") { placeholder(Type.BODY, idx = 1, listStyle = { level(1) { algn("ctr") } }) }
            slide { placeholder(Type.BODY, idx = 1, body = { para("paragraph") }) }
        }

        val style = frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single().style
        assertEquals("ctr", style?.alignment)
        assertEquals("*", (style?.bullet as SceneBullet.Char).char)
    }

    @Test
    fun `a paragraph's own alignment beats the list style`() {
        val deck = pptx {
            master { bodyStyle { level(1) { algn("ctr") } } }
            layout("Body") { placeholder(Type.BODY, idx = 1) }
            slide { placeholder(Type.BODY, idx = 1, body = { para(level = 0) { algn("l"); run("own") } }) }
        }

        assertEquals("l", frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single().style?.alignment)
    }

    @Test
    fun `endParaRPr sets the height of an empty paragraph`() {
        val deck = pptx {
            master { bodyStyle { sz(2000) } }
            layout("Body") { placeholder(Type.BODY, idx = 1) }
            slide {
                placeholder(
                    Type.BODY,
                    idx = 1,
                    body = { para { endPara { sz(2900); bold() } } },
                )
            }
        }

        val paragraph = frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single()
        assertTrue(paragraph.runs.isEmpty())
        assertEquals(29.0, paragraph.endStyle!!.sizePt!!, 0.0)
        assertTrue(paragraph.endStyle?.bold == true)
    }

    @Test
    fun `an empty paragraph with no endParaRPr takes its height from the list style`() {
        val deck = pptx {
            master { bodyStyle { sz(2400) } }
            layout("Body") { placeholder(Type.BODY, idx = 1) }
            slide { placeholder(Type.BODY, idx = 1) }
        }

        val paragraph = frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single()
        assertEquals(24.0, paragraph.endStyle!!.sizePt!!, 0.0)
    }

    @Test
    fun `the slide number field is computed from firstSlideNum`() {
        val deck = pptx {
            firstSlideNumber(7)
            layout("Blank")
            slide { textBox(0, 0, 100, 100) { para { field("slidenum", "1") } } }
            slide { textBox(0, 0, 100, 100) { para { field("slidenum", "2") } } }
        }

        val texts = resolve(deck).slides.map { slidescene ->
            slidescene.scene.nodes.filterIsInstance<SceneShape>().single().text!!.paragraphs.single().runs.single().text
        }
        assertEquals(listOf("7", "8"), texts)
    }

    @Test
    fun `a datetime field keeps its cached text`() {
        val deck = pptx {
            layout("Blank")
            slide { textBox(0, 0, 100, 100) { para { field("datetime1", "2020-01-02T03:04:05") } } }
        }

        val run = shapes(scene(deck)).single().text!!.paragraphs.single().runs.single()
        assertEquals("2020-01-02T03:04:05", run.text)
    }

    @Test
    fun `two slides on one layout resolve the same inherited placeholder`() {
        val deck = pptx {
            layout("Body") {
                placeholder(
                    Type.BODY,
                    idx = 1,
                    x = 10,
                    y = 20,
                    w = 30,
                    h = 40,
                    listStyle = { level(1) { sz(2100) } },
                )
            }
            slide { placeholder(Type.BODY, idx = 1, body = { para("one") }) }
            slide { placeholder(Type.BODY, idx = 1, body = { para("two") }) }
        }

        val resolved = resolve(deck).slides.map { slidescene ->
            slidescene.scene.nodes.filterIsInstance<SceneShape>().single()
        }
        assertEquals(SceneRect(10.0, 20.0, 30.0, 40.0), resolved[0].frame)
        assertEquals(resolved[0].frame, resolved[1].frame)
        assertEquals(
            resolved[0].text!!.paragraphs.single().runs.single().style.sizePt!!,
            resolved[1].text!!.paragraphs.single().runs.single().style.sizePt!!,
            0.0,
        )
    }

    @Test
    fun `a placeholder that names a missing layout does not crash`() {
        val built = pptx {
            layout("Blank")
            slide { shape(Preset.RECT, 0, 0, 10, 10, name = "only shape") }
        }
        val parts = built.parts.toMutableMap()
        parts["ppt/slides/_rels/slide1.xml.rels"] = (
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" """ +
                """Target="../slideLayouts/missing.xml"/></Relationships>"""
            ).toByteArray()

        val resolved = resolve(Deck(parts)).slides.single()
        assertFalse(resolved.incomplete)
        assertEquals(listOf("only shape"), resolved.scene.nodes.mapNotNull { it.name })
        assertEquals(Rgba.WHITE, solid(resolved.scene.background))
    }

    @Test
    fun `a slide with no layout relationship still resolves`() {
        val built = pptx {
            layout("Blank")
            slide { shape(Preset.RECT, 0, 0, 10, 10, name = "only shape") }
        }
        val parts = built.parts.toMutableMap()
        parts["ppt/slides/_rels/slide1.xml.rels"] = (
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""
            ).toByteArray()

        val resolved = resolve(Deck(parts)).slides.single()
        assertFalse(resolved.incomplete)
        assertEquals(listOf("only shape"), resolved.scene.nodes.mapNotNull { it.name })
    }

    @Test
    fun `a run inherits the language and size of its paragraph mark`() {
        val deck = pptx {
            layout("Blank")
            slide {
                textBox(0, 0, 100, 100) {
                    para { defaultRun { sz(1500); italic(); lang("fr-FR") }; run("hello") }
                }
            }
        }

        val style: SceneRunStyle = shapes(scene(deck)).single().text!!.paragraphs.single().runs.single().style
        assertEquals(15.0, style.sizePt!!, 0.0)
        assertTrue(style.italic)
        assertEquals("fr-FR", style.language)
    }

    @Test
    fun `a non-placeholder uses otherStyle and a title placeholder uses titleStyle`() {
        val deck = pptx {
            master {
                titleStyle { sz(4000) }
                otherStyle { sz(1000) }
            }
            layout("Title") {
                placeholder(Type.TITLE, idx = 0)
            }
            slide {
                placeholder(Type.TITLE, idx = 0, body = { para("title") })
                textBox(0, 0, 100, 100) { para("other") }
            }
        }

        val resolved = shapes(scene(deck))
        val title = resolved.single { it.placeholder != null }.text!!.paragraphs.single().runs.single()
        val other = resolved.single { it.placeholder == null }.text!!.paragraphs.single().runs.single()
        assertEquals(40.0, title.style.sizePt!!, 0.0)
        assertEquals(10.0, other.style.sizePt!!, 0.0)
    }

    @Test
    fun `a slide that keeps its own frame does not inherit the placeholder's`() {
        val deck = pptx {
            layout("Body") { placeholder(Type.BODY, idx = 1, x = 10, y = 20, w = 30, h = 40) }
            slide { placeholder(Type.BODY, idx = 1, x = 1, y = 2, w = 3, h = 4) }
        }

        assertEquals(
            SceneRect(1.0, 2.0, 3.0, 4.0),
            shapes(scene(deck)).single { it.placeholder != null }.frame,
        )
    }

    @Test
    fun `a master placeholder without a layout counterpart lends nothing`() {
        val deck = pptx {
            master { placeholder(Type.BODY, idx = 4, x = 10, y = 20, w = 30, h = 40) }
            layout("Blank")
            slide { placeholder(Type.BODY, idx = 1) }
        }

        assertNull(shapes(scene(deck)).single { it.placeholder != null }.frame)
    }

    @Test
    fun `a placeholder paragraph style reports spacing and indent through the chain`() {
        val deck = pptx {
            master {
                bodyStyle {
                    level(1) {
                        marL(1000)
                        indent(-500)
                        lnSpcPct(120)
                        spcBefPts(6)
                        spcAftPct(50)
                    }
                }
            }
            layout("Body") { placeholder(Type.BODY, idx = 1) }
            slide { placeholder(Type.BODY, idx = 1, body = { para("spaced") }) }
        }

        val style: SceneParagraphStyle = frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single().style!!
        assertEquals(1000.0, style.marginLeftEmu!!, 0.0)
        assertEquals(-500.0, style.indentEmu!!, 0.0)
        assertEquals(120.0, style.lineSpacing!!.percent!!, 0.0)
        assertEquals(6.0, style.spaceBefore!!.points!!, 0.0)
        assertEquals(50.0, style.spaceAfter!!.percent!!, 0.0)
    }

    @Test
    fun `an auto numbered bullet comes through as a bullet, not a character`() {
        val deck = pptx {
            master { bodyStyle { level(1) { autoNumber("romanUcPeriod", startAt = 3) } } }
            layout("Body") { placeholder(Type.BODY, idx = 1) }
            slide { placeholder(Type.BODY, idx = 1, body = { para("numbered") }) }
        }

        val bullet = frame(shapes(scene(deck)).single { it.placeholder != null }).paragraphs.single().style?.bullet
        assertTrue(bullet is SceneBullet.AutoNumber)
        assertEquals("romanUcPeriod", (bullet as SceneBullet.AutoNumber).scheme)
        assertEquals(3, bullet.startAt)
    }

    @Test
    fun `a placeholder with no matching type keeps its own empty frame`() {
        val deck = pptx {
            layout("Blank")
            slide { placeholder(Type.FOOTER, idx = 1) }
        }

        assertNull(shapes(scene(deck)).single { it.placeholder != null }.frame)
    }
}
