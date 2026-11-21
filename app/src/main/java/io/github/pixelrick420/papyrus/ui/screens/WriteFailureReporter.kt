package io.github.pixelrick420.papyrus.ui.screens

import kotlin.coroutines.cancellation.CancellationException

/** Writes that fail are recorded individually but disturb the reader with one generic notice per session. */
class WriteFailureReporter(
    private val report: (Throwable) -> Unit,
    private val notify: (Int) -> Unit,
) {
    private var notified = false

    @Suppress("TooGenericExceptionCaught") // Repository failures range from disk errors to provider rejections.
    suspend fun runReporting(messageRes: Int, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
            if (!notified) {
                notified = true
                notify(messageRes)
            }
        }
    }
}
