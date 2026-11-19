# Features

The README keeps the headline list. This page is the detailed reference, including behaviour that is easier to
describe at length than in a landing page.

## Library

- **Search** filters the list by title as you type and also looks inside the documents. A document that matches by
  content shows an excerpt under its title, with the matching words highlighted. Every word must appear, though a
  single-character word is dropped when a longer one is typed, and a word matches by prefix, so `scal` finds "scaling".
  Content matches are limited to the most relevant documents, and the list keeps your chosen sort order. Opening a
  content match lands on the first occurrence of the first searchable word and holds a highlight on it briefly before
  it fades; where find cannot search the document, it opens at the saved position instead.
- **Sort & filter** is the slider icon beside the search pill. One sheet holds every sort order, every file-type
  filter and the starred filter, with a badge counting what is on and a live count of matching files.
- **Sort** by last opened, name (A to Z or Z to A), date added or size. Names sort the way a person reads them, so
  `file2` comes before `file10`, and the choice is remembered.
- **File-type filters** for PDF, Word, ODT, EPUB, PowerPoint, Markdown, text, CSV, TSV and code, plus starred files.
  They combine with search and sorting, survive rotation, and a **Clear filters** button appears when nothing matches.
- **Star** a file from its row menu. Reopening it or re-granting access keeps the star.
- **Thumbnails** for every document, with the format label until one is ready.
- **Bulk select** on a long-press, then share the whole selection or remove it after a confirmation. A remove or a
  share that takes longer than a moment shows how far it has got ("Removing 3 of 12") and ends with one announcement.
  A single row's **Remove** can still be undone for a few seconds.
- **Tablets and foldables** show the library and a document side by side once the window is wide enough for two panes
  and tall enough that a phone on its side keeps one pane. Tapping a document opens it beside the list without
  moving the list's scroll position or search, and the open row is highlighted. Opened from outside (another app, a
  crash report) a document still fills the window.
- **Share** sends a read-only grant, so the receiving app cannot write, rename or delete the file.
- **Remove** with **Undo**. Your own file on disk is never touched.
- **File info**: name, format, size, provider type, page count for PDFs, slide count, size and font substitutions for
  presentations, both timestamps and the content URI.

## Reading

- **Find in file** (Ctrl+F) in every format, with the current match highlighted.
- **PDF outline and go to page** (Ctrl+G). The PDF's own bookmarks become a foldable **Outline**, and a long one starts
  folded. A long outline appears as it is read, and is cached, so the next open shows it at once.
- **Go to line** (Ctrl+G) in long text and code: type a line number to jump to it. It lands on the line exactly where
  lines do not wrap, and within its chunk where they do.
- **Scrub preview** in text and code: while the scroll knob is held, a readout beside it shows the line at the top of
  the screen and the first words of it.
- **Bookmarks** (Ctrl+D) in every format: named or positional, rename and delete with Undo, with a per-document cap.
- **PDF text selection**: long-press to select text, then tap **Copy** to put it on the clipboard. For a library file,
  **Highlight** also saves the selection, and on a selection that touches one, **Remove highlight** takes it off.
  Tap a stored highlight to write or edit its note; clearing the text and saving makes it a plain highlight again. A
  note keeps the highlighter wash, adds a rule and a dot, and lives in Papyrus. Highlights and notes are kept in
  Papyrus's own database, so the PDF itself is never changed.
  Where the device can write page annotations, **Save highlighted copy** writes the plain highlights into a new
  PDF through the system picker. The notes stay behind, since a saved copy carries highlights only.
- **PDF form fields**: a PDF with a fillable AcroForm offers **Form fields** in the menu. Each field shows its label,
  page, value and state; text and choice fields open a dialog, while check boxes and radio buttons switch on tap.
  Edits apply to the open document only: **Save a copy** writes a new file through the system picker and never
  changes the original. An XFA form offers no fields, and a signature field can be seen but not signed.
- **Reading position** reopens a document where you stopped, at the same zoom, even after a rotation. A switch in
  Settings turns it off.
- **Read aloud** speaks the page, chunk, block or paragraph, shades the sentence being read (and the word, where the
  engine reports timing), and keeps going with the screen off. It pauses for a call or another app's audio, and when
  headphones are unplugged; a short interruption resumes on its own. Play/pause, skip, lock, speed and stop are on the
  bar. It is offered only when the document has selectable text; a document with nothing speakable hides it.
- **Zoom** with a pinch or a double-tap, across a bounded range.
- **Links** open on tap for `http`, `https`, `mailto` and `tel`. A PDF's internal link scrolls to its page.

## PowerPoint

Slides draw text, tables, shapes and pictures, bar, column, line, area, scatter, pie, doughnut, radar, bubble, combo
and stock charts, SmartArt as node text, WordArt, vertical text, gradient and pattern fills, glow, soft edge,
reflection and colour effects, and embedded fonts.

Find in file, an outline from slide titles, bookmarks, links, slide jumps and read-along highlighting work over
slides. The per-format caveats — EMF+ pictures, 3-D effects, speaker notes and the rest — are in
[LIMITATIONS.md](LIMITATIONS.md).

## Markdown

Markdown supports tables, task lists, `$$...$$` maths (block or inline), autolinks, coloured code fences and `data:`
URI images. Footnotes are gathered into a **Footnotes** list at the end of the document, numbered in the order they are
first cited, and a tap on a marker or its way back scrolls there.

Only images carried inside the document as a `data:` URI are shown, since the app never touches the network; see
[LIMITATIONS.md](LIMITATIONS.md) for image and SVG details.

## Opening documents

- **Open with** shows a document from another app without adding it to the library.
- **Save to library** keeps a copy, so it outlives the other app's grant.
- **Password-protected documents** unlock locally: Word, OpenDocument and PDF. The password is never stored or logged,
  and the temporary copy of a Word or OpenDocument file is deleted when you close it.
- **Lost access** keeps the page on screen and offers to re-pick the file, matching on name and format. A file that
  was moved or deleted offers the same.
- **Open errors** say what went wrong: an unsupported type, a protection Papyrus cannot unlock, or a damaged file.

## Privacy & safety

The manifest declares no network or storage permission. The only permissions it asks for are `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, which let read-aloud keep speaking with the screen off, and `POST_NOTIFICATIONS`,
which lets read-aloud show its playback notification (asked the first time read-aloud is used, on Android 13 and up). A
`<queries>` entry for the text-to-speech service lets read-aloud see the speech engine you chose, since Android 11 and
up hides other apps otherwise.

- **EPUB safety.** A book is a ZIP from someone else, so it is read with hard limits and never extracted to disk beyond
  its images. A zip-slip entry, an oversized archive or a decompression bomb fails the book, not the app, and DRM is
  refused. Word and OpenDocument files are ZIPs too and are read under the same limits on entries, size and
  compression ratio.
- **Crash reports** are JSON files in app storage, written by ACRA. Nothing leaves the device.
- **Highlights and notes** stay in Papyrus's private database, are removed with the document, and are never written
  into your file unless you save a copy. A saved copy carries plain highlights only; notes are not exported.

The full policy is in [PRIVACY_POLICY.md](../PRIVACY_POLICY.md), the settings reference in
[SETTINGS.md](SETTINGS.md), and the TalkBack contract in [ACCESSIBILITY.md](../ACCESSIBILITY.md).
