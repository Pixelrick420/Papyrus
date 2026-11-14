package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

// Optional: runs only when tools/pptx/fetch-corpus.sh has downloaded decks. Checks no crash, bounded time and warning counts, never content.
class PptxCorpusSmokeTest {

    private val corpus = File(System.getenv("PPTX_CORPUS") ?: "../tools/pptx/corpus")

    @Test
    @Suppress("TooGenericExceptionCaught") // A crash must be collected and reported, not escape the test.
    fun `every deck in the local corpus opens without a crash and in bounded time`() {
        assumeTrue("no corpus; run tools/pptx/fetch-corpus.sh", corpus.isDirectory)
        val decks = corpus.walkTopDown().filter { it.isFile && it.extension.lowercase() in EXTENSIONS }.toList()
        assumeTrue("the corpus holds no decks", decks.isNotEmpty())

        val crashed = mutableListOf<String>()
        val slow = mutableListOf<String>()
        val warnings = sortedMapOf<String, Int>()
        var rejected = 0
        for (deck in decks) {
            val started = System.nanoTime()
            try {
                open(deck.readBytes()).forEach { warnings.merge(it, 1, Int::plus) }
            } catch (_: IOException) {
                rejected++
            } catch (e: Throwable) {
                crashed += "${deck.name}: $e"
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            if (millis > MAX_MILLIS_PER_DECK) slow += "${deck.name}: $millis ms"
        }

        println("pptx corpus: ${decks.size} decks, $rejected rejected, warnings $warnings")
        assertTrue("crashed: $crashed", crashed.isEmpty())
        assertTrue("slower than $MAX_MILLIS_PER_DECK ms: $slow", slow.isEmpty())
    }

    // Archive level for now; this is the one place the real pipeline replaces once it exists.
    private fun open(bytes: ByteArray): List<String> {
        val names = mutableSetOf<String>()
        BoundedZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                var read = zip.read(buffer)
                while (read >= 0) read = zip.read(buffer)
            }
        }
        return buildList { if ("ppt/presentation.xml" !in names) add("no ppt/presentation.xml") }
    }

    private companion object {
        val EXTENSIONS = setOf("pptx", "potx", "ppsx")
        const val MAX_MILLIS_PER_DECK = 10_000L
    }
}
