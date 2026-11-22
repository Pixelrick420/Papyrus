# Known limitations

## PowerPoint

- EMF/WMF render from GDI records; EMF+-only pictures show a placeholder.
- 3-D effects are flat; speaker notes are not shown.
- Find indexes at most 2,000,000 characters; later slides still render but are not searchable.

## Word and OpenDocument

- DOC carries paragraphs and tables only: no images. Styled headings render as headings and appear in the outline;
  encrypted and Word 6/95 files are rejected.
- Protected files open as `.docx`/`.pptx` (ECMA-376 Agile) or `.odt` (ODF with AES-256-CBC and PBKDF2), plus PDF.
  Unsupported ODF encryption and legacy schemes are reported as protected but not opened; ODF files are gated by their
  encryption parameters, not the ODF version.

## EPUB

- Fixed-layout pages keep page-sized picture shape; absolutely positioned text reflows in document order.
- CSS supports bold, italic and alignment through simple selectors; colors, fonts and sizes stay the app's.
- SVG supports rectangles, lines, polygons, circles and `M L H V Z` paths, fill/stroke, `viewBox` and translate.
  Curves, text, gradients, clipping and other transforms are omitted; an SVG cover is not the thumbnail.
- Chapter/TOC links work; missing destinations report an error. RTL books set text from the right, but tables keep
  left-to-right columns.

## PDF

- Outlines are skipped above 128 MB (`MAX_OUTLINE_FILE_BYTES` in `ViewerViewModel.kt`); a cached outline still shows.
- Form fields need Android 15 (API 35) or Android 12+ with PDF SDK extension 13+; writing highlights into a saved copy
  needs extension 18+ or API 37+. Elsewhere text, find and selection fall back to PDFBox. XFA cannot be filled,
  signatures cannot be signed, and field count is capped.
- Notes are stored and drawn in Papyrus only; saved copies carry plain highlights.

## Markdown and images

- Only `data:` images are shown; others fall back to alt text.
- SVG uses the EPUB subset. Bad or oversized drawings fall back; scripts never run.
- Markdown reads up to 6 MB, renders up to 128 `$$...$$` formulas, and leaves formulas over 4,096 characters as text.

## CSV and TSV

- Not read aloud. Held whole: 8 MB file, 200,000 cells, 1,024 columns and 10,000 characters per cell (`MAX_CELL_CHARS` in `DelimitedFile.kt`); over a cap the view is truncated with the
  incomplete-document notice.

## Content search

- Encrypted/DRM files and files over 64 MB are not indexed; text is capped at 1,000,000 characters (8 MB for plain
  text). Scanned PDFs with no text layer index as empty.
- `FAILED` rows become eligible after five minutes, with no timer: they retry on the next indexing pass. `SKIPPED` and
  `TOO_LARGE` never retry.
- Unicode marks stay with their letters in queries; the index tokenizer splits them into a phrase of fragments, which
  can still match unrelated adjacent fragments. CJK and Thai runs are single tokens.
- Files above 512 KB wait for the gated pass, which pauses while reading and under battery saver. With
  **Search inside documents** off, nothing new is indexed.
- Constants: `MAX_SOURCE_BYTES`, `MAX_SOURCE_TEXT_BYTES`, `MAX_INDEX_CHARS` in `DocumentTextExtractor.kt`;
  `EAGER_MAX_BYTES` in `SearchIndexer.kt`.

## Text and code

- UTF-8 and single-byte files have no size cap: text is decoded in chunks as it scrolls.
- UTF-16, multi-byte East Asian files and files the provider will not reopen twice are held in memory and capped.
- Recognised code files seed lexer states for later chunks on demand (`lazyChunkStartStates` / `ensure(index)` in `SyntaxHighlighter.kt`); a chunk over `MAX_HIGHLIGHT_CHARS` (20,000) stays plain.

## Read aloud

- The TTS voice and sentence splitting use the device locale (`Locale.getDefault()` in `TtsPlaybackService.kt` and
  `TtsAlignment.kt`), with English only as a fallback when that locale has no TTS data; there is no per-document
  language choice.
