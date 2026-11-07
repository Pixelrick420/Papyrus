package com.papyrus.app.e2e

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Clears what an earlier run left on disk before any of the app's code opens it, and restores the
 * device's animations and window size in [finish].
 */
class PapyrusTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        FreshInstall.beforeAppCode(targetContext)
    }

    override fun finish(resultCode: Int, results: Bundle) {
        Animations.restore()
        TabletWindow.off()
        super.finish(resultCode, results)
    }
}
