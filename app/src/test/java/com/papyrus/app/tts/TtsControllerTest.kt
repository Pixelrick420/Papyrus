package com.papyrus.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure queue logic behind a fake [SpeechEngine]: no Android, no real voice. */
class TtsControllerTest {

    private class FakeEngine(var available: Boolean = true) : SpeechEngine {
        val spoken = mutableListOf<String>()
        val utteranceIds = mutableListOf<String>()
        var stops = 0
        var rate = 1.0f

        override fun speak(text: String, utteranceId: String) {
            spoken.add(text)
            utteranceIds.add(utteranceId)
        }

        override fun stop() {
            stops++
        }

        override fun setSpeechRate(rate: Float) {
            this.rate = rate
        }

        override fun isAvailable(): Boolean = available

        /** The id of the utterance the engine is on, which its progress listener would report back. */
        val currentUtteranceId: String get() = utteranceIds.last()
    }

    private fun units() = listOf(
        TtsUnit("one", 0),
        TtsUnit("two", 1),
        TtsUnit("three", 2),
    )

    @Test
    fun `constructing and setting a queue speaks nothing`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())
        assertTrue(engine.spoken.isEmpty())
        assertFalse(controller.playback.value.isPlaying)
    }

    @Test
    fun `playFrom speaks that unit and reports its anchor`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(1)

        assertEquals(listOf("two"), engine.spoken)
        assertEquals(1, controller.playback.value.unitIndex)
        assertEquals(1, controller.playback.value.anchor)
        assertTrue(controller.playback.value.isPlaying)
    }

    @Test
    fun `finishing a unit advances to the next and stops cleanly at the end`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(0)
        controller.onUtteranceComplete(engine.currentUtteranceId)
        controller.onUtteranceComplete(engine.currentUtteranceId)
        assertEquals(listOf("one", "two", "three"), engine.spoken)

        // Past the last unit: playback stops and nothing more is spoken.
        controller.onUtteranceComplete(engine.currentUtteranceId)
        assertFalse(controller.playback.value.isPlaying)
        assertEquals(3, engine.spoken.size)
        assertEquals(2, controller.playback.value.unitIndex)
    }

    @Test
    fun `pause holds the unit and play re-speaks it rather than skipping`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(0)
        controller.onUtteranceComplete(engine.currentUtteranceId)
        controller.pause()
        assertTrue(controller.playback.value.isPaused)
        assertFalse(controller.playback.value.isPlaying)

        controller.play()
        assertEquals(listOf("one", "two", "two"), engine.spoken)
        assertEquals(1, controller.playback.value.unitIndex)
    }

    @Test
    fun `skipBack at the first unit stays put`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(0)
        controller.skipBack()

        assertEquals(listOf("one"), engine.spoken)
        assertEquals(0, controller.playback.value.unitIndex)
        assertTrue(controller.playback.value.isPlaying)
    }

    @Test
    fun `skipForward past the last unit stops`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(2)
        controller.skipForward()

        assertEquals(listOf("three"), engine.spoken)
        assertFalse(controller.playback.value.isPlaying)
    }

    @Test
    fun `skipping while paused moves the remembered unit without speaking`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(0)
        controller.pause()
        controller.skipForward()

        assertEquals(listOf("one"), engine.spoken)
        assertEquals(1, controller.playback.value.unitIndex)
        assertTrue(controller.playback.value.isPaused)

        controller.skipBack()
        assertEquals(0, controller.playback.value.unitIndex)
        assertEquals(listOf("one"), engine.spoken)
    }

    @Test
    fun `a speed change with no engine timing applies at the next unit`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(0)
        controller.setSpeed(1.5f)

        assertEquals(1.5f, engine.rate, 0.0001f)
        assertEquals(1.5f, controller.playback.value.speed, 0.0001f)
        // Nothing is re-queued without word timing, so the unit in flight plays out at its old rate.
        assertEquals(listOf("one"), engine.spoken)
        assertEquals(0, controller.playback.value.unitIndex)

        controller.onUtteranceComplete(engine.currentUtteranceId)
        assertEquals(listOf("one", "two"), engine.spoken)
        assertEquals(1.5f, engine.rate, 0.0001f)
    }

    @Test
    fun `a speed change mid-unit re-speaks only the rest of the unit`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit("hello brave new world", 0)))

        controller.playFrom(0)
        controller.onRangeStart(engine.currentUtteranceId, 6)
        controller.setSpeed(1.5f)

        assertEquals(listOf("hello brave new world", "brave new world"), engine.spoken)
        assertEquals(1.5f, engine.rate, 0.0001f)
        // Same unit, same anchor, still playing: the rate changes without moving the reader.
        assertEquals(0, controller.playback.value.unitIndex)
        assertEquals(0, controller.playback.value.anchor)
        assertTrue(controller.playback.value.isPlaying)
    }

    @Test
    fun `a completion for the utterance a speed change replaced is ignored`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(0)
        val replaced = engine.currentUtteranceId
        controller.onRangeStart(replaced, 0)
        controller.setSpeed(1.5f)
        controller.onUtteranceComplete(replaced)

        // The stopped utterance reported late; that must not look like the unit finishing.
        assertEquals(0, controller.playback.value.unitIndex)
        assertTrue(controller.playback.value.isPlaying)

        controller.onUtteranceComplete(engine.currentUtteranceId)
        assertEquals(1, controller.playback.value.unitIndex)
    }

    @Test
    fun `a speed change while paused does not start speaking`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(0)
        controller.onRangeStart(engine.currentUtteranceId, 0)
        controller.pause()

        controller.setSpeed(1.5f)

        assertEquals(listOf("one"), engine.spoken)
        assertEquals(1.5f, engine.rate, 0.0001f)
        assertTrue(controller.playback.value.isPaused)
    }

    @Test
    fun `speed is clamped to the supported range`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setSpeed(9f)
        assertEquals(2.0f, controller.playback.value.speed, 0.0001f)
        controller.setSpeed(0.1f)
        assertEquals(0.5f, controller.playback.value.speed, 0.0001f)
    }

    @Test
    fun `an engine error keeps the unit, surfaces the reason, and speaks nothing further`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(1)
        controller.onError("boom")
        controller.onUtteranceComplete(engine.currentUtteranceId)

        assertTrue(controller.playback.value.hasError)
        assertEquals("boom", controller.playback.value.errorMessage)
        assertFalse(controller.playback.value.isPlaying)
        assertEquals(1, controller.playback.value.unitIndex)
        assertEquals(listOf("two"), engine.spoken)
    }

    @Test
    fun `an unavailable engine fails instead of speaking`() {
        val engine = FakeEngine(available = false)
        val controller = TtsController(engine)
        controller.setUnits(units())

        controller.playFrom(0)

        assertTrue(controller.playback.value.hasError)
        assertTrue(engine.spoken.isEmpty())
    }

    // --- Where the voice is inside a unit ------------------------------------------------------

    private val prose = "Hello brave world. How are you today? Fine."

    @Test
    fun `a unit marks nothing finer than itself until the engine reports a word`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit(prose, anchor = 4, start = 7)))

        controller.playFrom(0)

        val progress = controller.playback.value.progress
        assertEquals(prose, progress.text)
        assertEquals(7, progress.start)
        assertNull(progress.sentence)
        assertNull(progress.word)
    }

    @Test
    fun `a reported word is marked along with the sentence holding it`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit(prose, 0)))
        controller.playFrom(0)

        val how = prose.indexOf("How")
        controller.onRangeStart(engine.currentUtteranceId, how, how + 3)

        val progress = controller.playback.value.progress
        assertEquals("How", prose.substring(progress.word!!.first, progress.word!!.last + 1))
        assertEquals(
            "How are you today?",
            prose.substring(progress.sentence!!.first, progress.sentence!!.last + 1),
        )
    }

    @Test
    fun `an engine that gives no end has the word read off the text`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit(prose, 0)))
        controller.playFrom(0)

        val brave = prose.indexOf("brave")
        controller.onRangeStart(engine.currentUtteranceId, brave)

        val word = controller.playback.value.progress.word!!
        assertEquals("brave", prose.substring(word.first, word.last + 1))
    }

    @Test
    fun `a word the engine reports past the text is ignored`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit("short", 0)))
        controller.playFrom(0)

        controller.onRangeStart(engine.currentUtteranceId, 99, 104)

        assertNull(controller.playback.value.progress.word)
    }

    @Test
    fun `after a speed change the engine counts from the re-queued rest of the unit`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        val text = "hello brave new world"
        controller.setUnits(listOf(TtsUnit(text, 0)))
        controller.playFrom(0)
        controller.onRangeStart(engine.currentUtteranceId, 6, 11)

        controller.setSpeed(1.5f)
        // The engine now speaks "brave new world" and counts from its first letter.
        val new = 6
        controller.onRangeStart(engine.currentUtteranceId, new, new + 3)

        val word = controller.playback.value.progress.word!!
        assertEquals("new", text.substring(word.first, word.last + 1))
        // A second change re-queues from the new word and the offsets stay in the unit's terms.
        controller.setSpeed(2.0f)
        controller.onRangeStart(engine.currentUtteranceId, 4, 9)
        val last = controller.playback.value.progress.word!!
        assertEquals("world", text.substring(last.first, last.last + 1))
    }

    @Test
    fun `a range from an utterance that was replaced is ignored`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit(prose, 0)))
        controller.playFrom(0)
        val replaced = engine.currentUtteranceId
        controller.onRangeStart(replaced, 0, 5)
        controller.setSpeed(1.5f)

        controller.onRangeStart(replaced, 6, 11)

        val word = controller.playback.value.progress.word!!
        assertEquals(0, word.first)
    }

    @Test
    fun `once the engine has reported words the next unit starts on its first sentence`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit(prose, 0), TtsUnit("Second one. And more.", 1)))
        controller.playFrom(0)
        controller.onRangeStart(engine.currentUtteranceId, 0, 5)
        controller.onUtteranceComplete(engine.currentUtteranceId)

        val progress = controller.playback.value.progress
        assertEquals("Second one. And more.", progress.text)
        assertNull(progress.word)
        val sentence = progress.sentence!!
        assertEquals("Second one.", progress.text.substring(sentence.first, sentence.last + 1))
    }

    @Test
    fun `skipping while paused moves the mark to the start of the unit it lands on`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit("one two", 0), TtsUnit("three four", 1, start = 12)))
        controller.playFrom(0)
        controller.onRangeStart(engine.currentUtteranceId, 4, 7)
        controller.pause()

        controller.skipForward()

        val progress = controller.playback.value.progress
        assertEquals("three four", progress.text)
        assertEquals(12, progress.start)
        assertNull(progress.word)
    }

    @Test
    fun `pausing keeps the word, and resuming re-speaks the unit from its start`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit("one two three", 0)))
        controller.playFrom(0)
        controller.onRangeStart(engine.currentUtteranceId, 4, 7)

        controller.pause()
        assertEquals(4, controller.playback.value.progress.word!!.first)

        controller.play()
        assertNull(controller.playback.value.progress.word)
    }

    @Test
    fun `stopping forgets where in the unit the voice was`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit(prose, 0)))
        controller.playFrom(0)
        controller.onRangeStart(engine.currentUtteranceId, 0, 5)

        controller.stop()

        assertEquals(TtsProgress(), controller.playback.value.progress)
    }

    @Test
    fun `progress inside a unit does not change what the notification shows`() {
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(listOf(TtsUnit(prose, 0), TtsUnit("next", 1)))
        controller.playFrom(0)
        val before = controller.playback.value

        controller.onRangeStart(engine.currentUtteranceId, 6, 11)
        val moved = controller.playback.value
        assertTrue(before.sameTransportAs(moved))

        controller.pause()
        assertFalse(moved.sameTransportAs(controller.playback.value))
        controller.play()
        controller.setSpeed(1.5f)
        assertFalse(controller.playback.value.sameTransportAs(moved))
    }

    @Test
    fun `every word the engine reports is found again in the document, speed change and all`() {
        val chunk = "First  line of\n   an indented paragraph. It has two sentences.\n\n" +
            "Second paragraph\nwraps over\n\t\tthree lines."
        val units = TtsDocumentSource.unitsFromPlainText(listOf(chunk))
        assertEquals(2, units.size)
        val engine = FakeEngine()
        val controller = TtsController(engine)
        controller.setUnits(units)
        controller.playFrom(0)

        var words = 0
        for (unit in units.indices) {
            var cursor = 0
            var utterances = engine.spoken.size
            while (true) {
                if (engine.spoken.size != utterances) {
                    // A speed change replaced the utterance with the rest of the unit.
                    utterances = engine.spoken.size
                    cursor = 0
                }
                val match = Regex("\\S+").find(engine.spoken.last(), cursor) ?: break
                controller.onRangeStart(engine.currentUtteranceId, match.range.first, match.range.last + 1)
                val progress = controller.playback.value.progress
                val found = TtsAlignment.sourceRange(chunk, progress.text, progress.start, progress.word!!)!!
                assertEquals(match.value, chunk.substring(found.first, found.last + 1))
                cursor = match.range.last + 1
                words++
                if (unit == 0 && words == 4) controller.setSpeed(1.5f)
                if (unit == 1 && words == 13) controller.setSpeed(2.0f)
            }
            controller.onUtteranceComplete(engine.currentUtteranceId)
        }
        // Each speed change re-queues from the word being spoken, which the engine then says again.
        assertEquals(chunk.split(Regex("\\s+")).size + 2, words)
    }

    @Test
    fun `nextSpeed cycles through the supported rates`() {
        assertEquals(1.0f, TtsController.nextSpeed(0.5f), 0.0001f)
        assertEquals(2.0f, TtsController.nextSpeed(1.5f), 0.0001f)
        assertEquals(0.5f, TtsController.nextSpeed(2.0f), 0.0001f)
    }
}
