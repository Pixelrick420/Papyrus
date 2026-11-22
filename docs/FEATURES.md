# Features

The README is the landing page. This is the reference for behavior that needs exact wording.

## Library

- **Search** filters by title and content, shows highlighted excerpts, requires every term, drops a one-character term
  when a longer one is present, and matches by prefix. Opening a content match lands on the first occurrence.
- **Sort/filter** lives in one sheet: last opened, name (natural order), date added, size; filters for PDF, Word, ODT,
  EPUB, PowerPoint, Markdown, text, CSV, TSV, code and starred. Choices survive rotation.
- **Star**, **bulk select** (long press), **share** (read-only grant), **remove** with Undo, and **file info** (name,
  format, size, provider, page/slide count, presentation size/font substitutions, timestamps, URI). Long operations
  show "Removing 3 of 12" and end with one announcement.
- **Thumbnails** are cached; unreadable ones use a short or permanent failure TTL instead of re-rendering on scroll.
  Encrypted Office documents do not get a thumbnail.
- **Two panes** appear on wide, tall windows; the list keeps its scroll and search when a document opens beside it.

## Reading

- **Find in file** (Ctrl+F), **outline/go to page or line** (Ctrl+G), **scrub preview** in text/code, **bookmarks**
  (Ctrl+D) with a per-document cap, and **reading position** with the same zoom after rotation.
- **PDF selection**: long press to copy; library PDFs also offer Highlight/Remove highlight and note editing. Notes stay
  in Papyrus; **Save highlighted copy** writes plain highlights only through the system picker.
- **PDF forms**: AcroForm fields are offered where the platform exposes them; edits affect the open document until
  **Save a copy**. XFA cannot be filled; signatures can be seen but not signed.
- **Read aloud** speaks pages, slides, chunks, blocks and markdown paragraphs; it shades sentence/word
  where the engine reports timing, works with the screen off, pauses for calls/other audio/headphone unplug, and
  exposes play/pause, skip, lock, speed and stop. Long units are split at sentence bounds where possible, otherwise
  at whitespace or a hard cut, into utterances of at most `TtsUnitSplitter.MAX_UNIT_CHARS` (3,500). Office tables are
  spoken row by row, engine rejection is shown as an error, and media controls show the document title. CSV has no
  speech units.
- **Text chunks** show a blank placeholder while loading and "Couldn't read this part. Tap to retry." on failure.
- **PDF page sizes** load lazily: page one sets the initial layout, then the rest arrive in chunks.
- **Links**: `http`, `https`, `mailto`, `tel` and PDF internal links. PDF links are labelled keyboard/TalkBack stops;
  slide links stay tap-only. Scheme-less text is completed only under the caller's policy: general links require
  `www.`; PDF URI actions also accept host-like names such as `example.com`. Bare file names never open.
- **Zoom** is pinch/double-tap within a bounded range.

## Formats

- **PowerPoint**: text, tables, shapes, pictures and charts (bar, column, line, area, scatter, pie, doughnut, radar,
  bubble, combo, stock), SmartArt node text, WordArt, vertical text, effects and embedded fonts.
- **Markdown**: tables, task lists, `$$...$$` maths, autolinks, colored fences, `data:` images and a Footnotes list with
  jumping markers.
- **DOC/DOCX/ODT/EPUB**: outlined where the format provides headings; EPUB cover is the thumbnail.
- Per-format gaps are in [LIMITATIONS.md](LIMITATIONS.md).

## Opening documents

- **Open with** opens without adding to the library; **Share to Papyrus** accepts one `content://` file (not shared
  text, `file://` or multiple files). **Save to library** keeps a copy.
- Failed bookmark, highlight or note writes show one notice, even when several fail.
- Password-protected PDF, DOCX, ODT and PPTX files unlock locally; the password is never stored or logged.
- Lost access or a moved/deleted file offers **Pick file**. Open errors name unsupported, protected or damaged files.

## Privacy & safety

- No network or storage permission. Foreground-service and media-playback permissions keep speech running with the
  screen off; `POST_NOTIFICATIONS` is asked on first read aloud (Android 13+). A `<queries>` entry exposes the chosen
  TTS engine.
- EPUB, Word, OpenDocument and PPTX ZIPs are read with entry, size and ratio limits; bombs fail the file, unsafe EPUB
  entry names are skipped and Office media names are reduced to a basename. Bytes inflated only to skip unused entries
  use a separate 2 GiB budget, so a large embedded video does not fail the document. The ratio check still covers
  skipped bytes.
- Import name stems are capped at 120 UTF-16 code units and 200 UTF-8 bytes before the detected extension is appended.
- Crash reports are local ACRA JSON with paths, URIs and storage locations redacted. Highlights and notes stay in the
  private database unless a copy is saved.

See [PRIVACY_POLICY.md](../PRIVACY_POLICY.md), [SETTINGS.md](SETTINGS.md) and
[ACCESSIBILITY.md](../ACCESSIBILITY.md).
