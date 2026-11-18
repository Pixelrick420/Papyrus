package io.github.pixelrick420.papyrus.ooxml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.security.MessageDigest

class PresetTextWarpDefinitionsTest {

    private val asset = File("src/main/assets/ooxml/presetTextWarpDefinitions.xml")

    @Test
    fun `the vendored file is unmodified`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
        assertEquals(SHA256, digest.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `the file holds 40 warps including textArchUp`() {
        val names = mutableListOf<String>()
        asset.inputStream().use { input ->
            val parser = KXmlParser()
            parser.setInput(input, null)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 2) names += parser.name
            }
        }
        assertEquals(40, names.size)
        assertTrue(names.contains("textArchUp"))
    }

    private companion object {
        const val SHA256 = "712d1c24e938d6434cc70bb9ca81817912ca3904a9e808241b965d7b0d8e5789"
    }
}
