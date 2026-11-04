package com.papyrus.app.e2e

import org.junit.Rule
import org.junit.rules.ExternalResource

/**
 * Base class for tests that drive the installed app: each starts from a fresh install, on a device that
 * will not drop a tap. The reset is a per-test rule because the runner starts only once for the suite.
 */
abstract class E2eTest {

    @get:Rule
    val freshInstall = FreshInstallRule()
}

/** Clears everything the app persisted, and stops the system dropping taps while a screen animates in. */
class FreshInstallRule : ExternalResource() {

    override fun before() {
        Animations.off()
        FreshInstall.ofRunningApp()
    }
}
