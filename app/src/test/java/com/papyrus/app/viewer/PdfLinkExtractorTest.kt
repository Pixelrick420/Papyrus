package com.papyrus.app.viewer

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionLaunch
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionNamed
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What survives being read out of a page. The failure modes matter most: one annotation PdfBox chokes
 * on must not cost the page its other links, and nothing a document writes may make extraction throw.
 */
class PdfLinkExtractorTest {

    /** A small crop box so every rectangle below lands on round page fractions. */
    private val crop = rect(0f, 0f, 200f, 100f)

    /** The rectangle every link in this test uses: 20..60 across, 50..75 up of a 200x100 page. */
    private val box = rect(20f, 50f, 60f, 75f)

    private val drawn = NormRect(0.1f, 0.25f, 0.3f, 0.5f)

    /**
     * This port's four-argument [PDRectangle] adds its third and fourth values to the first two instead of
     * treating them as an upper-right corner, so build the corner array directly.
     */
    private fun rect(llx: Float, lly: Float, urx: Float, ury: Float): PDRectangle =
        PDRectangle(COSArray().apply {
            add(COSFloat(llx))
            add(COSFloat(lly))
            add(COSFloat(urx))
            add(COSFloat(ury))
        })

    private fun page(vararg annotations: PDAnnotation): PDPage = PDPage().apply {
        cropBox = crop
        if (annotations.isNotEmpty()) setAnnotations(annotations.toList())
    }

    private fun document(vararg pages: PDPage): PDDocument =
        PDDocument().apply { pages.forEach { addPage(it) } }

    private fun uriLink(url: String): PDAnnotationLink = PDAnnotationLink().apply {
        setRectangle(box)
        setAction(PDActionURI().apply { setURI(url) })
    }

    private fun goToLink(destination: PDPageXYZDestination): PDAnnotationLink = PDAnnotationLink().apply {
        setRectangle(box)
        setAction(PDActionGoTo().apply { setDestination(destination) })
    }

    private fun urls(document: PDDocument, index: Int = 0): List<String> =
        PdfLinkExtractor.extract(document, index).map { (it.target as PdfLinkTarget.External).url }

    @Test
    fun `a uri action becomes an external link over the drawn rectangle`() {
        val document = document(page(uriLink("https://example.com/a")))

        assertEquals(
            listOf(PdfPageLink(drawn, PdfLinkTarget.External("https://example.com/a"))),
            PdfLinkExtractor.extract(document, 0),
        )
    }

    @Test
    fun `mailto and tel are kept alongside web links`() {
        val document = document(
            page(uriLink("https://example.com/a"), uriLink("mailto:a@example.com"), uriLink("tel:+911234")),
        )

        assertEquals(
            listOf("https://example.com/a", "mailto:a@example.com", "tel:+911234"),
            urls(document),
        )
    }

    @Test
    fun `a scheme that can run something is dropped and its neighbours survive`() {
        val unsafe = listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://x/y",
            "intent://scan/#Intent;end",
        ).map { uriLink(it) }
        val document = document(page(*unsafe.toTypedArray(), uriLink("https://example.com/good")))

