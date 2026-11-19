package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCatalogTest {

    @Test
    fun a_complete_family_resolves_every_face_from_its_own_file() {
        for (style in FaceStyle.entries) {
            val face = BundledFont.CARLITO.face(style)

            assertEquals("ooxml/fonts/humanist/PptxHumanist-${style.suffix}.ttf", face.asset)
            assertFalse("$style was synthetic", face.synthetic)
        }
    }

    @Test
    fun every_bundled_face_points_at_an_asset() {
        for (font in BundledFont.ALL) {
            for (style in FaceStyle.entries) {
                val asset = font.face(style).asset
                assertTrue("$asset is missing", File(ASSETS, asset).isFile)
            }
        }
    }

    @Test
    fun a_missing_weight_is_synthesised_from_the_regular_face() {
        val regularOnly = BundledFont("Pptx Humanist", "humanist", "PptxHumanist", setOf(FaceStyle.REGULAR))

        val bold = regularOnly.face(FaceStyle.BOLD)

        assertEquals("ooxml/fonts/humanist/PptxHumanist-Regular.ttf", bold.asset)
        assertTrue(bold.synthetic)
    }

    @Test
    fun a_missing_italic_borrows_the_bold_face() {
        val withoutItalic = BundledFont(
            "Pptx Humanist",
            "humanist",
            "PptxHumanist",
            setOf(FaceStyle.REGULAR, FaceStyle.BOLD),
        )

        val boldItalic = withoutItalic.face(FaceStyle.BOLD_ITALIC)

        assertEquals("ooxml/fonts/humanist/PptxHumanist-Bold.ttf", boldItalic.asset)
        assertTrue(boldItalic.synthetic)
    }

    @Test
    fun a_real_face_is_never_marked_synthetic() {
        val withoutBold = BundledFont(
            "Pptx Humanist",
            "humanist",
            "PptxHumanist",
            setOf(FaceStyle.REGULAR, FaceStyle.ITALIC, FaceStyle.BOLD_ITALIC),
        )

        assertFalse(withoutBold.face(FaceStyle.ITALIC).synthetic)
        assertFalse(withoutBold.face(FaceStyle.BOLD_ITALIC).synthetic)
    }

    @Test
    fun a_cached_value_loads_once() {
        val cache = LazyCache<String, String>()
        var loads = 0

        repeat(3) {
            assertEquals("k", cache.get("k") { loads++; it })
        }

        assertEquals(1, loads)
    }

    @Test
    fun a_failed_load_is_not_retried() {
        val cache = LazyCache<String, String>()
        var loads = 0

        repeat(2) { assertNull(cache.get("k") { loads++; null }) }

        assertEquals(1, loads)
    }

    private companion object {
        val ASSETS = File("src/main/assets")
    }
}
