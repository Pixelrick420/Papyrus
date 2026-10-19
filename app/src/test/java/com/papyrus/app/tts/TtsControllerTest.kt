package com.papyrus.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `nextSpeed cycles through the supported rates`() {
        assertEquals(1.0f, TtsController.nextSpeed(0.5f), 0.0001f)
        assertEquals(2.0f, TtsController.nextSpeed(1.5f), 0.0001f)
        assertEquals(0.5f, TtsController.nextSpeed(2.0f), 0.0001f)
    }
}
