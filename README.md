<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="192" alt="Papyrus app icon">
</p>

<h1 align="center">Papyrus</h1>

<p align="center">
  <em>Offline document viewer for Android: PDF, Word, OpenDocument, EPUB, Markdown, text and code.</em><br>
  <sub>No internet permission. No ads, no accounts, no analytics.</sub>
</p>

<p align="center">
  <a href="https://github.com/Pixelrick420/Papyrus/actions/workflows/build.yml"><img src="https://github.com/Pixelrick420/Papyrus/actions/workflows/build.yml/badge.svg?branch=main" alt="Build status"></a>
  <a href="https://github.com/Pixelrick420/Papyrus/blob/main/LICENSE"><img src="https://img.shields.io/github/license/Pixelrick420/Papyrus?color=blue" alt="Apache-2.0"></a>
  <a href="https://github.com/Pixelrick420/Papyrus/releases"><img src="https://img.shields.io/github/v/release/Pixelrick420/Papyrus?sort=semver" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
</p>

<p align="center">
  <a href="#screenshots">Screenshots</a> ·
  <a href="#formats">Formats</a> ·
  <a href="#features">Features</a> ·
  <a href="#install">Install</a> ·
  <a href="#build">Build</a> ·
  <a href="#architecture">Architecture</a> ·
  <a href="#license">License</a>
</p>

## Screenshots

<table>
  <tr>
    <td align="center" valign="top">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/home.png" width="190" alt="The Papyrus library screen"><br>
      <sub><b>Library</b><br>search, sort &amp; filter, starred files</sub>
    </td>
    <td align="center" valign="top">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/filter.png" width="190" alt="The sort and filter sheet"><br>
      <sub><b>Sort &amp; filter</b><br>one sheet, a live match count</sub>
    </td>
    <td align="center" valign="top">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/pdf.png" width="190" alt="A PDF open in the viewer"><br>
      <sub><b>PDF</b><br>zoom, links, outline, go to page</sub>
    </td>
    <td align="center" valign="top">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/md.png" width="190" alt="A Markdown document open in the viewer"><br>
      <sub><b>Markdown</b><br>tables, task lists, maths, code</sub>
    </td>
  </tr>
  <tr>
    <td align="center" valign="top">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/find.png" width="190" loading="lazy" alt="Find in file"><br>
      <sub><b>Find in file</b><br>every format, Ctrl+F</sub>
    </td>
    <td align="center" valign="top">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/tts.png" width="190" loading="lazy" alt="Read aloud"><br>
      <sub><b>Read aloud</b><br>sentence and word highlighting</sub>
    </td>
    <td align="center" valign="top">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/info.png" width="190" loading="lazy" alt="The file info sheet"><br>
      <sub><b>File info</b><br>name, format, size, URI</sub>
    </td>
  </tr>
</table>

## Why Papyrus

Offline document viewer for Android built with Kotlin, Jetpack Compose and Room.

