# Known limitations

Papyrus aims to open as much as it can without a network connection, but some formats are read by a subset parser and
some features depend on the device. This page collects the per-format detail; the README lists only the headline
limits.

## PowerPoint

Slides draw text, tables, shapes and pictures, charts, SmartArt as node text, WordArt, vertical text, gradient and
pattern fills, glow, soft edge, reflection and colour effects, and embedded fonts. The gaps:

- EMF and WMF pictures render from their GDI records; an EMF+ only picture shows an "EMF+ image not displayed"
  placeholder.
- 3-D effects stay flat.
- Speaker notes in a deck are not shown.
- Find in file indexes at most 2,000,000 characters across the whole deck. Slides past that cap still render, but their
  text is not searchable.

## Word and OpenDocument

- DOC files carry paragraphs and tables only: no images, and headings arrive as plain paragraphs. Encrypted and Word
  6/95 files are rejected.
- Password-protected documents open when they are a PDF, a `.docx` (ECMA-376 Agile) or an `.odt` (ODF 1.2
  AES-256-CBC). Newer ODF 1.3 packages and the legacy binary schemes are reported as protected but not opened.

## EPUB

- Fixed-layout EPUB books keep one page boundary per page and the shape of a page-sized picture, but not absolutely
  positioned text, which is read in document order and reflows.
- A book's styles are read for bold, italic and text alignment only, through simple selectors (a type, an id and
  classes) and with every `.css` part merged into one sheet; colours, fonts and sizes stay the app's.
- SVG pictures are drawn once into a PNG from straight shapes only (rectangles, lines, polygons, circles and
  `M L H V Z` paths): curves, text, gradients, clipping and any transform but a translate are left out, and an SVG
  cover is not used as the book's thumbnail.
- Links between chapters and from the table of contents open the place they name; a link to a missing place says so.
- Right-to-left books (a `dir` attribute, or the spine's page progression) set paragraphs, lists and notes from the
  right, but a table keeps its left-to-right column order.

## PDF

- A very large PDF is not read for an outline, because PdfBox would hold a second copy of it in memory next to the
  renderer's; an outline already cached from an earlier open is still shown. The cut-off is 128 MB
  (`MAX_OUTLINE_FILE_BYTES` in `ViewerViewModel.kt`).
- Filling a PDF form needs Android 12 or newer with the PDF SDK extension that exposes form widgets, and writing
  highlights into a saved copy needs a newer extension, built in from Android 17. Elsewhere the page still renders,
  and its text, find and selection fall back to PDFBox, but no **Form fields** menu is offered and highlights stay in
  Papyrus alone. An XFA form is never offered for filling, a signature field can be seen but not signed, and only a
  capped number of form fields are read.
- Notes are stored, drawn and read in Papyrus only. A saved copy of the PDF carries plain highlights, not notes.

## Markdown and images

- Markdown shows an image only when the document carries it as a `data:` URI; the app never touches the network, so
  every other image falls back to its alt text.
- A `data:image/svg+xml` image, in its base64 or its percent-escaped form, is drawn by the same SVG subset the EPUB
  reader uses: shapes, straight paths, fill and stroke, `viewBox` and plain `translate`. What the subset cannot draw is
  left out, an oversized drawing or one that is not well formed falls back to its alt text, and a script in one is never
  run.
- Markdown is read up to 6 MB, since the renderer builds one spanned string for the whole document; past that the
  document opens with a truncation notice. Only the first 128 `$$...$$` formulas are rendered, and one longer than
  4,096 characters stays as its placeholder text.

## CSV and TSV

- CSV and TSV tables are not read aloud.
- A table is held whole, so it reads far less than plain text. The file is read up to 8 MB, a table is capped at
  200,000 cells and 1,024 columns, and a file past a cap is cut and ends with the viewer's incomplete-document notice
  instead of failing.

## Content search

Content search only finds text that is in the index.

- Encrypted and DRM-protected documents are not indexed, nor are files over the 64 MB source cap, and a document is
  indexed only up to 1,000,000 characters. A plain-text file is read up to 8 MB before that character cap applies.
- A scanned PDF with no text layer is indexed as empty.
- A document whose text could not be read is tried again after five minutes; no further retries happen once it is
  marked `SKIPPED` or `TOO_LARGE`.
- A document is searchable by content only once the indexer has reached it, which can take a while on a large library
  and, for files above the 512 KB eager threshold, does not happen while you read or under battery saver. With
  **Search inside documents** off it does not happen at all.
- The caps above are `MAX_SOURCE_BYTES`, `MAX_SOURCE_TEXT_BYTES` and `MAX_INDEX_CHARS` in `DocumentTextExtractor.kt`,
  and `EAGER_MAX_BYTES` in `SearchIndexer.kt`.

## Text and code

- Plain text and code have no size cap in UTF-8 and the single-byte encodings: a large file is indexed once and decoded
  as it is scrolled to, a few chunks at a time.
- One in UTF-16 or a multi-byte East Asian encoding is held in memory and cut at a size cap, as is one the file
  provider will not open twice.
