package com.papyrus.app.viewer

/**
 * What the reader typed into a go-to dialog, as a one-based number from 1 to [max], or null when it
 * is not one. Plain digits only: a sign, a decimal point or a thousands separator is a mistake to
 * point out, not something to guess at, and an absurdly long number simply is not in range.
 */
fun parseGoToNumber(input: String, max: Int): Int? {
    val text = input.trim()
    if (max < 1 || text.isEmpty() || !text.all { it in '0'..'9' }) return null
    return text.toIntOrNull()?.takeIf { it in 1..max }
}
