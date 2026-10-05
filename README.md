# Papyrus

Offline document viewer for Android. Kotlin, Jetpack Compose, Room.

Documents are opened through the Storage Access Framework, indexed in a local Room database and rendered on-device.
The manifest declares no permissions, so there is no network access and no storage permission to ask for.

## Formats

| Format | Rendered as |
| --- | --- |
| PDF | `PdfRenderer` pages; zoom re-rasterises at the target resolution. Password-protected files are rejected |
| Markdown | Markwon to `Spanned` in a native `TextView`, with strikethrough and tables |
| Plain text (`.txt`, `.log`, ...) | chunked 40 lines at a time, selectable monospace Compose `Text`. Read up to 4 MB |
| Source and config (`.kt`, `.py`, `.json`, ...) | as plain text, labelled `CODE` in the list |
| DOCX / ODT | headings, paragraphs, tables and images, read straight from the archive |

Format resolves from the file extension first and the MIME type second, because SAF providers routinely report a `.md` as
`application/octet-stream` and a `.pdf` as `text/plain`. Extension-less names (`Makefile`, `Dockerfile`, `.gitignore`)
resolve too. Anything left over is sniffed from its first 8 KB: no known container magic, no NUL byte, strict UTF-8 and
overwhelmingly printable means text.

## Features

- **Index** holds one Room row per document: URI, title, format, size, and the added and last-opened timestamps. Page
  count and MIME type are read live from the provider and the open PDF instead of being stored. There is no destructive
  migration fallback, because the index is the reader's document history.
- **Search** filters the library by title, in memory, over the list the screen already holds.
- **Open with** takes an `ACTION_VIEW` content URI from another app and shows it without indexing it. That document
  lives in memory for the life of the process, since the read grant that arrived with the intent does.
- **Share** sends the document's own SAF URI through `ACTION_SEND` with `FLAG_GRANT_READ_URI_PERMISSION`, so no target
  can write, rename or delete the file. The grant is probed first: a dead one raises a message instead of a chooser
  that opens and then fails inside someone else's app.
- **Remove** deletes the row and releases the persisted SAF grant. The file on disk is untouched.
- **Find in file**, also on Ctrl+F, works on every format. PDF, text and Office step through matches one at a time: each
  match counts separately, the current one is orange and the rest yellow, and the view scrolls to it within the page,
  chunk or block holding it. Markdown is a single `Spanned` with no step target, so it lights every match at once and
  its counter shows the total.
- **Zoom** is a pinch or a double-tap, 0.5x to 5x, the double-tap toggling between 1x and 2.5x. Text surfaces scale the
  type size, so content re-wraps and the scroll bounds stay correct.
- **Lost access** keeps what is on screen and offers a re-pick, matched against the title so the wrong file cannot be
  accepted.
- **File info** is a bottom sheet: name, format, size, the provider's type, page count for PDFs, both timestamps and the
  content URI.
- **Thumbnails** are one bitmap per document in an in-memory `LruCache` keyed by id and size. A tile shows the format
  label until its bitmap arrives, and `No access` once the grant is gone. In the PDF viewer a page being flung renders
  at a quarter of its width and is rescaled.
- **Crash reports** are JSON files in `filesDir/crash-reports/`, the newest 20 kept, written by ACRA. Nothing leaves the
  device.

## Build

Needs JDK 17 and the Android SDK (platform 36, build-tools 36.0.0). Runs on Android 8.0 (API 26) and up.

```bash
./gradlew assembleDebug
./gradlew installLocalDebug     # assemble and install on the connected device
./gradlew installLocalRelease   # minified release build, signed for local install
```

`installLocalRelease` signs a throwaway copy with the local debug keystore, because the `release` build type carries no
signing config of its own. The build fails outright if any configuration resolves a Google dependency, so F-Droid
compliance is enforced rather than documented.

## Test

```bash
./gradlew check                  # unit tests, lint and detekt
./gradlew testDebugUnitTest      # unit tests alone
```

221 JVM unit tests. The DOCX/ODT extractor runs against archives the tests build in memory (kxml2 stands in for the
platform's `XmlPullParser`), and each of the three Room migrations, 1 -> 2, 2 -> 3 and 3 -> 4, runs its own real
statements against SQLite via JDBC. detekt is scoped to dead code and correctness, with the deliberate broad catches
baselined so they stay visible. CI runs only the tests and `assembleRelease`, so lint and detekt are local gates.

## Releases

CI runs the tests and assembles a release APK on every push to `main`, uploading the unsigned APK as a build artifact.
Pushing a `v*` tag publishes a signed APK to [Releases](../../releases). The tag must match `versionName` in
`app/build.gradle.kts`, and `versionCode` must exceed the previous release tag's, since that value alone orders the
upgrades. The job also fails if the APK comes out unsigned or carries the Android debug certificate. Signing uses a
release keystore supplied from repository secrets, never committed to the repo; without them `release` stays unsigned.

## License

[Apache-2.0](LICENSE)