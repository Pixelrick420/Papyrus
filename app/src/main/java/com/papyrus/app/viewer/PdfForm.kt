package com.papyrus.app.viewer

/**
 * What kind of AcroForm widget a field is, reduced to what the reader can do with it. The raw values
 * are the platform's `FormWidgetInfo.WIDGET_TYPE_*` constants, kept as plain integers so this file
 * carries no Android types and the mapping is tested on the JVM.
 */
enum class PdfFieldKind {
    UNKNOWN,

    /** A push button: it runs an action, which this viewer does not offer. */
    BUTTON,
    CHECKBOX,
    RADIO,

    /** A combo or list box: a fixed set of choices. */
    CHOICE,

    /** A text field, single or multi-line. */
    TEXT,

    /** A signature: shown, never signed or changed. */
    SIGNATURE,
    ;

    /** Whether the reader may change this widget at all. */
    val editable: Boolean get() = this == CHECKBOX || this == RADIO || this == CHOICE || this == TEXT

    /** Whether tapping is the whole edit: a check box or radio button, with no value to type. */
    val toggles: Boolean get() = this == CHECKBOX || this == RADIO

    companion object {
        /** [raw] is `FormWidgetInfo.getWidgetType()`: 1 push, 2 check, 3 radio, 4 combo, 5 list, 6 text, 7 signature. */
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

/**
 * What kind of form a document carries. Only an AcroForm can be filled, so an XFA document stays
 * read-only rather than being half-rendered and half-edited.
 */
enum class PdfFormType {
    NONE,
    ACRO_FORM,
    XFA_FULL,
    XFA_FOREGROUND,
    ;

    val fillable: Boolean get() = this == ACRO_FORM

    companion object {
        /** [raw] is `PdfRenderer.getPdfFormType()`: 1 AcroForm, 2 XFA full, 3 XFA foreground. */
        fun of(raw: Int): PdfFormType = when (raw) {
            1 -> ACRO_FORM
            2 -> XFA_FULL
            3 -> XFA_FOREGROUND
            else -> NONE
        }
    }
}

/**
 * One AcroForm widget in viewer terms. [page] and [widget] are what an edit addresses; the rest is
 * what the sheet shows. [bounds] is null when the platform reported a box with no area.
 */
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

    /** Identifies the same widget across a re-read of the document, so a refresh can keep its place. */
    val key: String get() = "$page:$widget"

    /** Editable only where the document permits it and the kind offers an edit at all. */
    val writable: Boolean get() = !readOnly && kind.editable

    /**
     * A check box or radio button reports its export value, "Off" when it is clear; anything else
     * counts as set. The platform's own names are localised only in its UI, so this stays literal.
     */
    val checked: Boolean get() = kind.toggles && value.isNotEmpty() && !value.equals(OFF, ignoreCase = true)

    private companion object {
        const val OFF = "Off"
    }
}

/**
 * An edit applied to the open document in memory. Nothing reaches the source file: the edited
 * document is only ever written to a copy the reader picks.
 */
sealed interface PdfFormEdit {
    /** Replaces a text field's value. */
    data class Text(val widget: Int, val text: String) : PdfFormEdit

    /** Selects choice items by index; a single-choice box passes exactly one. */
    data class Choice(val widget: Int, val selected: List<Int>) : PdfFormEdit

    /** Taps a check box or radio button at its own centre, which is how the platform switches it. */
    data class Tap(val widget: Int) : PdfFormEdit
}
