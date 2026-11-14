package io.github.pixelrick420.papyrus.ooxml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.security.MessageDigest

class PresetShapeDefinitionsTest {

    private val asset = File("src/main/assets/ooxml/presetShapeDefinitions.xml")

    @Test
    fun `the vendored file is unmodified`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
        assertEquals(SHA256, digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `the file holds 187 shapes including upArrow`() {
        val names = mutableListOf<String>()
        asset.inputStream().use { input ->
            val parser = KXmlParser()
            parser.setInput(input, null)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 2) names += parser.name
            }
        }
        assertEquals(187, names.size)
        assertTrue(names.contains("upArrow"))
    }

    private companion object {
        const val SHA256 = "4a762444d8d85876881c02a5b1dedf6f73006fcd8acb7b4e393435615b37c780"
    }
}
