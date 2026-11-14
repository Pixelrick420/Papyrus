package io.github.pixelrick420.papyrus.e2e

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

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
