package io.github.pixelrick420.papyrus.ui.screens

import io.github.pixelrick420.papyrus.data.Registration
import org.junit.Assert.assertEquals
import org.junit.Test

class PickTallyTest {

    @Test
    fun `a new tally has counted nothing`() {
        val tally = PickTally()

        assertEquals(listOf(0, 0, 0, 0), listOf(tally.added, tally.encrypted, tally.grantRefused, tally.failed))
    }

    @Test
    fun `every outcome is counted on its own, so none is reported as another`() {
        val tally = PickTally()

        tally.record(Registration.Added(1))
        tally.record(Registration.Added(2))
        tally.record(Registration.Encrypted(-1))
        tally.record(Registration.GrantRefused(-2))
        tally.record(Registration.GrantRefused(-3))
        tally.record(Registration.GrantRefused(-4))
        tally.recordFailure()

        assertEquals(listOf(2, 1, 3, 1), listOf(tally.added, tally.encrypted, tally.grantRefused, tally.failed))
    }
}
