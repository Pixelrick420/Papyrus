package io.github.pixelrick420.papyrus.testutil

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.fail
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Fails a device test when the main Looper goes longer than its budget between heartbeats. */
class MainThreadStallRule(private val budgetMillis: Long = DEFAULT_BUDGET_MILLIS) : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val handler = Handler(Looper.getMainLooper())
            val lastBeat = AtomicLong(SystemClock.uptimeMillis())
            val longestGap = AtomicLong(0L)
            val beat = Runnable {
                val now = SystemClock.uptimeMillis()
                val gap = now - lastBeat.getAndSet(now)
                longestGap.updateAndGet { current -> maxOf(current, gap) }
            }
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "main-thread-stall-heartbeat").apply { isDaemon = true }
            }
            val heartbeat = scheduler.scheduleAtFixedRate(
                { handler.post(beat) },
                0L,
                HEARTBEAT_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            try {
                base.evaluate()
            } finally {
                heartbeat.cancel(false)
                scheduler.shutdownNow()
                handler.removeCallbacks(beat)
                val gap = longestGap.get()
                if (gap > budgetMillis) {
                    fail("Main thread stalled for ${gap}ms (budget ${budgetMillis}ms)")
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_BUDGET_MILLIS = 250L
        const val HEARTBEAT_INTERVAL_MILLIS = 16L
    }
}
