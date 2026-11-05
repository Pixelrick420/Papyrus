package com.papyrus.app.e2e

import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/**
 * Base class for tests that drive the installed app: each starts from a fresh install, on a device that
 * will not drop a tap. The reset is a per-test rule because the runner starts only once for the suite.
 *
 * A failed test is run again, up to [RetryRule]'s limit. The retry is the outer rule so that every attempt,
 * not just the first, begins with [FreshInstallRule] clearing what the failed one left behind.
 */
abstract class E2eTest {

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RetryRule()).around(FreshInstallRule())
}

/** Clears everything the app persisted, and stops the system dropping taps while a screen animates in. */
class FreshInstallRule : ExternalResource() {

    override fun before() {
        Animations.off()
        FreshInstall.ofRunningApp()
    }
}