        assertEquals(listOf("https://example.com/good"), urls(document))
    }

    @Test
    fun `a goto action to a page of this document is an internal jump to it`() {
        val third = page()
        val document = document(page(), page(goToLink(PDPageXYZDestination().apply { setPage(third) })), third)

        assertEquals(listOf(PdfPageLink(drawn, PdfLinkTarget.Internal(2))), PdfLinkExtractor.extract(document, 1))
    }

    @Test
    fun `a destination carrying a page number rather than a page object resolves the same way`() {
        // A GoTo action must name a page object, so a numbered destination is written on the annotation.
        val link = PDAnnotationLink().apply {
            setRectangle(box)
            setDestination(PDPageXYZDestination().apply { setPageNumber(2) })
        }
        val document = document(page(), page(), page(link))

        assertEquals(listOf(PdfPageLink(drawn, PdfLinkTarget.Internal(2))), PdfLinkExtractor.extract(document, 2))
    }

    @Test
    fun `a destination naming a page outside the document goes nowhere`() {
        val link = PDAnnotationLink().apply {
            setRectangle(box)
            setDestination(PDPageXYZDestination().apply { setPageNumber(99) })
        }
        val document = document(page(link))

        assertTrue(PdfLinkExtractor.extract(document, 0).isEmpty())
    }

    @Test
    fun `a destination that is not a page destination goes nowhere`() {
        val link = PDAnnotationLink().apply {
            setRectangle(box)
            setDestination(PDNamedDestination("an-intro"))
        }
        val document = document(page(link))

        assertTrue(PdfLinkExtractor.extract(document, 0).isEmpty())
    }

    @Test
    fun `an action that is not uri or goto is dropped`() {
        val actions = listOf(
            PDActionJavaScript().apply { setAction("app.alert(1)") },
            PDActionNamed().apply { setN("NextPage") },
            PDActionLaunch(),
        )
        val annotations = actions.map { action ->
            PDAnnotationLink().apply {
                setRectangle(box)
                setAction(action)
            }
        }
        val document = document(page(*annotations.toTypedArray()))

        assertTrue(PdfLinkExtractor.extract(document, 0).isEmpty())
    }

    @Test
    fun `a rectangle that is not an array throws and its neighbour survives`() {
        val broken = uriLink("https://example.com/broken")
        broken.getCOSObject().setString(COSName.RECT, "not a rectangle")
        val document = document(page(broken, uriLink("https://example.com/good")))

        assertEquals(listOf("https://example.com/good"), urls(document))
    }

    @Test
    fun `an action that is not a dictionary and a missing rectangle are both dropped`() {
        val notADictionary = uriLink("https://example.com/a")
        notADictionary.getCOSObject().setString(COSName.A, "not an action")
        val noRectangle = PDAnnotationLink().apply {
            setAction(PDActionURI().apply { setURI("https://example.com/b") })
        }
        val document = document(page(notADictionary, noRectangle, uriLink("https://example.com/good")))

        assertEquals(listOf("https://example.com/good"), urls(document))
    }

    @Test
    fun `a rectangle with no area is dropped without throwing`() {
        val empty = uriLink("https://example.com/empty").also { it.setRectangle(rect(10f, 10f, 10f, 10f)) }
        val document = document(page(empty, uriLink("https://example.com/good")))

        assertEquals(listOf("https://example.com/good"), urls(document))
    }

    @Test
    fun `a page with no annotations has no links`() {
        assertTrue(PdfLinkExtractor.extract(document(page()), 0).isEmpty())
    }

    @Test
    fun `a page index outside the document has no links`() {
        val document = document(page())

        assertTrue(PdfLinkExtractor.extract(document, 1).isEmpty())
        assertTrue(PdfLinkExtractor.extract(document, -1).isEmpty())
    }

    /** The frame of a 200x100 crop box, with its lower-left where [x], [y] put it. */
    private fun frame(rotation: Int = 0, x: Float = 0f, y: Float = 0f) = PdfPageFrame(200f, 100f, x, y, rotation)

    @Test
    fun `an identity page maps a rectangle onto the same page fractions`() {
        assertEquals(drawn, frame().toNorm(20f, 50f, 60f, 75f))
    }

    @Test
    fun `a crop box that does not start at the origin is folded out before dividing`() {
        // The same rectangle, 20..60 across and 50..75 up of the crop box, wherever the box starts.
        assertEquals(drawn, frame().toNorm(20f, 50f, 60f, 75f))
        assertEquals(drawn, frame(x = 40f, y = 30f).toNorm(60f, 80f, 100f, 105f))
    }

    @Test
    fun `rotation turns the rectangle the way the page is turned`() {
        assertEquals(NormRect(0.5f, 0.1f, 0.75f, 0.3f), frame(rotation = 90).toNorm(20f, 50f, 60f, 75f))
        assertEquals(NormRect(0.7f, 0.5f, 0.9f, 0.75f), frame(rotation = 180).toNorm(20f, 50f, 60f, 75f))
        assertEquals(NormRect(0.25f, 0.7f, 0.5f, 0.9f), frame(rotation = 270).toNorm(20f, 50f, 60f, 75f))
    }

    @Test
    fun `a rectangle that is empty or not finite has no drawing`() {
        assertEquals(null, frame().toNorm(10f, 10f, 10f, 10f))
        assertEquals(null, frame().toNorm(0f, Float.NaN, 10f, 10f))
        assertEquals(null, frame().toNorm(0f, 0f, Float.POSITIVE_INFINITY, 10f))
    }

    @Test
    fun `a link on a page whose crop box starts away from the origin still lands on its words`() {
        // A 200x100 crop box starting at (40, 30), with a link 20..60 across and 50..75 up of it.
        val offsetPage = PDPage().apply {
            setCropBox(rect(40f, 30f, 240f, 130f))
            setAnnotations(listOf(uriLink("https://example.com/a").also { it.setRectangle(rect(60f, 80f, 100f, 105f)) }))
        }

        assertEquals(
            listOf(PdfPageLink(drawn, PdfLinkTarget.External("https://example.com/a"))),
            PdfLinkExtractor.extract(document(offsetPage), 0),
        )
    }
}
