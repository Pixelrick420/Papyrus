# Testing

Use JDK 17 and the Android SDK versions pinned in `.github/workflows/build.yml`.

| Task | Runs |
|---|---|
| `./gradlew check` | unit tests, lint, detekt |
| `./gradlew testDebugUnitTest` | unit tests only |
| `./gradlew connectedDebugAndroidTest` | semantics and end-to-end tests on a device |

The semantics suite needs an awake, unlocked device. See [ACCESSIBILITY.md](../ACCESSIBILITY.md) for its manual
TalkBack script.

## End-to-end tests

`app/src/androidTest/java/io/github/pixelrick420/papyrus/e2e` launches the installed app, hands it documents like a
file manager, and drives it through UI Automator; it calls no app code. Tests run on `debug` only, because R8 on a
minified APK strips classes the androidTest APK expects on its classpath. `FreshInstall` and `PapyrusTestRunner`
clear the database, imported copies and settings before each test.

`RetryRule` retries a failed test from a fresh install (fixed attempts and a whole-run cap) and logs `FLAKY` when a
retry passes. The CI emulator job passes when at least `EMULATOR_MIN_PASS_PERCENT` (95) of tests pass; not-run tests
count as failures and a zero-test run fails. Failures appear in the job summary and `emulator-test-results/logcat.txt`.
Local `connectedDebugAndroidTest` is strict. `GoldenImageTest` is the one strict instrumented lane, run separately
with a class filter and a zero minimum. The optional `@RegressionGuard` lane is not used; critical guards have
JVM equivalents in `check`.

## Static checks and timing

`check` runs AST-only detekt (unused code, broad catches), plus `detektDebug` with type resolution for `!!`-free code,
no unreachable code, and immediate rethrow of caught `CancellationException` in suspend functions. Broad catches use
local `@Suppress`, not a shared baseline.

Debug builds install `StrictMode` with `penaltyLog()`; instrumented runs skip it because harness violations and logs
distort frame timing, so `MainThreadStallRule` is the main-thread guard there. `LargeTextFrameTest` checks
jump-to-last-line elapsed time against a 3-second budget, not a `gfxinfo` frame percentile.

## Shared utilities

`app/src/sharedTest` is on JVM and device source sets:

- `CountingStreamFactory` records stream opens and bytes read.
- `FakeClock` supplies `() -> Long` for TTL/sweep tests.
- `GeneratedFiles` builds large single-line text, corrupt/zero-size PDFs, many-piece `.doc` files and large skipped ZIP
  entries at test time.

Device-only utilities in `app/src/androidTest`: `MainThreadStallRule` fails when the main Looper heartbeat gap
exceeds 250 ms; `SlowContentProvider` sleeps in `openFile` for `openFileDelayMillis` (set/reset per test) at
`content://io.github.pixelrick420.papyrus.test.slowcontent/file`. Prefer JVM equivalents; `check` is strict.

## CI

Every PR to `main` runs `check` and `assembleRelease`; every push adds the emulator suite. A `v*` tag runs unit tests
and publishes signed APKs ([RELEASING.md](RELEASING.md)). Android jobs share `.github/actions/setup-android`; the web
workflow runs lint, check and build separately.
