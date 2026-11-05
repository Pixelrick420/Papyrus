package com.papyrus.app.e2e

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Runs a test again when it fails, so a lost tap or a frame that arrived late on the CI emulator does not
 * fail the build. A test that fails on every attempt still fails, with the earlier attempts attached to its
 * stack trace as suppressed exceptions, so nothing about the failures is lost.
 *
 * Put outside [FreshInstallRule] (see [E2eTest]) so each attempt starts from the same fresh install as the first.
 *
 * Retries are not free, and they must not hide a broken build, so two limits apply:
 *  - a test gets at most [attempts] attempts, and
 *  - the whole run gets at most [MAX_RETRIES_PER_RUN] retries between all its tests. A build that is wrong
 *    rather than flaky fails nearly everything; without this cap each of those tests would be run [attempts]
 *    times and the job would end at its timeout instead of at a report.
 *
 * A test that fails and then passes is reported to logcat under [TAG] as `FLAKY ...`; CI lists those in the job
 * summary, because a retry that goes unseen is a flake nobody gets to fix.
 */
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

    /** The failure's type and first line of message: enough to tell flakes apart in logcat, short enough to grep. */
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

        /** Takes one retry from the run's budget; false once it is spent. */
        fun claimRetry(): Boolean = retriesLeft.getAndUpdate { maxOf(it - 1, 0) } > 0
    }
}
