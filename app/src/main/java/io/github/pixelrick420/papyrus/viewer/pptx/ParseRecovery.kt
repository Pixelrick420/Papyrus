package io.github.pixelrick420.papyrus.viewer.pptx

import kotlinx.coroutines.CancellationException

/** Treats a malformed part as a recoverable failure, never as a reason to abandon a cancelled load. */
internal inline fun <T> recover(default: T, block: () -> T): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (ignoredOom: OutOfMemoryError) {
        default
    } catch (ignored: Exception) {
        default
    }

internal suspend inline fun <T> recoverSuspend(default: T, crossinline block: suspend () -> T): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (ignoredOom: OutOfMemoryError) {
        default
    } catch (ignored: Exception) {
        default
    }
