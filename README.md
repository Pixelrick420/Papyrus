# Papyrus

Offline document viewer for Android built with Kotlin, Jetpack Compose and Room.

Documents open through the Storage Access Framework, get indexed in a local Room database, and render entirely
on-device. The manifest declares no network or storage permissions; the only two it asks for,
`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, let read-aloud keep speaking with the screen off.

## Formats

| Format | Rendered as |
| --- | --- |
| PDF | `PdfRenderer` pages; zoom re-rasterises at the target resolution. Tappable links: a web link opens, an in-document jump scrolls to its page. A PDF with bookmarks gets an **Outline** in the viewer menu, and every PDF a **Go to page**. Password-protected files prompt for the password |
| Markdown | Markwon to `Spanned` in a native `TextView`, with strikethrough and tables, links open on tap |
| Plain text (`.txt`, `.log`, ...) | chunked 40 lines at a time, selectable monospace Compose `Text`. Decoded straight into the chunks, so a file costs about its own size in memory. Read up to 32 MB (Markdown: 4 MB). Optional line-number gutter and no-wrap mode with sideways scrolling |
| Source and config (`.kt`, `.py`, `.json`, ...) | as plain text, labelled `CODE` in the list, with syntax colour for the languages the app defines (below) |
| DOCX / ODT | headings, paragraphs, bold / italic / underline / strikethrough / super- and subscript, text and highlight colours, links, bulleted and numbered lists (nested, with Word's and LibreOffice's numbering schemes), alignment, indentation, footnotes and endnotes, reviewer comments (marked in the text and listed after the body with author and date), equations (DOCX; written out as text such as `(a+b)/c`, `x²`, `√(x+1)`, not typeset), tables (with merged cells, shading and cell formatting) and images (at their stated size, with alt text), read straight from the archive. Macro-enabled files and templates (`.docm`, `.dotx`, `.dotm`, `.ott`) open like their plain counterparts; no macro is ever run. Documents with headings get an **Outline** in the viewer menu that jumps to a heading. Password-protected files prompt for the password |
| DOC (Word 97-2003) | paragraphs and tables, read from the OLE2 piece table. No images; headings come out as plain paragraphs. Encrypted and Word 6/95 files are rejected |

Format resolves from the file extension first and the MIME type second, because SAF providers routinely report a `.md`
as `application/octet-stream` and a `.pdf` as `text/plain`. Extension-less names (`Makefile`, `Dockerfile`,
`.gitignore`) resolve too. Anything left over is sniffed from its first 8 KB: no known container magic, no NUL byte
(unless a UTF-16 or UTF-32 byte-order mark vouches for it), and overwhelmingly printable means text, in UTF-8 or any
legacy 8-bit encoding. A Windows-1252 `.csv` from Excel is the case that matters.

### Text encodings

The encoding of a text, code or Markdown file is chosen from a 64 KB sample, in this order: a byte-order mark (UTF-8,
UTF-16, UTF-32), UTF-16 without one (zero bytes piling up on one side of the byte pairs), strict UTF-8, the app's own
statistical detector for anything that is not UTF-8 (Shift_JIS, KOI8-R, Windows-1251 and so on; it scores each
candidate charset by how much its reading looks like prose, and only overrides the default when a non-Latin
reading clearly wins), and finally Windows-1252, which decodes every byte. A Latin-1 guess becomes Windows-1252, its
superset. The viewer menu's *Text encoding* re-reads the file with a charset picked by hand, or goes back to
auto-detect; the pick is kept for the life of the viewer, including across process death, and is not stored with the
document.

### Syntax highlighting

The tokenizer is the app's own, with no dependency: one scanner driven by per-language data (`CodeLanguages.kt`) for
Kotlin, Java, C and C++, C#, JavaScript, TypeScript, Python, Ruby, Go, Rust, Swift, Dart, PHP, Lua, Groovy and Gradle,
Scala, shell, PowerShell, SQL, GraphQL, R, JSON, YAML, TOML, INI and `.properties`, HTML, XML and SVG, CSS, SCSS and
Less, Dockerfile, make and CMake. The language comes from the file name only. It is a colourer, not a parser: comments,
strings, numbers, keywords, types, calls, annotations and variables, and no more. Chunks are coloured on demand as they
scroll into view, and a single pass over the file on load records where each 40-line chunk begins (inside a block
comment or a triple-quoted string, say), so a construct that crosses a chunk boundary still colours as one. A chunk
over 20,000 characters is shown plain.

## Features

The index is one Room row per document: URI, title, format, size, and the added and last-opened timestamps. Page count
and MIME type are read live from the provider and the open PDF instead of being stored. Schema version 4 is the oldest
supported one: earlier versions were test-only and are not migrated. The number was not reset, because installed
databases are already at 4 and Room refuses a downgrade. Later versions add a `Migration` in `data/Migrations.kt`
and are never replaced by a destructive fallback, since a row owns a file under `filesDir/imports` that wiping it
would orphan. Room exports each version's schema to `app/schemas/`, which is committed, and a unit test fails if the
current version's schema is missing or the migrations leave a gap.

The current version is 5. Its one additive step from 4 is plain SQL, not an `AutoMigration`, because the version-4
schema file was never exported and an `AutoMigration` cannot be generated without it. It adds a `reading_progress`
table (one row per document), a `bookmarks` table and a `documents.isFavourite` column defaulting to 0. Both new tables
reference `documents(id)` with `ON DELETE CASCADE`, so removing a document removes what hangs off it. `documents` is
never written with `OnConflictStrategy.REPLACE`: a replace is a delete plus an insert and would cascade that away.

- **Open with** takes an `ACTION_VIEW` content URI from another app and shows it without indexing it. It opens in its
  own window rather than the caller's, so the other app stays usable behind it; back returns to that app and drops the
  document from Recents. The document lives in memory for the life of the process, since the read grant that arrived
  with the intent does.
- **Save to library** copies such a document into app-private storage and indexes the copy, so it outlives the grant.
  Owned copies share out through a FileProvider instead of a bare `file://` URI. Saving the same file again reuses the
  existing copy when the name and bytes match, instead of adding another.
