# Contributing

Issues and pull requests are welcome.

- Run `./gradlew check` (unit tests, lint, detekt) before sending a PR.
- Keep changes focused; put per-format detail in `docs/`, not the README.
- Accessibility changes must follow [ACCESSIBILITY.md](ACCESSIBILITY.md) and `AccessibilitySemanticsTest`.
- Screenshots live in `fastlane/metadata/android/en-US/images/`.

## Code style

- Kotlin follows the official style (`kotlin.code.style=official`); keep comments to one short line about a non-obvious why.
- `./gradlew detektDebug` runs the type-resolution rules; plain `detekt` only runs the AST rules.
- Install tasks capture only plain values (paths and strings) in `doLast`: the configuration cache is on for every build.

## Compatibility policy

- Downgrades are unsupported: Room has no `onDowngrade` handler, so installing an older build over a newer database fails. Uninstalling clears local data.
- UI strings live only in `app/src/main/res/values/strings.xml`; translations and locale-specific fallback are not part of this project yet.

## Tests

- `./gradlew check` covers JVM tests; instrumented tests need a device or emulator (`./gradlew connectedDebugAndroidTest`).
- CI keeps a 95% soft gate on the emulator suite: too few results, or fewer declared tests reported, fails even when the pass rate looks fine.
- `GoldenImageTest` is exempt from that tolerance and must pass in full; a golden diff is a deterministic regression, not device flakiness.
- After editing an install task, check its configuration-cache entry with `./gradlew --configuration-cache --dry-run installLocalDebug installLocalRelease`.

## Releases

- A tag publishes only when its commit passed both CI jobs on `main` (Test and build, Emulator tests).
- The three release APKs ship with a `papyrus-<tag>-SHA256SUMS.txt` file and a SLSA build provenance attestation; verify one with `gh attestation verify --owner Pixelrick420 <apk>`.
- Changelogs are named by `versionCode` under `fastlane/metadata/android/en-US/changelogs/`. The archive starts at code 17 (v1.0): 0.x was a fresh-start preview with no release archive, so there is no gap to backfill.

## Build tooling review notes

- Versions were reviewed as one set (AGP 9.5.2, Compose compiler 2.4.21, KSP 2.3.14, detekt 2.0.0-alpha.6 against androidx-core 1.16.0, activity 1.10.1, lifecycle 2.9.0). None was changed without a full build: the Kotlin/KSP and AGP/Gradle couplings must be verified together in a dedicated dependency PR.
- The configuration cache runs with Gradle's default fail-on-problem mode; warning mode and the integrity-check flag are intentionally not set.

## PPTX licensing

Permissive code/data may be vendored with a NOTICE entry; copyleft code may not be copied; behavior may be studied
from anywhere. Apache POI is a reference oracle only (no copying; add headers/NOTICE if derived code is ever merged).
LibreOffice and OnlyOffice code (MPL-2.0 + LGPL / AGPL) is never copied or translated; observing output is fine.

Attribution for shipped code is in [NOTICE](NOTICE).
