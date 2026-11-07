package com.papyrus.app.e2e

import android.os.SystemClock

/** Waiting for something that is not on the screen, such as a file or a row of the database. */
internal object Polling {

    /** True once [condition] holds, checking until [timeoutMs] runs out. */
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
