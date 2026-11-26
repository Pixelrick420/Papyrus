package io.github.pixelrick420.papyrus.ui.screens

import io.github.pixelrick420.papyrus.data.Registration

// How a multi-file pick went, one count per outcome, so the report can say what happened to every file.
internal class PickTally {
    var added = 0
        private set
    var encrypted = 0
        private set
    var grantRefused = 0
        private set
    var failed = 0
        private set

    fun record(registration: Registration) {
        when (registration) {
            is Registration.Added -> added++
            is Registration.Encrypted -> encrypted++
            is Registration.GrantRefused -> grantRefused++
        }
    }

    fun recordFailure() {
        failed++
    }
}
