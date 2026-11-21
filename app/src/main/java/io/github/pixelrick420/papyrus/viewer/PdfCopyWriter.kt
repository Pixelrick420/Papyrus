package io.github.pixelrick420.papyrus.viewer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable

// Provider opens and deletes are binder calls; cancellation must not skip the delete that removes a partial copy.
internal class PdfCopyWriter<K, D : Closeable>(
    private val open: (K) -> D?,
    private val writeCopy: suspend (D, Map<Int, List<List<NormRect>>>) -> Boolean,
    private val delete: (K) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    // Deleting a partial copy runs in NonCancellable before the cancellation is rethrown.
    @Suppress("SuspendFunSwallowedCancellation")
    suspend fun write(target: K, highlights: Map<Int, List<List<NormRect>>> = emptyMap()): Boolean {
        val destination = try {
            withContext(dispatcher) { open(target) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        val written = try {
            destination.use { writeCopy(it, highlights) }
        } catch (e: CancellationException) {
            discard(target)
            throw e
        } catch (_: Exception) {
            false
        }
        if (!written) discard(target)
        return written
    }

    private suspend fun discard(target: K) {
        withContext(NonCancellable + dispatcher) { runCatching { delete(target) } }
    }
}
