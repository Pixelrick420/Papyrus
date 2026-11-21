# Architecture

Papyrus is Kotlin, Jetpack Compose and Room.

## Flow

- Sources: file picker (always joins the library), another app's handover (joins only with **Autosave external docs**
  on) and share target (one `content://` file only).
- Format is detected from extension, then MIME type, then a content sniff.
- Viewer readers extract the text used by both search and read aloud; PDF copy writes go through `PdfCopyWriter`.

## Data

| Table | Role |
|---|---|
| `documents` | one row per document: URI, title, format, size, added/last-opened times, starred flag |
| `reading_progress` | one location per document, with unit, zoom and progress |
| `bookmarks` | named or positional locations |
| `highlights` | rects, optional kind and note |
| `pdf_outlines` | outline cache keyed by source size, source modified time and cache version |
| `document_text` | extracted text, state, character count, source size, indexed time/version |
| `document_text_fts` | FTS4 inverted index maintained by Room triggers; text is not stored twice |

Child rows use `ON DELETE CASCADE`. `documents` rows are never written with `OnConflictStrategy.REPLACE`, which would
delete and reinsert them and cascade the children away. Page count and MIME type are read live. A 0.x database is
deleted on first 1.0 launch; version 2 adds `pdf_outlines.sourceModified` and migrates a 1.x database in place.
Schemas are exported to `app/schemas/`.

## Cache layout

All paths are under `cacheDir`, private and disposable. Startup sweeping runs off the main thread before a document
opens; age cut-offs are measured from the current process start.

| Path | Written by | Cleaned by |
|---|---|---|
| `pdf-unlock/` | PDF password unlocking | `MainApplication.sweepUnlocked` at startup; `UnlockedCopy` on replace/discard |
| `office-unlock/` | Office password unlocking | `MainApplication.sweepUnlocked` at startup |
| `pdf-view/` | `PdfPageSource` copies of non-seekable PDF descriptors | startup age sweep; `PdfPageSource.close` |
| `office-media/` | viewer image extraction, in `<document id>/` | `ViewerViewModel.onCleared`; startup age sweep |
| `pptx-<uuid>/` | `SlidePageSource` media extraction | `SlidePageSource.close`; startup age sweep |

`pdf-view/` replaced older `view-*.pdf` files; those are no longer written.

## Content search

- Indexer uses `document_text` as its queue; states are `INDEXED`, `FAILED`, `SKIPPED` and `TOO_LARGE`.
- A `FAILED` row is eligible again after five minutes (`indexedAt`), but there is no timer: it is retried only on the
  next indexing pass. `SKIPPED` and `TOO_LARGE` are terminal.
- At or below `SearchIndexer.EAGER_MAX_BYTES`, text is read at once, even while a document is open or battery saver is
  on. The **Search inside documents** switch still applies. Larger files pause while reading or under battery saver.
- A document changes when its recorded size differs, or when the provider reports a new modified time. The modified
  time is checked once per process and does not open the file.
- Queries split on anything that is not a letter, number or Unicode mark. Only the first few terms are kept; a
  one-character term is dropped when a longer one exists. Terms are quoted for FTS4, then 2+ character terms match
  by prefix. Matching documents are ranked with BM25; excerpts are fetched for the best few after a short debounce.
- Raising `SearchIndexer.INDEX_VERSION` re-indexes everything. A failed search is reported once and shown as no
  content matches.

Limits are in [LIMITATIONS.md](LIMITATIONS.md); switches are in [SETTINGS.md](SETTINGS.md).
