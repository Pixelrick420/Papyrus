# Papyrus

Offline document viewer for Android. Kotlin, Jetpack Compose, Room.

Documents are opened through the Storage Access Framework, indexed in a local Room database and rendered on-device.

## Formats

| Format | Rendered as |
| --- | --- |
| PDF | `PdfRenderer` pages; zoom re-rasterises at the target resolution |
| Markdown | Markwon to `Spanned` in a native `TextView` |
| Plain text (`.txt`, `.log`, ...) | chunked, selectable Compose `Text` |
| Source and config (`.kt`, `.py`, `.json`, ...) | as plain text, labelled `CODE` in the list |
| DOCX / ODT | headings, paragraphs, tables and images, read straight from the archive |

Format resolves from the file extension first and the MIME type second, because SAF providers routinely report a `.md` as
`application/octet-stream` and a `.pdf` as `text/plain`. Extension-less names (`Makefile`, `Dockerfile`, `.gitignore`)
resolve too. Anything left over is sniffed from its first 8 KB: no known container magic, no NUL byte, strict UTF-8 and
overwhelmingly printable means text.

## Features

- **Find in file** works on every format. Text and Office step between chunks, blocks and pages; Markdown highlights
  every match in place, so its counter shows the total.
- **Zoom** scales the type size on text surfaces, so content re-wraps and the scroll bounds stay correct.
- **Index** holds documents and page order in Room, updated incrementally as files are opened.
- **Thumbnails** sit in an in-memory `LruCache`, one representative thumbnail per document, with low-resolution PDF
  placeholders while scrolling.
- **Crash reports** are JSON files in `filesDir/crash-reports/`, written by ACRA.

## Build

Needs JDK 17 and the Android SDK (platform 36, build-tools 36.0.0). Runs on Android 8.0 (API 26) and up.

```bash
./gradlew assembleDebug
./gradlew installLocalDebug     # assemble and install on the connected device
./gradlew installLocalRelease   # minified release build, signed for local install
```

## Test

```bash
./gradlew testDebugUnitTest
```

156 JVM unit tests. The DOCX/ODT extractor runs against archives the tests build in memory (kxml2 stands in for the
platform's `XmlPullParser`), and the Room 1 -> 2 migration runs its real statements against SQLite via JDBC.

## Releases

CI runs the tests on every push to `main`. Pushing a `v*` tag publishes a signed APK to
[Releases](../../releases); the tag must match `versionName` in `app/build.gradle.kts` or the publish fails.
Signing uses a release keystore supplied from repository secrets, never committed to the repo.

## License

[Apache-2.0](LICENSE)
