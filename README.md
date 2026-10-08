# Papyrus

Offline document viewer for Android. Kotlin, Jetpack Compose, Room.

Documents open through the Storage Access Framework, get indexed in a local Room database, and render entirely
on-device. The manifest declares no permissions: no network access, no storage permission to ask for.

## Formats

| Format | Rendered as |
| --- | --- |
| PDF | `PdfRenderer` pages; zoom re-rasterises at the target resolution. Password-protected files are rejected |
| Markdown | Markwon to `Spanned` in a native `TextView`, with strikethrough and tables |
| Plain text (`.txt`, `.log`, ...) | chunked 40 lines at a time, selectable monospace Compose `Text`. Read up to 4 MB |
| Source and config (`.kt`, `.py`, `.json`, ...) | as plain text, labelled `CODE` in the list |
| DOCX / ODT | headings, paragraphs, tables and images, read straight from the archive |
| DOC (Word 97-2003) | paragraphs and tables, read from the OLE2 piece table. No images; headings come out as plain paragraphs. Encrypted and Word 6/95 files are rejected |

Format resolves from the file extension first and the MIME type second, because SAF providers routinely report a `.md`
as `application/octet-stream` and a `.pdf` as `text/plain`. Extension-less names (`Makefile`, `Dockerfile`,
`.gitignore`) resolve too. Anything left over is sniffed from its first 8 KB: no known container magic, no NUL byte,
strict UTF-8 and overwhelmingly printable means text.

## Features

The index is one Room row per document: URI, title, format, size, and the added and last-opened timestamps. Page count
and MIME type are read live from the provider and the open PDF instead of being stored. There is no destructive
migration fallback, because the index is the reader's document history.

- **Open with** takes an `ACTION_VIEW` content URI from another app and shows it without indexing it. That document
  lives in memory for the life of the process, since the read grant that arrived with the intent does.
- **Save to library** copies such a document into app-private storage and indexes the copy, so it outlives the grant.
  Owned copies share out through a FileProvider instead of a bare `file://` URI.
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
- **Zoom** is a pinch or a double-tap, 0.5x to 5x, the double-tap toggling between 1x and 2.5x. Text surfaces scale the
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
`XmlPullParser`, and the `.doc` fixtures assemble the OLE2 container and piece table from scratch), and every Room
migration runs its own real statements against SQLite via JDBC. detekt is scoped to
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
