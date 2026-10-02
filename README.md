# Papyrus (offline document scanner & viewer)

Kotlin + Jetpack Compose. F-Droid-compatible: no GMS, Firebase, ML Kit, Play libraries, no INTERNET permission.

* Scanner: CameraX -> OpenCV (Canny -> largest quad -> `warpPerspective`, adaptive threshold) -> PdfBox-Android
* Viewer: `PdfRenderer` (mutex-serialised, LruCache, low-res placeholders while scrolling), Markwon, Compose `Text`,
  dependency-free DOCX/ODT text extraction
* Storage: SAF only (`OPEN_DOCUMENT`, `CREATE_DOCUMENT`, persisted grants); Room index
* Crash reports: ACRA with a local-file sender (`filesDir/crash-reports/*.json`), nothing is transmitted

## Formats

The format is chosen from the file extension first and the MIME type second, because SAF providers
routinely report a `.md` as `application/octet-stream` and a `.pdf` as `text/plain`. A file whose name *is* the format
(`Makefile`, `Dockerfile`, `.gitignore`) resolves too. Anything unrecognised is sniffed: no known container magic, no NUL
byte, strict UTF-8 and overwhelmingly printable means "text", and only the first 8 KB are read so a large log is not
scanned whole to render its first line.

| Format | Rendered as |
| --- | --- |
| PDF | PdfRenderer pages, zoom re-rasterises rather than magnifying pixels |
| Markdown | Markwon -> `Spanned` in a native `TextView`; no WebView |
| Plain text (`.txt`, `.log`, …) | chunked, selectable, monospace-ish Compose `Text` |
| Source and config (`.kt`, `.py`, `.json`, …) | identical to plain text, labelled `CODE` in the list |
| DOCX / ODT | headings, paragraphs, tables and images, extracted from the archive directly |

Find-in-file works on all of them. Text and Office step between chunks, blocks and (for PDF) pages; Markdown highlights
every match in place, so its counter shows the total and the step arrows are disabled rather than pretending to navigate.
Zoom scales the *type* on text surfaces, so the content re-wraps and the scroll bounds stay correct.

## Tests

    ./gradlew testDebugUnitTest

131 JVM unit tests, no device or emulator needed: the DOCX/ODT extractor runs against archives the tests build in memory
(kxml2 stands in for the platform's `XmlPullParser`), the Room 1 -> 2 migration runs its real statements against SQLite
via JDBC, and format detection, text sniffing and find-in-file hit computation are covered directly. There are no
instrumented tests.

## Build
Requires JDK 17 and the Android SDK (platform 36).

    gradle wrapper --gradle-version 8.13   # only if gradlew/gradle-wrapper.jar are missing
    ./gradlew assembleDebug

`app/build.gradle.kts` fails dependency resolution if any `com.google.android.gms`, `com.google.firebase`,
`com.google.mlkit` or `com.google.android.play` artifact appears, even transitively.

## Notes
* Room is at schema `version = 2`. `MIGRATION_1_2` rebuilds the `documents` table (create/copy/drop/rename/reindex)
  instead of `ALTER TABLE ... DROP COLUMN`, because minSdk 26 ships a SQLite older than 3.35. The database has no
  destructive fallback, so each future version bump needs its own migration.
* Office images stream to `cacheDir/office-media/` under a total and per-image byte budget plus an image-count cap,
  and are cleared when the viewer closes. A dangling relationship, an oversized image or a budget rejection is
  skipped, and the document's text still renders.
* `ThumbnailLoader` is an in-memory `LruCache` keyed `"$documentId@$sizeBytes"`, holding one representative
  thumbnail per document. A PDF thumbnail renders page 0 and closes the `PdfRenderer` immediately.
* Core library desugaring is enabled as planned, but it does not provide `java.awt`/`javax.xml` APIs, so Apache POI
  would still not run on Android. DOCX/ODT therefore use `ZipInputStream` + `XmlPullParser` (Option A, minus POI/ODFDOM).
* The scanner route is still registered in `NavGraph`, but nothing in the UI navigates to it. Deleting the route is a
  one-line change once a non-Home entry point is no longer needed.
* Rowspans are extracted correctly but render as one bordered cell followed by whitespace: a `Column` of `Row`s cannot
  overlap children, so growing the origin's box downward needs a custom `SubcomposeLayout` that measures the covered rows
  first. Covered columns draw no box, so a merged cell never has a border through its middle.
* Before a Play release, check native-lib 16 KB alignment of the OpenCV AAR (`check_elf_alignment.sh`).
* This scaffold has not been compiled in CI; first sync may need small version bumps.
