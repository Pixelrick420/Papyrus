# Architecture

Papyrus is Kotlin, Jetpack Compose and Room.

## Flow

- Sources: file picker (normally joins the library; an encrypted file with **Autosave encrypted docs** off or a URI
  whose grant cannot be persisted is handed over transiently), another app's handover (joins only with the matching
  autosave setting: **Autosave encrypted docs** for protected files, **Autosave external docs** otherwise) and share
  target (one `content://` file only).
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
delete and reinsert them and cascade the children away. Page count and MIME type are read live. Version 2 adds
`pdf_outlines.sourceModified`; version 3 adds `document_text.failureCount` for indexed-text retry backoff. Both migrate
a schema-1 database in place through those two steps. Version 2 shipped in 2.1.1. Schemas are exported to `app/schemas/`.

The installable database file keeps the historical name `papyrus-v1.db`, even though the app version is no longer 1.x;
renaming it would orphan existing libraries. The pre-1.0 `papyrus.db` is deleted at startup because 1.0 changed the
application ID. Room has no downgrade migration: installing an older build over a newer database is unsupported, and
an uninstall is the supported way to clear local data.

## Cache layout

All paths are under `cacheDir`, private and disposable. Startup sweeping runs off the main thread; only creation of an
unlocked copy waits for its directory sweep. Age cut-offs are measured from the current process start.

| Path | Written by | Cleaned by |
|---|---|---|
| `pdf-unlock/` | PDF password unlocking | `MainApplication.sweepUnlocked` at startup; `UnlockedCopy` on replace/discard |
| `office-unlock/` | Office password unlocking | `MainApplication.sweepUnlocked` at startup; `UnlockedCopy` on replace/discard |
| `pdf-view/` | `PdfPageSource` copies of non-seekable PDF descriptors | startup age sweep; `PdfPageSource.close` |
| `office-media/` | viewer image extraction, in `<document id>/<open id>/` | `ViewerViewModel.onCleared` deletes that open's subtree; startup age sweep clears leftovers |
| `pptx-<uuid>/` | `SlidePageSource` media extraction | `SlidePageSource.close`; startup age sweep |

`pdf-view/` holds the `view-*.pdf` copies written for non-seekable PDF descriptors; older copies at the cache root
are no longer written.

## Content search

- Indexer uses `document_text` as its queue; states are `INDEXED`, `FAILED`, `SKIPPED` and `TOO_LARGE`.
- A `FAILED` row is eligible again at its `indexedAt` deadline (5 minutes, then 30 minutes, 2 hours and 24 hours), but
  there is no timer: it is retried only on the next indexing pass. After the last failure it becomes `SKIPPED`, as are
  `TOO_LARGE` and DRM-protected files.
- At or below `SearchIndexer.EAGER_MAX_BYTES`, text is read at once, even while a document is open or battery saver is
  on. The **Search inside documents** switch still applies. Larger files pause while reading or under battery saver.
- Opening a library document refreshes its recorded size and modified time from the provider, so an external edit is
  detected on the next indexing pass. A document changes when its recorded size differs (a size of 0 means the provider
  did not report one), or when the provider reports a different modified time for a row that already has one recorded
  (`sourceModified != 0`); rows without a recorded time are left alone. The indexer checks
  the modified time via `textCacheModified`, which queries the provider for metadata only; it does not read the
  document bytes.
- Queries split on anything that is not a letter, number or Unicode mark. Only the first few terms are kept; a
  one-character term is dropped when a longer one exists. Terms are quoted for FTS4, then 2+ character terms match
  by prefix. Matching documents are ranked with BM25; excerpts are fetched for the best few after a short debounce.
- Raising `SearchIndexer.INDEX_VERSION` re-indexes everything. A failed search is reported once and shown as no
  content matches.

Limits are in [LIMITATIONS.md](LIMITATIONS.md); switches are in [SETTINGS.md](SETTINGS.md).