Documents open through the Storage Access Framework, get indexed in a local Room database, and render entirely
on-device. The manifest declares no network or storage permissions; the only two it asks for,
`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, let read-aloud keep speaking with the screen off.

## Formats

| Format                | What you get                                                                              |
| --------------------- | ----------------------------------------------------------------------------------------- |
| **PDF**               | Zoom, tappable links, PDF bookmarks as an **Outline**, **Go to page**, password unlock    |
| **DOCX / ODT**        | Headings, lists, tables, images, footnotes, reviewer comments, DOCX equations as text     |
| **DOC**               | Paragraphs and tables                                                                     |
| **EPUB**              | A reflowed book, its own table of contents as the **Outline**, its cover as the thumbnail |
| **Markdown**          | Tables, task lists, maths, autolinks, coloured code fences, `data:` URI images            |
| **Text / Logs**       | 40-line chunks, selectable text, optional line numbers, no-wrap mode                      |
| **Code / Config**     | The same text pipeline, with syntax colour for 30+ languages                              |
| **CSV  / TSV**        | A header row in bold, wrapping cells, sideways scroll for wide tables                     |

The format comes from the file extension first, then the MIME type, then an 8 KB sniff. Text encodings are detected
automatically and can be changed from the viewer menu.

## Features

### Library

- **Search** filters the list by title as you type.
- **Sort & filter** is the slider icon beside the search pill. One sheet holds every sort order, every file-type
  filter and the starred filter, with a badge counting what is on and a live count of matching files.
- **Sort** by last opened, name (A to Z or Z to A), date added or size. Names sort the way a person reads them, so
  `file2` comes before `file10`, and the choice is remembered.
- **File-type filters** for PDF, Word, ODT, EPUB, Markdown, text, CSV, TSV and code, plus starred files. They combine
  with search and sorting, survive rotation, and a **Clear filters** button appears when nothing matches.
- **Star** a file from its row menu. Reopening it or re-granting access keeps the star.
- **Thumbnails** for every document, with the format label until one is ready.
- **Bulk select** on a long-press, then share the whole selection or remove it after a confirmation.
- **Share** sends a read-only grant, so the receiving app cannot write, rename or delete the file.
- **Remove** with a six-second **Undo**. Your own file on disk is never touched.
- **File info**: name, format, size, provider type, page count for PDFs, both timestamps and the content URI.

### Reading

- **Find in file** (Ctrl+F) in every format, with the current match highlighted.
- **PDF outline and go to page** (Ctrl+G). The PDF's own bookmarks become a foldable **Outline**, and a long one starts
  folded.
- **Bookmarks** (Ctrl+D) in every format: named or positional, rename and delete with Undo, at most 200 per document.
- **Reading position** reopens a document where you stopped, at the same zoom, even after a rotation. A switch in
  Settings turns it off.
- **Read aloud** speaks the page, chunk, block or paragraph, shades the sentence being read (and the word, where the
  engine reports timing), and keeps going with the screen off. Play/pause, skip, lock, speed and close are on the bar.
- **Zoom** with a pinch or a double-tap, from 0.5x to 3x.
- **Links** open on tap for `http`, `https`, `mailto` and `tel`. A PDF's internal link scrolls to its page.

### Opening documents

- **Open with** shows a document from another app without adding it to the library.
- **Save to library** keeps a copy, so it outlives the other app's grant.
- **Password-protected documents** unlock locally: Word, OpenDocument and PDF. The password is never stored or logged,
  and the temporary copy of a Word or OpenDocument file is deleted when you close it.
- **Lost access** keeps the page on screen and offers to re-pick the file, matching on name and format.

### Settings

- **Theme** follows the system, or stays light or dark. It is applied before the first frame, so the app opens in the
  right scheme instead of flashing the other one.
- **Colorblind mode** swaps the theme colours for a colour-blind safe palette of blue, orange and teal.
- **Night mode** inverts PDF pages, so white paper reads black.
- **Syntax highlighting** colours comments, strings, keywords and numbers in source and config files.
- **Line numbers** adds a gutter beside plain text, logs and code.
- **Wrap long lines** wraps at the screen edge. Off, each line stays on one row and the view scrolls sideways.
- **Pretty print structured files** indents JSON, XML, HTML and SVG as they open, at the chosen tab size. The stored
  file is never changed.
- **Tab size** sets the indent for pretty printing: 2, 3, 4 or 8 spaces.
- **Autosave external docs** adds a document another app hands over to the library as it opens. Off, the viewer's
  **Save to library** keeps a copy instead.
- **Autosave encrypted docs** does the same for password-protected PDF, Word and OpenDocument files. They stay
  encrypted and the password is never stored.
- **Remember reading position** reopens a library document where you stopped and shows progress on Home. Off, no
  position is read or written, and the stored ones apply again when it goes back on.
- **About** shows the version and links to release notes, source code, the Apache 2.0 license and the privacy policy.

### Privacy & safety

- **EPUB safety.** A book is a ZIP from someone else, so it is read with hard limits and never extracted to disk beyond
  its images. A zip-slip entry, an oversized archive or a decompression bomb fails the book, not the app, and DRM is
  refused.
- **Crash reports** are JSON files in app storage, written by ACRA. Nothing leaves the device.

## Install

1. Download the signed APK from the [latest release](https://github.com/Pixelrick420/Papyrus/releases/latest).
2. Install it; Android may ask you to allow installs from your browser or file manager.
3. Open a document from Papyrus, or tap one in another app and choose Papyrus.

Needs Android 8.0 (API 26) or newer. No Play Services, no network permission, no account. To build it yourself, see
[Build](#build).

## Build

Needs JDK 17, the Android SDK, and Android 8.0 (API 26) or up.

| Task                            | What it does                                     |
| ------------------------------- | ------------------------------------------------ |
| `./gradlew assembleDebug`       | build the debug APK                              |
| `./gradlew installLocalDebug`   | build and install on the connected device        |
| `./gradlew installLocalRelease` | minified release build, signed for local install |

`installLocalRelease` signs a throwaway copy with the local debug keystore, because the `release` build type carries no
signing config of its own. The build fails outright if any configuration resolves a Google dependency, so F-Droid
compliance is enforced rather than documented.

## Test

| Task                          | What it runs                |
| ----------------------------- | --------------------------- |
| `./gradlew check`             | unit tests, lint and detekt |
| `./gradlew testDebugUnitTest` | unit tests alone            |

The DOCX/ODT, EPUB and DOC extractors run against archives the tests build in memory (kxml2 stands in for the platform's
`XmlPullParser`, and the `.doc` fixtures assemble the OLE2 container and piece table from scratch). detekt is scoped to
dead code and correctness, with the deliberate broad catches baselined so they stay visible. CI runs only the tests and
`assembleRelease`, so lint and detekt are local gates.

## Architecture

Papyrus gets a document one of two ways: you pick it in the system file picker, or another app hands one over. Either
way it works out the format and opens the viewer for it. A picked file also joins the library, with its reading
position and bookmarks. A document handed over by another app is not added to the library and lives only while Papyrus
runs. Read aloud uses the text the viewer already extracted.

```mermaid
flowchart LR
  A["Another app<br/>hands over a document"] --> C{"Which format?<br/>extension, MIME type, then the first 8 KB"}
  B["File picker"] --> C
  C --> D["Library index<br/>one row per document, plus positions and bookmarks"]
  D --> E["Viewer"]
  E --> F["PDF"]
  E --> G["Markdown"]
  E --> H["Office<br/>DOCX, ODT, DOC, EPUB, CSV"]
  E --> I["Text and code<br/>40 lines at a time"]
  E --> J["Read aloud<br/>system speech engine"]
