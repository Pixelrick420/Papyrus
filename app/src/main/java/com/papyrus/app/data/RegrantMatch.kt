package com.papyrus.app.data

internal const val UNTITLED = "untitled"

// Name and format must match exactly; size is deliberately not compared (the file may have been edited).
internal fun matchesRepick(
    expectedTitle: String,
    expectedFormat: DocumentFormat,
    pickedName: String,
    pickedFormat: DocumentFormat,
): Boolean =
    pickedName.ifBlank { UNTITLED } == expectedTitle && formatsCompatible(expectedFormat, pickedFormat)

// Equal formats, except a legacy UNKNOWN row counts as the CODE the sniff now resolves it to, and a .csv or
// .tsv stored as CODE before it had a format of its own counts as the CSV it is now.
private fun formatsCompatible(expected: DocumentFormat, picked: DocumentFormat): Boolean =
    expected == picked ||
        (expected == DocumentFormat.UNKNOWN && picked == DocumentFormat.CODE) ||
        (expected == DocumentFormat.CODE && picked == DocumentFormat.UNKNOWN) ||
        (expected == DocumentFormat.CODE && picked == DocumentFormat.CSV)
