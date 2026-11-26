package io.github.pixelrick420.papyrus.ui.screens

import java.io.Closeable

// The one open source, keyed by URI so a re-grant never reads the dead one. It is built and closed only here, under one lock,
// so two callers never build competing sources and none is left unclosed; [open] must only construct, never call back in.
internal class PageSourceHolder<T : Closeable>(private val open: (String) -> T) {

    private val lock = Any()
    private var source: T? = null
    private var uri: String? = null
    private var released = false

    /** Null once [release]d, so a late caller does not open a source nothing will close. */
    fun get(uri: String): T? = synchronized(lock) {
        if (released) return null
        source?.takeIf { this.uri == uri } ?: open(uri).also { swap(it, uri) }
    }

    /** A document switch: the next [get] opens afresh. */
    fun reset() = synchronized(lock) { swap(null, null) }

    fun release() = synchronized(lock) {
        released = true
        swap(null, null)
    }

    private fun swap(next: T?, nextUri: String?) {
        val old = source
        source = next
        uri = nextUri
        old?.close()
    }
}
