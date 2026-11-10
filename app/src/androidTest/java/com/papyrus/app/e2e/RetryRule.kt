package com.papyrus.app.e2e

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

// Reruns a failed test so a lost tap or a late frame on the CI emulator does not fail the build; a test that fails every attempt still fails.
class RetryRule(private val attempts: Int = DEFAULT_ATTEMPTS) : TestRule {

    init {
        require(attempts >= 1) { "attempts must be at least 1, was $attempts" }
    }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val name = "${description.className.substringAfterLast('.')}#${description.methodName}"
            val failures = ArrayList<Throwable>()
            for (attempt in 1..attempts) {
                try {
                    base.evaluate()
                    if (failures.isNotEmpty()) {
                        Log.w(TAG, "FLAKY $name passed on attempt $attempt/$attempts; first failure: ${failures.first().summary()}")
                    }
                    return
                } catch (skipped: AssumptionViolatedException) {
                    // An assumption is a deliberate skip, not a failure: running it again changes nothing.
                    throw skipped
                } catch (failure: Throwable) {
                    // Throwable, not Exception: a test's failure is usually an AssertionError, which is an Error.
                    failures += failure
                    Log.w(TAG, "$name attempt $attempt/$attempts failed: ${failure.summary()}")
                    if (attempt == attempts || !claimRetry()) break
                }
            }
            val last = failures.last()
            failures.forEach { if (it !== last) last.addSuppressed(it) }
            throw last
        }
    }

    private fun Throwable.summary(): String {
        val firstLine = message.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return "${javaClass.simpleName}: $firstLine".take(SUMMARY_CHARS)
    }

    private companion object {
        const val TAG = "PapyrusRetry"
        const val DEFAULT_ATTEMPTS = 3
        const val MAX_RETRIES_PER_RUN = 15
        const val SUMMARY_CHARS = 300

        /** Retries left for the whole run: the instrumentation process lives for the suite, so a static is the run. */
        val retriesLeft = AtomicInteger(MAX_RETRIES_PER_RUN)

        fun claimRetry(): Boolean = retriesLeft.getAndUpdate { maxOf(it - 1, 0) } > 0
    }
}
