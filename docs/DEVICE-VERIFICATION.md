# Device verification ledger

Plan 13 separates checks needing real Android from CI checks. Device checks stay open until run on hardware.

## Device checks (D1–D11)

| ID | Question | Drives | Status |
|---|---|---|---|
| D1 | Does an over-long TTS unit fail, and by callback, return code or both? | plan 02 | **NOT RUN** |
| D2 | How long does the skipped-entry ZIP guard take, and is a 2 GiB budget acceptable? | plan 01 | **NOT RUN** |
| D3 | Do multi-MB single lines and very large files stall layout or scrolling? | plans 03, 09 | **NOT RUN** |
| D4 | Does Android SQLite tokenise Indic and Arabic marks like sandbox SQLite? | plan 04 | **NOT RUN** |
| D5 | Which providers need the PDF copy fallback, and can they be told from corrupt files? | plan 06 | **NOT RUN** |
| D6 | Is a many-piece `.doc` slow enough to matter on a cloud provider? | plan 08 | **NOT RUN** |
| D7 | What is the memory behavior of a large deck on a 2–3 GB device? | plan 09 | **NOT RUN** |
| D8 | Is autosave of a 300 MB external file noticeable? | plan 05 | **NOT RUN** |
| D9 | How costly is a library of unreadable files and DOCX thumbnails? | plan 05 | **NOT RUN** |
| D10 | Does closing one viewer window delete another window's `office-media`? | plan 06 | **NOT RUN** |
| D11 | Does `StrictMode` in a plain debug build (no instrumentation) see a main-thread cross-process `ContentResolver` call? | plans 03, 12 | **NOT RUN** |

Procedures are in `13-device-verification-and-unreviewed-areas.md` under "Verification backlog". None can run here: no
device or emulator is attached. `MainThreadStallRule` and `SlowContentProvider` are ready for D3 and D10. D11 needs a
plain debug build: instrumented runs skip `StrictMode` because the harness triggers violations and its logs distort
frame timing; `MainThreadStallRule` is the instrumented main-thread guard. Record device, Android version, TTS engine,
build, result and a short `logcat` excerpt. Do not mark a check done from code inspection alone.

## CI-side checks (C1–C3)

| ID | Question | Status |
|---|---|---|
| C1 | Does `UnsafeCallOnNullableType` run under `check`? | **PASS** |
| C2 | Does `setup-gradle@v5` validate the Gradle wrapper? | **PASS** |
| C3 | Do Compose compiler reports show unstable large screens? | **PASS (informational)** |

- **C1:** `./gradlew detektDebug --offline` passed with 0 findings. A temporary `value!!` failed once; the probe was
  removed. `check` depends on `detektDebug`, so new main-code `!!` fails CI. The seven sites are gone; test code is
  outside the gate and the baseline is empty.
- **C2:** pinned `gradle/actions/setup-gradle@0723195856401067f7a2779048b490ace7a47d7c` (v5.0.2) validates the wrapper
  by default; no separate wrapper-validation step is needed.
- **C3:** reports go to `app/build/compose-reports`. Large screens are `restartable skippable` but take unstable
  parameters: `HomeScreen` (1), `SettingsScreen` (1), `ViewerScreen` (1), `PdfViewer` (8), `OfficeViewer` (2),
  `MarkdownViewer` (7). Keep the reports; do not restructure screens from this alone.

No D result is recorded, so no driven plan is closed. Plan 09's `largeHeap` stays a last resort until D3/D7 report.
Plan 13 §5 claims about TTS rejection, Android SQLite tokenization, `StrictMode` visibility, PDF seekability,
split-screen `office-media`, decoder mapping and on-device filename handling remain unverified.
