# Releasing

Releases are cut from `main` by `.github/workflows/build.yml`.

- PR to `main`: unit tests plus unsigned release APK artifact; no emulator suite.
- Push to `main`: unit tests, graded emulator suite and unsigned release APK artifact.
- `v*` tag: unit tests and three signed APKs (arm64-v8a, armeabi-v7a, universal) published to
  [Releases](https://github.com/Pixelrick420/Papyrus/releases). The tag must be on `main`; the emulator suite is not
  rerun.
- Version names follow `X.Y.Z` (breaking/new feature/fix); tags add `v`, so a fix is `v2.1.1`.
- Tag must match `versionName` in `app/build.gradle.kts`; `versionCode` must exceed the previous release tag. Room
  migrates a 2.1 database from version 1 to 2 on first open, keeping the library.
- The job fails on unsigned or debug-certificate APKs. Signing secrets:
  `PAPYRUS_KEYSTORE_BASE64`, `PAPYRUS_STORE_PASSWORD`, `PAPYRUS_KEY_ALIAS`, `PAPYRUS_KEY_PASSWORD`.
- Local release builds use Gradle properties `PAPYRUS_STORE_FILE`, `PAPYRUS_STORE_PASSWORD`, `PAPYRUS_KEY_ALIAS`,
  `PAPYRUS_KEY_PASSWORD`; without all four, `release` stays unsigned.
