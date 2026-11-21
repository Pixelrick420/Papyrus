# Accessibility

Papyrus targets TalkBack first: each control is one focus stop that says what it is and what it does. The semantic
contract is enforced by `AccessibilitySemanticsTest`, the interaction paths by the end-to-end suite, and both are
checked by hand with the script below.

## Semantic contract

- A labelled control is one node; placeholder, leading glyph and joined badge are not separate stops.
- State changes are announced: selected rows read as checked; row actions read "Select" or "Deselect", not "Open".
- A search excerpt is one text node; highlight spans do not split it.
- Sheet titles and headings in EPUB, DOCX, ODT and Markdown are headings; alt text is read where present, and
  unlabeled images are skipped.
- A slide is one stop: "Slide 3 of 24", title as heading, text in reading order, then picture/chart alt text.
  Decorative shapes are skipped and hidden slides announce "Hidden".
- Find match counters are polite live regions ("2 of 7"); PDF links are labelled stops ("Link", "Open link"), while
  slide links stay tap-only.
- Long bulk Remove/Share exposes one text node ("Removing 3 of 12") with no live region; the snackbar announces the
  result once.
- The read-aloud bar is six labelled stops with no self-announcement. Play/pause reads the next action, lock reads
  "Lock scrolling"/"Unlock scrolling", and an engine failure leaves Play available while other engine controls read
  as disabled.
- Snackbars are one polite region including Undo; a second message waits. An empty two-pane placeholder is one plain
  text node.
- `FocusOrderTest` pins bar order; interactive targets are at least 48dp via `minimumInteractiveComponentSize`.

## Running the suite

```bash
./gradlew connectedDebugAndroidTest
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.pixelrick420.papyrus.ui.AccessibilitySemanticsTest
```

A connected device or emulator must stay awake and unlocked (`adb shell svc power stayon true`). CI runs the same task
on push to `main` and uploads the HTML report.

## Manual checks

Enable TalkBack and walk each screen with the screen curtain on.

- **Home:** "Search files, edit box"; clear appears only with text; sort reads its order and filter count; a row reads
  title/size/opened/excerpt then "Double tap to open"; long press starts selection. In selection, the header, count,
  Remove, Share and "Select all, checkbox" replace the search row; each row reads checked/not checked with one toggle.
  Empty, no-match and no-filter-result states announce once with their action last. Long bulk operations expose only
  the progress node and one result; single-row Remove still offers Undo. On wide, tall windows the list is left, the
  document right, the empty pane reads "Choose a document from the list to read it here", and the open row reads
  "selected".
- **Viewer:** Back, heading title and overflow are separate stops. Find is one field with an announced counter and
  labelled Previous/Next/Close. File info, outline, bookmarks, PDF form fields and sheets open with a heading title.
  PDF selection long-press replaces overflow with Copy/Clear selection, plus Highlight or Remove highlight for
  library PDFs; a page without a text layer says so. Stored highlights are "Note: …" stops with "Open note"; their
  sheets have a counter, Save and Delete highlight. PDF page text reads "Page 3." then the page text. Form fields are
  one stop each with label, page, value and state. Headings and described images in Markdown/EPUB/DOCX/ODT are their
  own stops. Open errors name unsupported, protected, damaged or moved/deleted files and offer "Pick file".
- **Read aloud:** play/pause reads "Play" or "Pause"; skip, stop and lock are labelled; speed reads "Speed 1.5×,
  button". The bar appears only for selectable text; Android 13+ asks once to show the playback notification.
- **Slides:** one stop per slide with number, title, text, table cells and alt text; decorative shapes are silent and
  hidden slides end with "Hidden". Speaker notes are not read.
- **Focus order:** Home starts at the "Papyrus" heading, then Settings, search, sort, rows and add-files. Selection
  puts the header in the search row's place and keeps focus on the pressed row. Two-pane puts the whole list before the
  viewer. In the viewer, order is Back, title, Share, overflow, read-aloud controls, then document; Find puts focus
  in its field and orders field, counter, Previous, Next, Close, document. Sheets/dialogs start at the title heading
  and fields take focus. Tab follows the same order.
- **Keyboard:** with TalkBack off, Tab/Shift+Tab move focus and Enter/Space press. Ctrl+F opens Find, Ctrl+G opens Go
  to line/page, Ctrl+D adds a bookmark where the menu offers them. Selection cannot start from a keyboard (row menus
  still have Share, Star and Remove for one file). Esc closes sheets/dialogs; arrow keys step choices and row menus.
- **Switch Access:** use scan or linear scanning with TalkBack off. Every control in the TalkBack script is reachable;
  a Home row offers Open and Select, selection headers are reachable, scrolling moves each document a screen at a
  time, and sheets/dialogs start on their first stop.

## Known limits

- PDF pages and slides are bitmaps: text is one stop per page/slide, not per line or shape.
- Notes stay in Papyrus and are not exported to saved PDFs.
- Pinch zoom, double-tap zoom and the fast-scroll knob are touch gestures; use accessibility zoom and Go to instead.
- Markdown headings and described images are virtual nodes, so alt text is read both there and in the document text.
- Keyboard multi-select and PDF text selection need long press/drag; existing notes and PDF links remain reachable.

## Adding a check

Put component assertions in `AccessibilitySemanticsTest` and bar order in `FocusOrderTest`. Use
`onNodeWithContentDescription`, `assertHasClickAction` and the test's `minTouchTarget` (touch bounds, not visual
height).
