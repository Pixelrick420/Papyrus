package com.papyrus.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// Flows keep this free of Android types so the indexer can be tested with plain ones.
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

// A count rather than a flag: a second viewer can be entered before the first has left.
class ReaderPresence {
    private val viewers = MutableStateFlow(0)

    val reading: Flow<Boolean> = viewers.map { it > 0 }.distinctUntilChanged()

    fun entered() = viewers.update { it + 1 }

    /** Never below zero, so a stray extra call cannot leave the indexer paused for good. */
    fun left() = viewers.update { (it - 1).coerceAtLeast(0) }
}