- **Password-protected documents** prompt for the password, then decrypt a copy into the cache and render that. Word
  (`.docx`, ECMA-376 Agile encryption) and OpenDocument (`.odt`, ODF 1.2 AES-256-CBC) files are supported; newer ODF
  1.3 packages (AES-GCM with Argon2id) and the legacy binary schemes are reported as protected but not opened. A wrong
  password retries in place; the decrypted copy is deleted when the viewer closes and swept on the next start. By
  default the file is not indexed or listed on Home, and the password is never stored or logged. Save to library in the
  viewer menu, or the autosave switch below, keeps the still-encrypted file; opening it from Home asks for the password
  again.
  A PDF is first scanned byte-for-byte for the `/Encrypt` key, so an ordinary PDF is recognised as unprotected without
  the whole-file parse that only a protected one needs. The scan covers the whole file because the key can sit near the
  start (linearized PDFs) or inside the cross-reference stream's dictionary (PDF 1.5) as well as at the end. If the
  renderer still asks for a password after the scan found none, the full check runs and the usual prompt appears.
- **Settings** is the gear on Home's title bar:
  - *Theme*: system default, light or dark. It applies to the whole app, status and navigation bar icons included.
  - *Syntax highlighting*, *Line numbers* and *Wrap long lines*: how plain text, logs and code are shown. Highlighting
    and wrapping are on by default, line numbers off. With wrapping off each line stays on one row and the whole list
    scrolls sideways together; its width is the file's widest line, capped at 60,000 px, past which a line is clipped.
    Line numbers are drawn beside the text from its layout, so a wrapped line still has one number and the numbers are
    never selected or copied.
  - *Autosave external documents*: a document opened from another app is saved to the library as soon as it has
    opened. Off by default.
  - *Autosave encrypted documents*: the same for password-protected documents, saved once they unlock, including ones
    picked from Home. Off by default; while it is off the viewer menu offers Save to library for them instead.
  - *Remember reading position*: library documents reopen where you stopped. On by
    default.
  - *About*: version, source link and license.
