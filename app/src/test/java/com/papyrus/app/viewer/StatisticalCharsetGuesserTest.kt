package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class StatisticalCharsetGuesserTest {

    private fun guess(text: String, charset: Charset): String? {
        val data = text.toByteArray(charset)
        return StatisticalCharsetGuesser.guess(data, data.size)
    }

    @Test
    fun `japanese Shift_JIS names Shift_JIS`() {
        assertEquals("Shift_JIS", guess("こんにちは、世界。今日はいい天気ですね。", Charset.forName("Shift_JIS")))
    }

    @Test
    fun `cyrillic Windows-1251 is told from its KOI8-R reading`() {
        val text = "Съешь же ещё этих мягких французских булок, да выпей чаю."

        assertEquals("windows-1251", guess(text, Charset.forName("windows-1251")))
    }

    @Test
    fun `cyrillic KOI8-R is told from its Windows-1251 reading`() {
        val text = "Съешь же ещё этих мягких французских булок, да выпей чаю."

        assertEquals("KOI8-R", guess(text, Charset.forName("KOI8-R")))
    }

    @Test
    fun `greek Windows-1253 names Windows-1253`() {
        assertEquals("windows-1253", guess("Καλημέρα κόσμε, τι κάνετε σήμερα;", Charset.forName("windows-1253")))
    }

    @Test
    fun `ascii is no evidence and gets no guess`() {
        assertNull(guess("The quick brown fox jumps over the lazy dog.", Charsets.US_ASCII))
    }

    @Test
    fun `sparsely accented Latin keeps the Windows-1252 default`() {
        val text = "Le café est prêt et déjà réservé pour René et sa fiancée."

        assertNull(guess(text, Charset.forName("windows-1252")))
    }
}
