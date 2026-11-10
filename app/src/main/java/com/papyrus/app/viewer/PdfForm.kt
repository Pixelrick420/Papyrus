package com.papyrus.app.viewer

// The raw values are the platform's `FormWidgetInfo.WIDGET_TYPE_*` constants kept as plain integers, so this file carries no Android types and the mapping is tested on the JVM.
enum class PdfFieldKind {
    UNKNOWN,

    BUTTON,
    CHECKBOX,
    RADIO,

    CHOICE,

    TEXT,

    SIGNATURE,
    ;

    val editable: Boolean get() = this == CHECKBOX || this == RADIO || this == CHOICE || this == TEXT

    val toggles: Boolean get() = this == CHECKBOX || this == RADIO

    companion object {
        fun of(raw: Int): PdfFieldKind = when (raw) {
            1 -> BUTTON
            2 -> CHECKBOX
            3 -> RADIO
            4, 5 -> CHOICE
            6 -> TEXT
            7 -> SIGNATURE
            else -> UNKNOWN
        }
    }
}

// Only an AcroForm can be filled, so an XFA document stays read-only rather than half-rendered and half-edited.
enum class PdfFormType {
    NONE,
    ACRO_FORM,
    XFA_FULL,
    XFA_FOREGROUND,
    ;

    val fillable: Boolean get() = this == ACRO_FORM

    companion object {
        fun of(raw: Int): PdfFormType = when (raw) {
            1 -> ACRO_FORM
            2 -> XFA_FULL
            3 -> XFA_FOREGROUND
            else -> NONE
        }
    }
}

data class PdfFormField(
    val page: Int,
    val widget: Int,
    val kind: PdfFieldKind,
    val bounds: NormRect?,
    val label: String?,
    val value: String,
    val options: List<String> = emptyList(),
    val selectedOptions: List<Int> = emptyList(),
    val readOnly: Boolean = false,
    val multiline: Boolean = false,
    val multiSelect: Boolean = false,
) {

    val key: String get() = "$page:$widget"

    val writable: Boolean get() = !readOnly && kind.editable

    // The platform's own names are localised only in its UI, so "Off" stays literal.
    val checked: Boolean get() = kind.toggles && value.isNotEmpty() && !value.equals(OFF, ignoreCase = true)

    private companion object {
        const val OFF = "Off"
    }
}

// An edit applied to the open document in memory. Nothing reaches the source file: the edited
// document is only ever written to a copy the reader picks.
sealed interface PdfFormEdit {
    data class Text(val widget: Int, val text: String) : PdfFormEdit

    data class Choice(val widget: Int, val selected: List<Int>) : PdfFormEdit

    data class Tap(val widget: Int) : PdfFormEdit
}
