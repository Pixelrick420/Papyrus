package io.github.pixelrick420.papyrus.viewer

internal const val DEFAULT_ASPECT = 1.414f

private const val MIN_ASPECT = 0.1f
private const val MAX_ASPECT = 10f

internal fun sanitizedAspect(width: Float, height: Float): Float {
    val ratio = height / width
    if (!ratio.isFinite() || ratio <= 0f) return DEFAULT_ASPECT
    return ratio.coerceIn(MIN_ASPECT, MAX_ASPECT)
}
