package io.github.pixelrick420.papyrus.e2e

import android.os.SystemClock

internal object Polling {

    fun until(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= until) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    private const val POLL_MS = 250L
}
