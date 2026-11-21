# Device verification ledger

Plan 13 separates checks that need real Android from checks that can run in CI. This file records the result of each one.
A device check stays open until it has been run on hardware; the run records below are all blank.

## Device checks (D1–D11)

| ID | Question | Drives | Status |
|---|---|---|---|
| D1 | Does an over-long TTS unit fail, and is it reported by callback, return code, or both? | plan 02 | **NOT RUN** |
| D2 | How long does the zip guard take on a large skipped entry, and is a 2 GiB skipped budget acceptable? | plan 01 | **NOT RUN** |
| D3 | Do multi-MB single lines and very large files stall layout or scrolling? | plans 03, 09 | **NOT RUN** |
| D4 | Does Android SQLite tokenise Indic and Arabic marks like the sandbox SQLite? | plan 04 | **NOT RUN** |
| D5 | Which providers need the PDF copy fallback, and can they be told apart from corrupt files? | plan 06 | **NOT RUN** |
| D6 | Is a many-piece `.doc` slow enough to matter on a cloud provider? | plan 08 | **NOT RUN** |
| D7 | What is the memory behaviour of a large deck on a 2–3 GB device? | plan 09 | **NOT RUN** |
| D8 | Is autosave of a 300 MB external file noticeable? | plan 05 | **NOT RUN** |
| D9 | How costly is a library of unreadable files and DOCX thumbnails? | plan 05 | **NOT RUN** |
| D10 | Does closing one viewer window delete another window's `office-media`? | plan 06 | **NOT RUN** |
| D11 | Does `StrictMode` see a cross-process `ContentResolver` call on the main thread? | plans 03, 12 | **NOT RUN** |

The procedures are in `13-device-verification-and-unreviewed-areas.md` under "Verification backlog". None can run in this
environment: no device or emulator is attached. `MainThreadStallRule` and `SlowContentProvider` from plan 12 are ready
for D3, D10 and D11 when one is available.

| ID | Device model | Android | TTS engine | App build | Result | logcat excerpt |
|---|---|---|---|---|---|---|
| D1 | — | — | — | — | not run | — |
| D2 | — | — | — | — | not run | — |
| D3 | — | — | — | — | not run | — |
| D4 | — | — | — | — | not run | — |
| D5 | — | — | — | — | not run | — |
| D6 | — | — | — | — | not run | — |
| D7 | — | — | — | — | not run | — |
| D8 | — | — | — | — | not run | — |
| D9 | — | — | — | — | not run | — |
| D10 | — | — | — | — | not run | — |
| D11 | — | — | — | — | not run | — |

Fill a row with the device model, Android version, TTS engine where relevant, the exact app build, the observed result,
and a short `logcat` excerpt. Do not mark a check done from code inspection alone.

## CI-side checks (C1–C3)

| ID | Question | Status |
|---|---|---|
| C1 | Does `UnsafeCallOnNullableType` run under `check`? | **PASS** |
| C2 | Does `setup-gradle@v5` validate the Gradle wrapper? | **PASS** |
| C3 | Do Compose compiler reports show unstable large screens? | **PASS (informational)** |

### C1 — detekt rule is enforced

`./gradlew detektDebug --offline` passes with 0 findings on the current tree. A temporary main-source function containing
`value!!` was added and `detektDebug` failed with one `UnsafeCallOnNullableType` finding; the probe was then removed. The
`check` task depends on `detektDebug`, so a new `!!` in main code fails CI. The seven sites listed in plan 10 are gone.
The rule gates main/debug sources; `!!` assertions in unit-test code are outside that gate and were not part of the
seven-site cleanup. The baseline remains empty.

### C2 — wrapper validation is automatic

The pinned action is `gradle/actions/setup-gradle@0723195856401067f7a2779048b490ace7a47d7c` (v5.0.2). Its v5.0.2
`docs/setup-gradle.md` states under "Gradle Wrapper validation": "By default, this action will perform the same wrapper
validation as is performed by the dedicated wrapper-validation action." No separate `gradle/actions/wrapper-validation`
step is needed; the plan 11 workflows correctly omit one.

### C3 — Compose compiler reports

`app/build.gradle.kts` sets `composeCompiler.reportsDestination` to `app/build/compose-reports`. After a forced debug
compile, `app-composables.txt` shows the large screens are `restartable skippable`, but several take unstable parameters:

| Composable | Unstable parameters |
|---|---|
| `HomeScreen` | 1 (`HomeViewModel?`) |
| `SettingsScreen` | 1 (`SettingsViewModel?`) |
| `ViewerScreen` | 1 (`ViewerViewModel?`) |
| `PdfViewer` | 8 (aspect ratios, page hits, match and highlight maps, links callback, font substitutions, selected pages, search results) |
| `OfficeViewer` | 2 (`List<OfficeBlock>`, `Set<Int>?`) |
| `MarkdownViewer` | 7 (`Markwon`, `Spanned`, `LatexSpanFactory?`) |

Decision: keep report generation, but do not restructure screens from this data alone. Plan 10's §4 decomposition stays
a separate behaviour-preserving series of PRs; when it lands, hoist or stabilise the state behind these parameters rather
than only splitting files.

## Decisions and no-action items

- D1–D11: no result recorded, so no driven plan is closed by this pass. Plan 02's TTS cap, plan 04's search fix and the
  plan 06/08/09 questions all remain subject to the device checks above.
- Plan 09 item 4 (`largeHeap` and memory budget): no action until D3 and D7 report measurements. Adding `largeHeap` is
  still the last resort.
- C1: closes the "rule configured but not running" hypothesis; no build change.
- C2: closes the separate wrapper-validation question; no workflow change.
- C3: no restructuring now; the reports are kept so the next decomposition PR has a baseline.
- The plan 13 §5 claims about TTS rejection, Android SQLite tokenisation, `StrictMode` visibility, PDF seekability,
  split-screen `office-media`, decoder mapping and on-device filename handling remain unverified for the same reason as
  their D items.
