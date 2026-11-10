package com.papyrus.app.e2e

import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

abstract class E2eTest {

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(RetryRule()).around(FreshInstallRule())
}

class FreshInstallRule : ExternalResource() {

    override fun before() {
        Animations.off()
        FreshInstall.ofRunningApp()
    }
}
