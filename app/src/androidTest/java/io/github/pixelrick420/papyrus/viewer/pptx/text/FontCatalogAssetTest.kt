package io.github.pixelrick420.papyrus.viewer.pptx.text

import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FontCatalogAssetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val catalog = FontCatalog(context.assets)

    @Test
    fun every_bundled_face_loads_from_its_asset() {
        for (font in BundledFont.ALL) {
            for (style in FaceStyle.entries) {
                val typeface = catalog.bundled(font, style != FaceStyle.REGULAR, style == FaceStyle.ITALIC)
                assertNotSame("${font.family} ${style.suffix}", Typeface.DEFAULT, typeface)
            }
        }
    }

    @Test
    fun the_bundled_bold_face_is_wider_than_the_regular_face() {
        val regular = width(catalog.bundled(BundledFont.CARLITO, bold = false, italic = false))
        val bold = width(catalog.bundled(BundledFont.CARLITO, bold = true, italic = false))

        assertTrue("bold $bold vs regular $regular", bold > regular)
    }

    @Test
    fun a_face_is_loaded_once() {
        val first = catalog.bundled(BundledFont.CALADEA, bold = false, italic = false)
        val second = catalog.bundled(BundledFont.CALADEA, bold = false, italic = false)

        assertSame(first, second)
    }

    private fun width(typeface: Typeface): Float =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 48f
            isLinearText = true
        }.measureText(PANGRAM)

    private companion object {
        const val PANGRAM = "The quick brown fox jumps over the lazy dog"
    }
}
