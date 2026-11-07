package com.papyrus.app.e2e

/**
 * Gives the device a tablet-sized window, for the layouts a phone never shows, and puts the real size back.
 *
 * 800dp by 1280dp is a window with room for the list and a document side by side. The size is set before the app
 * launches, so its first layout is already the wide one.
 */
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

    /** Idempotent, and a no-op for a run that never asked for a tablet. */
    fun off() {
        if (!applied) return
        applied = false
        Shell.run("wm size reset")
        Shell.run("wm density reset")
    }
}
