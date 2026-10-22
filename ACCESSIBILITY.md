# Accessibility

Papyrus targets TalkBack first: every control is one focus stop, says what it is, and says what
pressing it does. The rules below are enforced by `AccessibilitySemanticsTest` and checked by hand
with the script at the end.

## What is covered

- A labelled control is one node. A field's placeholder and leading glyph do not become separate
  stops, and a badge joined to a button does not announce itself on its own.
- A control that changes state says so. A selected row reads as checked and its action reads
  "Select" or "Deselect", not "Open".
- Sheet titles are headings, so TalkBack's heading navigation skips between sections.
- The find-in-file match counter is a polite live region: stepping through matches announces
  "2 of 7" without moving focus.
- Interactive controls have a touch target of at least 48dp. Material's
  `minimumInteractiveComponentSize` supplies this; the tests assert the touch bounds rather than the
  visual size.

## Running the suite

```bash
./gradlew connectedDebugAndroidTest
```

This needs a connected device or a running emulator. Keep the phone awake and unlocked while it
runs: a slept device leaves the test activity paused, and the suite waits for it to resume before
the first test starts. `adb shell svc power stayon true` stops it sleeping.

`.github/workflows/build.yml` runs the same task on an API 34 emulator for every branch push and
uploads the HTML report as an artifact.

Run a single test:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.papyrus.app.ui.AccessibilitySemanticsTest
```

## Manual TalkBack script

Enable TalkBack in Android Settings, then walk each screen with the screen curtain on and the
device face down if you want to remove visual cues.

### Home

1. The search pill is one stop: "Search files, edit box". Typing filters the list.
2. The clear button appears only when there is text: "Clear search, button".
3. The sort button is one stop: "Sort: Last opened, button". With filters on it also reads the
   count, for example "Sort: A to Z, 2 filters active, button".
4. Each document row is one stop: title, size, when it was opened, then "Double tap to open".
   A long press starts selection.
5. In selection mode the header reads back, the count, Remove, Share and "Select all, checkbox".
   Each row reads "checked" or "not checked" and its action is "Select" or "Deselect". A tap
   toggles once; there is no second checkbox stop.
6. Empty, no-match and no-results-for-filters states are each announced once with their action
   button last.

### Viewer

1. Back, title and overflow are separate stops and the title is a heading.
2. **Read aloud bar:** play/pause is labelled with the action it will take ("Play" or "Pause"). Skip
   back, skip forward and stop are labelled. The scroll lock label changes between "Lock scrolling
   to the line being read" and "Unlock scrolling". The speed chip reads "Speed 1.5×, button" and a
   double tap cycles the speed.
3. **Find in file:** the field is one stop: "Find, edit box". The match counter announces changes on
   its own. Previous match, next match and close find are labelled; the arrows read as disabled when
   there is nothing to step to.
4. **File info:** the sheet title is a heading. Each field is one swipe: "Name, report.pdf",
   "Format, PDF", "Size, 1.0 kB", and so on. The URI is one long stop.
5. **Outline:** the title is a heading. A fold control names the entry it hides or shows
   ("Expand Chapter 1"); an entry without a destination is present but not clickable.
6. **Bookmarks:** the title is a heading; rename and delete name the bookmark they act on.

### Known limits

- PDF pages are bitmaps. TalkBack can announce the page indicator and any extracted text used for
  find-in-file, but it cannot read the rendered page.
- Pinch zoom is a two-finger gesture that TalkBack intercepts. Use Android's accessibility zoom
  instead. Text documents keep their normal TalkBack text navigation.
- Images inside documents are not described.

## Adding a check

Add a test to `AccessibilitySemanticsTest` next to the existing one for that component. Use
`onNodeWithContentDescription` for the label and `assertHasClickAction` for the action. For touch
targets use the test's `minTouchTarget`, which reads touch bounds; a plain height assertion would
measure the visual size and fail on Material controls that are visually smaller than 48dp.
