package com.papyrus.app.viewer

// Plain digits only: a sign, a decimal point or a thousands separator is a mistake to point out, not something to guess at.
fun parseGoToNumber(input: String, max: Int): Int? {
    val text = input.trim()
    if (max < 1 || text.isEmpty() || !text.all { it in '0'..'9' }) return null
    return text.toIntOrNull()?.takeIf { it in 1..max }
}
