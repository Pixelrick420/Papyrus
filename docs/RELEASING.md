# Releasing

Releases are cut from `main` by the `build` workflow in `.github/workflows/build.yml`.

- Every pull request to `main` runs the unit tests and assembles the unsigned release APKs uploaded as a build
  artifact, so a broken build shows up before the merge. The emulator suite is left to the push to `main`, to keep
  runner cost down.
- Every push to `main` runs the unit tests and the emulator suite, and assembles the unsigned release APKs uploaded as
  a build artifact. Failing emulator tests are tolerated unless a configured share of the suite did not pass; see
  [TESTING.md](TESTING.md).
- Pushing a `v*` tag runs the unit tests and publishes three signed APKs (arm64-v8a, armeabi-v7a and universal) to
  [Releases](https://github.com/Pixelrick420/Papyrus/releases). It does not run the emulator suite, which belongs to
  the `main` push, and it refuses a tag that is not on `main`.
- The tag must match `versionName` in `app/build.gradle.kts`, and `versionCode` must exceed the previous release tag's,
  since that value alone orders the upgrades.
- The job fails if any APK comes out unsigned or carries the Android debug certificate.
- Signing uses a release keystore supplied from repository secrets, never committed to the repo. The job stops early
  if any of those secrets is missing rather than publishing an APK no one can upgrade to.

The secrets are `PAPYRUS_KEYSTORE_BASE64`, `PAPYRUS_STORE_PASSWORD`, `PAPYRUS_KEY_ALIAS` and `PAPYRUS_KEY_PASSWORD`.
Local release builds read the equivalent `PAPYRUS_STORE_FILE`, `PAPYRUS_STORE_PASSWORD`, `PAPYRUS_KEY_ALIAS` and
`PAPYRUS_KEY_PASSWORD` Gradle properties; without all four, `release` stays unsigned.
