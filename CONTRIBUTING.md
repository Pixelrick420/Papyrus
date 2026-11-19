# Contributing

Issues and pull requests are welcome.

## Before you send a pull request

- Run `./gradlew check` before sending one: it runs the unit tests, lint and detekt, the same task CI runs. The full
  test reference is in [docs/TESTING.md](docs/TESTING.md).
- Keep a change focused. A bug fix and an unrelated refactor belong in separate pull requests.
- The README is a landing page and should point at the workflows rather than duplicate CI checks, task tables or
  per-format detail. Put that detail in `docs/`, where it can be kept current.
- If you change accessibility behaviour, follow [ACCESSIBILITY.md](ACCESSIBILITY.md): it holds the manual TalkBack
  script and the contract enforced by `AccessibilitySemanticsTest`.
- The screenshots live in `fastlane/metadata/android/en-US/images/`, so the store listing and the README stay in step
  when you update them.

## Licensing rules for the PPTX feature

Permissive code and data may be vendored with a NOTICE entry; copyleft code may not be copied; behaviour may be
studied from anywhere.

- Apache POI code is a reference oracle only. Do not copy it; if any derived code is ever merged,
  add headers and a NOTICE entry.
- LibreOffice and OnlyOffice code (MPL-2.0 + LGPL / AGPL) is never copied or translated.
  Observing their output as a cross-check is fine.

Third-party attribution for what ships in the app is kept in [NOTICE](NOTICE).
