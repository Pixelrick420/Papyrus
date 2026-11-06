package com.papyrus.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Whether the content indexer may work right now: only when the person has not turned it off, is not
 * reading a document, and the device is not saving battery.
 *
 * The three conditions arrive as flows so this holds no Android type and the indexer can be tested
 * with plain ones; [batterySaverFlow] and [ReaderPresence] are the real sources.
 */
class IndexingGate(
    enabled: Flow<Boolean>,
    reading: Flow<Boolean>,
    batterySaver: Flow<Boolean>,
) {
    /** True while indexing may run. Changes only when the answer does, so a collector restarts only then. */
    val open: Flow<Boolean> = combine(enabled, reading, batterySaver) { on, busy, saving ->
        on && !busy && !saving
    }.distinctUntilChanged()
}

/**
 * How many screens are showing a document to read. The viewer registers while it is in the foreground
 * and leaves when it pauses or goes, so a document opened beside the list and one opened full screen
 * count the same way. A count rather than a flag because a second viewer can be entered before the
 * first one has left.
 */
class ReaderPresence {
    private val viewers = MutableStateFlow(0)

    /** True while at least one viewer is in the foreground. */
    val reading: Flow<Boolean> = viewers.map { it > 0 }.distinctUntilChanged()

    fun entered() = viewers.update { it + 1 }

    /** Never below zero, so a stray extra call cannot leave the indexer paused for good. */
    fun left() = viewers.update { (it - 1).coerceAtLeast(0) }
}