- **Search** filters the library by title, in memory, over the list the screen already holds.
- **Share** sends the document's own SAF URI through `ACTION_SEND` with `FLAG_GRANT_READ_URI_PERMISSION`, so no target
  can write, rename or delete the file. The grant is probed first: a dead one raises a message instead of a chooser
  that opens and then fails inside someone else's app.
- **Remove** deletes the row and releases the persisted SAF grant. An app-owned copy goes with its row; your own file
  on disk is untouched. A single row's Remove first only hides it and shows an **Undo** snackbar; the grant, the row and
  the owned copy are touched when that window ends (six seconds), when another removal replaces it, or when Home is
  closed. Undo puts the row back exactly as it was. If the app is killed inside the window the document is simply still
  in the library.
- **Bulk select** starts on a long-press. The header shows the picked count and a select-all toggle, and the bottom bar
  shares the whole selection in one `ACTION_SEND_MULTIPLE` chooser or removes it after a confirmation, immediately
  and without an undo.
- **Find in file**, also on Ctrl+F, works on every format. PDF, text and Office step through matches one at a time:
  the current one is orange and the rest yellow. Markdown is a single `Spanned`, so it lights every match at once and
  its counter shows the total.
- **PDF outline and go to page.** A PDF's bookmarks are read once, off the main thread and after the first page is
  showing, so a large outline never delays it; the viewer menu offers **Outline** only once a non-empty one is ready. It
  is a nested tree with indentation and a fold control per parent (a long outline starts folded to its top level), and a
  bookmark jumps to its page, taken from its own destination, a GoTo action, a page number, or a named destination
  (the catalog's name tree and its older `/Dests` dictionary). A bookmark that goes to no page is left out unless
  something under it does, in which case it stays as a heading that cannot be chosen; one with a blank title is left
  out and its children keep their place. The outline is author-supplied, so the reader assumes nothing: each bookmark
  is visited once (a loop ends), at most 5000 are read and 32 levels descended, and a bookmark PdfBox cannot read costs
  only itself. Every page is indexed once rather than searched for per bookmark. A PDF over 64 MB gets no outline,
  because PdfBox would hold a second copy of it in memory next to the renderer's. **Go to page** (viewer menu or
  Ctrl+G) asks for a number from 1 to the page count, shows "of N" beside the field, flags anything else inline, and
  Go on the keyboard confirms. Both jump with the page's top at the top of the screen.
- **Reading position** reopens a library document where the reader stopped, at the zoom they had. The place is an
  anchor that does not depend on the viewport width, the zoom or the text size, so it survives a rotation, a pinch, an
  encoding change and a re-wrap: a PDF page plus the fraction down it, a 40-line text chunk plus a fraction, an Office
  block (DOCX, ODT, DOC) plus a fraction, and for Markdown a character offset into the rendered text. A stored place is
  clamped against what actually loaded, because a file can change between opens, so a bad one can only land on the
  nearest real place.
  - It is stored in its own `reading_progress` table, as numbers only: the anchor, the committed zoom, the derived
    progress (0 to 1) and a timestamp. A save never touches `documents`, so Home's order (`lastOpenedAt`) and its list
    do not change, and an unlocked password-protected document leaves no readable fragment in the database.
  - Nothing is saved before the restore has been applied, and the first position seen afterwards is a starting point,
    not a save: opening a document and leaving without scrolling keeps the previous position. After that a position is
    written once the reader has been still for about a second and a half, when the app goes to the background, and when
    the viewer closes. Writes are ordered and never on the main thread.
  - A document handed over by another app is not in the library, so nothing is stored for it. If it is saved to the
    library, its position is kept under the new row from then on.
  - *Remember reading position* in Settings (on by default) turns all of this off: nothing is read or written. What
    was stored stays and applies again when the switch is turned back on.
