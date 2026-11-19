package io.github.pixelrick420.papyrus.viewer.pptx.text

import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FontCatalogEmbeddedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun an_embedded_face_loads_from_the_deck_bytes() {
        val bytes = context.assets.open("ooxml/fonts/humanist/PptxHumanist-Regular.ttf").use { it.readBytes() }
        val font = EmbeddedFont("ppt/fonts/font1.fntdata", "Pptx Humanist", FaceStyle.REGULAR, bytes)
        val catalog = FontCatalog(context.assets, context.cacheDir)

        val typeface = catalog.embedded(font, bold = false, italic = false)

        assertNotSame(Typeface.DEFAULT, typeface)
        assertSame(typeface, catalog.embedded(font, bold = false, italic = false))
    }
}
