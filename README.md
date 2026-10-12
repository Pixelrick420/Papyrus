# Papyrus

Offline document viewer for Android built with Kotlin, Jetpack Compose and Room.

Documents open through the Storage Access Framework, get indexed in a local Room database, and render entirely
on-device. The manifest declares no permissions: no network or storage access

## Formats

| Format | Rendered as |
| --- | --- |
| PDF | `PdfRenderer` pages; zoom re-rasterises at the target resolution. Password-protected files prompt for the password |
| Markdown | Markwon to `Spanned` in a native `TextView`, with strikethrough and tables |
| Plain text (`.txt`, `.log`, ...) | chunked 40 lines at a time, selectable monospace Compose `Text`. Decoded straight into the chunks, so a file costs about its own size in memory. Read up to 32 MB (Markdown: 4 MB). Optional line-number gutter and no-wrap mode with sideways scrolling |
| Source and config (`.kt`, `.py`, `.json`, ...) | as plain text, labelled `CODE` in the list, with syntax colour for the languages the app defines (below) |
| DOCX / ODT | headings, paragraphs, bold / italic / underline / strikethrough / super- and subscript, text and highlight colours, links, bulleted and numbered lists (nested, with Word's and LibreOffice's numbering schemes), alignment, indentation, footnotes and endnotes, tables (with merged cells, shading and cell formatting) and images (at their stated size, with alt text), read straight from the archive. Password-protected files prompt for the password |
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
and MIME type are read live from the provider and the open PDF instead of being stored. Only the current schema is
supported: there is no migration path from older versions, and a database written by one will not open.

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
  - *About*: version, source link and license.
- **Search** filters the library by title, in memory, over the list the screen already holds.
- **Share** sends the document's own SAF URI through `ACTION_SEND` with `FLAG_GRANT_READ_URI_PERMISSION`, so no target
  can write, rename or delete the file. The grant is probed first: a dead one raises a message instead of a chooser
  that opens and then fails inside someone else's app.
- **Remove** deletes the row and releases the persisted SAF grant. An app-owned copy goes with its row; your own file
  on disk is untouched.
- **Bulk select** starts on a long-press. The header shows the picked count and a select-all toggle, and the bottom bar
  shares the whole selection in one `ACTION_SEND_MULTIPLE` chooser or removes it after a confirmation. A single row's
  ⋮ Remove still happens straight away.
- **Find in file**, also on Ctrl+F, works on every format. PDF, text and Office step through matches one at a time:
  the current one is orange and the rest yellow. Markdown is a single `Spanned`, so it lights every match at once and
  its counter shows the total.
- **Zoom** is a pinch or a double-tap, 0.5x to 3x, the double-tap toggling between 1x and 2.5x. Text surfaces scale the
  type size, so content re-wraps and the scroll bounds stay correct.
- **Lost access** keeps what is on screen and offers a re-pick, matched against the title so the wrong file cannot be
  accepted.
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
