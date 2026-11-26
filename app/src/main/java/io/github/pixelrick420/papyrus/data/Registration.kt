package io.github.pixelrick420.papyrus.data

/** What [DocumentRepository.register] did with a picked file; every case carries an id the viewer can open. */
sealed interface Registration {
    val id: Long

    /** Indexed in the library. */
    data class Added(override val id: Long) : Registration

    /** Password-protected, so opened for this session only; the library keeps no row for it. */
    data class Encrypted(override val id: Long) : Registration

    /** The provider refused a lasting grant (or the OS cap was reached), so opened for this session only. */
    data class GrantRefused(override val id: Long) : Registration
}
