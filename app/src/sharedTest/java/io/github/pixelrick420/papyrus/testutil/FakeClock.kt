package io.github.pixelrick420.papyrus.testutil

/** Deterministic clock for TTL and sweep tests. */
class FakeClock(initialMillis: Long = 1_000L) {

    val now: () -> Long = { current }

    private var current: Long = initialMillis

    fun advance(millis: Long) {
        current += millis
    }
}
