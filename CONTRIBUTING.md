# Contributing

Issues and pull requests are welcome.

- Run `./gradlew check` (unit tests, lint, detekt) before sending a PR.
- Keep changes focused; put per-format detail in `docs/`, not the README.
- Accessibility changes must follow [ACCESSIBILITY.md](ACCESSIBILITY.md) and `AccessibilitySemanticsTest`.
- Screenshots live in `fastlane/metadata/android/en-US/images/`.

## Compatibility policy

- Downgrades are unsupported: Room has no `onDowngrade` handler, so installing an older build over a newer database fails. Uninstalling clears local data.
- UI strings live only in `app/src/main/res/values/strings.xml`; translations and locale-specific fallback are not part of this project yet.

## PPTX licensing

Permissive code/data may be vendored with a NOTICE entry; copyleft code may not be copied; behavior may be studied
from anywhere. Apache POI is a reference oracle only (no copying; add headers/NOTICE if derived code is ever merged).
LibreOffice and OnlyOffice code (MPL-2.0 + LGPL / AGPL) is never copied or translated; observing output is fine.

Attribution for shipped code is in [NOTICE](NOTICE).
