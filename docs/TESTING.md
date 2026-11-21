# Testing

Run the suite with JDK 17 and the Android SDK installed, as CI pins them in `.github/workflows/build.yml`.

| Task                                  | What it runs                               |
| ------------------------------------- | ------------------------------------------ |
| `./gradlew check`                     | unit tests, lint and detekt                |
| `./gradlew testDebugUnitTest`         | unit tests alone                           |
| `./gradlew connectedDebugAndroidTest` | semantics and end-to-end tests on a device |

The semantics suite needs a device or emulator kept awake and unlocked; [ACCESSIBILITY.md](../ACCESSIBILITY.md) covers
that and the manual TalkBack script.

The end-to-end tests are in `app/src/androidTest/java/io/github/pixelrick420/papyrus/e2e`. They launch the installed app, hand it
documents the way a file manager does, and drive it through the accessibility tree with UI Automator, so they call
nothing inside the app. They run on `debug` only: minification makes AGP minify the androidTest APK too and treat the
app's output as a provided classpath, so anything R8 stripped from the app is missing at run time and the runner dies
before the first test - a green build reporting zero tests. Every test starts from a fresh install, so none
depends on another: `FreshInstall` empties the database, the imported copies and the settings before each
one, and `PapyrusTestRunner` takes the same data off disk before any of the app's code loads.

The emulator in CI draws in software, so a frame can arrive seconds late and a tap can be lost. Two things keep that from
failing the build. `RetryRule` runs a failed end-to-end test again (a fixed number of attempts, each from a fresh
install, with a cap on the whole run so a broken build still ends at a report), and logs `FLAKY` for any test that only
passed on a retry. Then the emulator job grades the suite as a whole instead of failing on the first red test: it passes
when at least a configured share of the tests passed (`EMULATOR_MIN_PASS_PERCENT`, 90, in `.github/workflows/build.yml`). Tests
that never ran count as not passing, and a run that reports no tests at all fails, so a crashed run cannot pass by
omission. The failing tests are listed in the job summary and as warnings, and `logcat.txt` is in the
`emulator-test-results` artifact. Locally, `./gradlew connectedDebugAndroidTest` stays strict: any failure fails it.

The DOCX/ODT, EPUB and DOC extractors run against archives the tests build in memory (kxml2 stands in for the platform's
`XmlPullParser`, and the `.doc` fixtures assemble the OLE2 container and piece table from scratch). detekt is scoped to
dead code and correctness; deliberate broad catches carry a local `@Suppress` rather than a shared baseline entry, so a
new catch with the same shape still fails the build. `check` runs both detekt tasks: the AST-only one enforces unused
code and broad catches, and `detektDebug` resolves types to enforce `!!`-free code, no unreachable code, and immediate
rethrow of a caught `CancellationException` in suspend functions.

## Shared test utilities

`app/src/sharedTest` is on both the JVM and device test source sets. `CountingStreamFactory` wraps a stream factory and
records opens and bytes read; use it for "never reopened" and "never read" guards. `FakeClock` supplies the `() -> Long`
that TTL and sweep tests need. `GeneratedFiles` builds large single-line text, corrupt and zero-size PDFs, many-piece
`.doc` files and ZIPs with unused large entries at test time, so no large fixture is committed.

Device-only utilities live in `app/src/androidTest`: `@get:Rule val mainThread = MainThreadStallRule()` fails when the
main Looper's heartbeat gap exceeds 250 ms, and `SlowContentProvider` sleeps in `openFile` for
`SlowContentProvider.openFileDelayMillis`; set and reset that value around each use. Its URI is
`content://io.github.pixelrick420.papyrus.test.slowcontent/file`, declared in `app/src/androidTest/AndroidManifest.xml`.
Prefer a JVM equivalent when one exists: it runs in `check`, which is strict.

## What CI runs

`check` is strict: every unit test, lint check and detekt rule fails the job on the first failure. The push-only emulator
job is graded: it passes when at least `EMULATOR_MIN_PASS_PERCENT` (90) of tests pass, with `RetryRule` and the
zero-test guard above. `GoldenImageTest` is the one strict instrumented lane, checked separately with a class filter and
a zero minimum. Plan 12's optional `@RegressionGuard` lane is not used because the critical guards have JVM
equivalents, so a regression fails `check` before the emulator runs. Locally, `connectedDebugAndroidTest` is strict too.

`check` (unit tests, lint and detekt, with their reports uploaded as the `check-reports` artifact) and
`assembleRelease` run on every pull request to `main`; those plus the emulator suite run on every push to `main`. A
`v*` tag runs the unit tests and publishes the signed release — see [RELEASING.md](RELEASING.md). The Android jobs in
`build.yml` and `codeql.yml` share the SDK/NDK setup in `.github/actions/setup-android`; the web workflow runs lint,
check and build separately.