- **Read aloud** speaks the open document through the platform speech engine, in the format's own reading units: a
  PDF page, a 40-line text chunk, an Office block, or a Markdown paragraph. It starts at the current reading position,
  scrolls each spoken unit into view and tints it, and offers a bar over the text with play/pause, a skip back and
  forward of one unit, a speed of 0.5x, 1x, 1.5x or 2x, and close. Pausing and resuming re-speaks the current unit
  rather than skipping it; a speech-engine failure stops and reports the reason while keeping the place. Playback
  continues with the screen off, from a media-playback foreground service whose notification offers play/pause and
  stop, and a platform `MediaSession` forwards headset and Bluetooth keys. No audio is written to disk and no text
  leaves the device; the speed is held for the session only.
- **Zoom** is a pinch or a double-tap, 0.5x to 3x, the double-tap toggling between 1x and 2.5x. Text surfaces scale the
  type size, so content re-wraps and the scroll bounds stay correct.
- **Links** open on tap with no confirmation, in PDF and Markdown text and in DOCX / ODT — a PDF's in-document
  destination scrolls to that page instead of leaving the app. Only `http`, `https`, `mailto` and `tel` are followed;
  any other scheme is refused, and a link with nothing to open it raises a short toast, leaving the page as it was.
- **Lost access** keeps what is on screen and offers a re-pick. The pick must have exactly the stored file name and the
  same format; size is not compared, so a file edited since it was added is still accepted, and a file whose name only
  starts the same is not.
- **File info** is a bottom sheet: name, format, size, the provider's type, page count for PDFs, both timestamps and
  the content URI.
- **Thumbnails** are one bitmap per document in an in-memory `LruCache`. A tile shows the format label until its
  bitmap arrives, and `No access` once the grant is gone.
- **Crash reports** are JSON files in `filesDir/crash-reports/`, written by ACRA. Nothing leaves the device.

## Build

Needs JDK 17, the Android SDK, and Android 8.0 (API 26) or up.

| Task | What it does |
| --- | --- |
| `./gradlew assembleDebug` | build the debug APK |
| `./gradlew installLocalDebug` | build and install on the connected device |
| `./gradlew installLocalRelease` | minified release build, signed for local install |

`installLocalRelease` signs a throwaway copy with the local debug keystore, because the `release` build type carries no
signing config of its own. The build fails outright if any configuration resolves a Google dependency, so F-Droid
compliance is enforced rather than documented.

## Test

| Task | What it runs |
| --- | --- |
| `./gradlew check` | unit tests, lint and detekt |
| `./gradlew testDebugUnitTest` | unit tests alone |

The DOCX/ODT and DOC extractors run against archives the tests build in memory (kxml2 stands in for the platform's
`XmlPullParser`, and the `.doc` fixtures assemble the OLE2 container and piece table from scratch). detekt is scoped to
dead code and correctness, with the deliberate broad catches baselined so they stay visible. CI runs only the tests and
`assembleRelease`, so lint and detekt are local gates.

## Releases

- Every push to `main` runs the tests and assembles an unsigned release APK, uploaded as a build artifact.
- Pushing a `v*` tag publishes a signed APK to [Releases](../../releases). The tag must match `versionName` in
  `app/build.gradle.kts`, and `versionCode` must exceed the previous release tag's, since that value alone orders the
  upgrades.
- The job fails if the APK comes out unsigned or carries the Android debug certificate.
- Signing uses a release keystore supplied from repository secrets, never committed to the repo. The job stops early
  if any of those secrets is missing rather than publishing an APK no one can upgrade to.

## License

[Apache-2.0](LICENSE)
