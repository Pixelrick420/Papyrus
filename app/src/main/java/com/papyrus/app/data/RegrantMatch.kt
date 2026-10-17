package com.papyrus.app.data

/** The title stored for a document whose provider reports no display name. */
internal const val UNTITLED = "untitled"

/**
 * Whether a re-picked file is the document a row or hand-over stands for. Name and format must match
 * exactly; size is deliberately not compared (the file may have been edited) and case is exact.
 */
internal fun matchesRepick(
    expectedTitle: String,
    expectedFormat: DocumentFormat,
    pickedName: String,
    pickedFormat: DocumentFormat,
): Boolean =
    pickedName.ifBlank { UNTITLED } == expectedTitle && formatsCompatible(expectedFormat, pickedFormat)

/** Equal formats, except a legacy UNKNOWN row counts as the CODE the sniff now resolves it to. */
private fun formatsCompatible(expected: DocumentFormat, picked: DocumentFormat): Boolean =
    expected == picked ||
        (expected == DocumentFormat.UNKNOWN && picked == DocumentFormat.CODE) ||
        (expected == DocumentFormat.CODE && picked == DocumentFormat.UNKNOWN)