```

### Data and schema

The index is one Room row per document: URI, title, format, size, and the added and last-opened timestamps. Page count
and MIME type are read live, so they are not stored.

The current schema is 5. Version 4 is the oldest supported one, since earlier versions were test-only. Version 5 adds a
`reading_progress` table with one row per document, a `bookmarks` table, and `documents.isFavourite`. Both new tables
reference `documents(id)` with `ON DELETE CASCADE`, so deleting a document removes them. Rows are never written with
`OnConflictStrategy.REPLACE`, because that deletes and reinserts, which would cascade the children away. Migrations are
plain SQL in `data/Migrations.kt`, and every version's schema is exported to `app/schemas/` with a test that fails if
one is missing.

```mermaid
erDiagram
    DOCUMENTS ||--o| READING_PROGRESS : "keeps one position"
    DOCUMENTS ||--o{ BOOKMARKS : "holds"

    DOCUMENTS {
        INTEGER id PK
        TEXT uri UK
        TEXT title
        TEXT format
        INTEGER sizeBytes
        INTEGER createdAt
        INTEGER lastOpenedAt
        INTEGER isFavourite
    }

    READING_PROGRESS {
        INTEGER documentId PK, FK
        INTEGER locationKind
        INTEGER locationUnit
        REAL locationFraction
        INTEGER unitCount
        REAL zoomScale
        REAL progress
        INTEGER updatedAt
    }

    BOOKMARKS {
        INTEGER id PK
        INTEGER documentId FK
        INTEGER locationKind
        INTEGER locationUnit
        REAL locationFraction
        TEXT label
        INTEGER createdAt
    }
```

## Known limitations

- DOC files carry paragraphs and tables only: no images, and headings arrive as plain paragraphs. Encrypted and Word
  6/95 files are rejected.
- Fixed-layout EPUB books reflow as plain text; there is no EPUB-specific typography, right-to-left layout is not
  handled, and links between chapters stay plain text.
- Password-protected documents open when they are a PDF, a `.docx` (ECMA-376 Agile) or an `.odt` (ODF 1.2
  AES-256-CBC). Newer ODF 1.3 packages and the legacy binary schemes are reported as protected but not opened.
- A PDF over 64 MB gets no outline, because PdfBox would hold a second copy of it in memory next to the renderer's.
- Markdown shows an image only when the document carries it as a `data:` URI; the app never touches the network, so
  every other image falls back to its alt text.
- CSV and TSV tables are not read aloud.
- Size and shape caps: Markdown up to 6 MB, plain text up to 32 MB, a table up to 8 MB and 200,000 cells. A table that
  hits a cap ends with the viewer's incomplete-document notice instead of failing.

## Contributing

Issues and pull requests are welcome. Run `./gradlew check` before sending one: it runs the unit tests, lint and
detekt, and lint and detekt are not part of CI. The screenshots this README shows live in
`fastlane/metadata/android/en-US/images/`, so the store listing and the README stay in step when you update them.

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
