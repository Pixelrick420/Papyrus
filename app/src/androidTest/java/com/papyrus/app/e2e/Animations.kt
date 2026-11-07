package com.papyrus.app.e2e

/**
 * Turns the device's animations off for the run and restores them afterwards, so a tap is not dropped
 * while a window transition is still playing.
 */
internal object Animations {

    private val SCALES = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    )

    /** The values the device had before [off], so [restore] can put them back. Null until [off] runs. */
    private var saved: Map<String, String?>? = null

    /** Idempotent: only the first call reads the device and changes anything. */
    fun off() {
        if (saved != null) return
        val current = SCALES.associateWith(::read)
        saved = current
        current.keys.forEach { write(it, "0") }
    }

    fun restore() {
        saved?.forEach { (scale, value) -> write(scale, value) }
    }

    private fun read(scale: String): String? = Shell.run("settings get global $scale")
        .takeUnless { it.isEmpty() || it == "null" }

    private fun write(scale: String, value: String?) {
        Shell.run(if (value == null) "settings delete global $scale" else "settings put global $scale $value")
    }
}
