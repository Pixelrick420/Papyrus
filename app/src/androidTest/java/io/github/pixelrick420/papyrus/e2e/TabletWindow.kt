package io.github.pixelrick420.papyrus.e2e

internal object TabletWindow {

    private const val WIDTH_PX = 1600
    private const val HEIGHT_PX = 2560
    private const val DENSITY_DPI = 320

    private var applied = false

    fun on() {
        applied = true
        Shell.run("wm size ${WIDTH_PX}x$HEIGHT_PX")
        Shell.run("wm density $DENSITY_DPI")
    }

    fun off() {
        if (!applied) return
        applied = false
        Shell.run("wm size reset")
        Shell.run("wm density reset")
    }
}
