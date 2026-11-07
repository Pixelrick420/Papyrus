# Accessibility

Papyrus targets TalkBack first: every control is one focus stop, says what it is, and says what
pressing it does. The semantic rules below are enforced by `AccessibilitySemanticsTest`, the behavioural steps by
the end-to-end suite, and both are checked by hand with the script at the end.

## What is covered

- A labelled control is one node. A field's placeholder and leading glyph do not become separate
  stops, and a badge joined to a button does not announce itself on its own.
- A control that changes state says so. A selected row reads as checked and its action reads
  "Select" or "Deselect", not "Open".
- A search result's excerpt is one text node. The highlighted words do not split it into separate stops,
  so TalkBack reads it as a sentence.
- Sheet titles are headings, so TalkBack's heading navigation skips between sections. So are the
  headings inside EPUB, DOCX and ODT documents and inside Markdown.
- An image that has alt text (EPUB, DOCX, ODT, Markdown) reads that text. An image without it is skipped.
- The find-in-file match counter is a polite live region: stepping through matches announces
  "2 of 7" without moving focus.
- A multi-file Remove or Share that runs long enough to show progress says so in one text node
  ("Removing 3 of 12") and nothing else: the bar is not exposed and no live region is involved, so the
  steps are not read out as they pass. The result is announced once, by the snackbar, when it ends.
- The read-aloud bar is six labelled stops and says nothing on its own. Play/pause reads the action it will
  take, the scroll lock reads "Lock scrolling" or "Unlock scrolling", and after an engine failure everything
  that needs the engine reads as disabled while Play stays.
- A snackbar is one polite live region holding its message and its Undo button, so it is announced once.
  A second message waits for the first to leave rather than sharing its region.
- The empty right-hand pane of the two-pane layout is one plain text node: no action, no live region.
- The controls of each bar are reached in a fixed order: the selection header, the search row, the find bar
  and the read-aloud bar are checked by `FocusOrderTest`, and the screens by the script below.
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

`.github/workflows/build.yml` runs the same task on an emulator in CI for every push to `main`, and uploads the
HTML report as an artifact.

Run a single test:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.papyrus.app.ui.AccessibilitySemanticsTest
```

## Manual TalkBack script

Enable TalkBack in Android Settings, then walk each screen with the screen curtain on and the
device face down if you want to remove visual cues.

### Home

1. The search pill is one stop: "Search files, edit box". Typing filters the list by title and adds documents
   whose text contains the words.
2. The clear button appears only when there is text: "Clear search, button".
3. The sort button is one stop: "Sort: Last opened, button". With filters on it also reads the
   count, for example "Sort: A to Z, 2 filters active, button".
4. Each document row is one stop: title, size, when it was opened and, for a content match, its excerpt, then
   "Double tap to open".
   A long press starts selection.
5. In selection mode the header reads back, the count, Remove, Share and "Select all, checkbox".
   Each row reads "checked" or "not checked" and its action is "Select" or "Deselect". A tap
   toggles once; there is no second checkbox stop.
6. Empty, no-match and no-results-for-filters states are each announced once with their action
   button last.
7. **Bulk Remove and Share:** with a long selection, confirm Remove. Nothing is read while it runs. Swipe
   to the header: it reads "Removing 3 of 12" (Share reads "Preparing 3 of 12") and Remove, Share and
   Select all read as dimmed. When it ends the snackbar announces "Removed 12 files" once, and selection
   closes. A single row's Remove still offers Undo.
8. **Tablet or unfolded foldable** (a tablet-width, non-compact-height window): the list is on the left and
   the document on the right. Before one is chosen the right side reads "Choose a document from the list to
   read it here". Once one is open its row in the list reads "selected". Back closes the open document, and a
   second Back leaves the app. A phone, including one on its side, keeps the single pane and the script
   above unchanged.

### Viewer

1. Back, title and overflow are separate stops and the title is a heading.
2. **Read aloud bar:** play/pause is labelled with the action it will take ("Play" or "Pause"). Skip
   back, skip forward and stop are labelled. The scroll lock label changes between "Lock scrolling
   to the line being read" and "Unlock scrolling". The speed chip reads "Speed 1.5×, button" and a
   double tap cycles the speed. The bar appears only when the document has selectable text. On Android 13
   and up, using read aloud for the first time asks to show the playback notification; a denial is remembered
   so it is not asked again.
3. **Find in file:** the field is one stop: "Find, edit box". The match counter announces changes on
   its own. Previous match, next match and close find are labelled; the arrows read as disabled when
   there is nothing to step to.
4. **File info:** the sheet title is a heading. Each field is one swipe: "Name, report.pdf",
   "Format, PDF", "Size, 1.0 kB", and so on. The URI is one long stop.
5. **Outline:** the title is a heading. A fold control names the entry it hides or shows
   ("Expand Chapter 1"); an entry without a destination is present but not clickable.
6. **Bookmarks:** the title is a heading; rename and delete name the bookmark they act on.
7. **PDF text selection:** a long press on a page with a text layer selects a run of text and
   replaces the overflow control with "Copy, button" and "Clear selection, button", plus
   "Highlight, button" for a library PDF and "Remove highlight, button" when the selection touches
   a highlight. A page with no text layer shows "This page has no selectable text." Copy confirms
   with a "Copied" toast before Android 13 and with the system's own confirmation from Android 13
   on.
8. **PDF page text:** with TalkBack running, a shown page reads "Page 3." followed by the page's
   own text, from the platform renderer where the device has one and from PDFBox otherwise. A page
   with no text layer reads only its number.
9. **PDF form fields:** a PDF with a fillable AcroForm offers Form fields in the menu. Each field is
   one stop with its label, page, value and state; text and choice fields open a dialog, while check
   boxes and radio buttons switch on tap. Edits apply to the open document only: Save a copy writes a
   new file through the system picker and never changes the original. A PDF whose form is XFA
   offers no Form fields, so it stays as rendered, and a signature field is shown but cannot be
   signed.
10. **PDF notes:** tapping any stored highlight opens its note sheet, so saving text in a plain
    highlight turns it into a note. A note is one stop over the passage it marks, read as
    "Note: <the reader's words>" and labelled "Open note" as a button. The stop covers the whole run,
    not the dot drawn at its end, so it is a full fingertip target. The sheet offers a note field
    with a character counter, Save, and Delete highlight. Only plain highlights are written into a
    saved copy of the PDF; the note stays in Papyrus.
11. **Headings:** in a Markdown, EPUB, DOCX or ODT document, set TalkBack's reading controls to
    Headings and swipe down. Focus moves from heading to heading and each reads as "heading".
12. **Image alt text:** in an EPUB, DOCX, ODT or Markdown document with a described image, the image is one
    stop that announces as an image and reads its alt text. An image with no alt text is not a stop. In Markdown
    the alt text is also part of the document's text, so it is read there too.
13. **Open errors:** a file that cannot be opened shows its own message: unsupported type, protected
    in a way Papyrus cannot unlock, damaged, or moved or deleted. A password prompt says whether
    the password is required or was wrong. A file that is moved, deleted or no longer accessible
    offers "Pick file" to choose it again.

### Focus order

`FocusOrderTest` pins the order inside each bar. These steps cover what a bar test cannot: the first
stop of a whole screen and the order across its parts. Swipe right from the top of the screen to walk it.

1. **Home:** first stop is the "Papyrus" title (a heading), then Settings, the search field, the sort
   button, each document row from the top, and the add-files button last.
2. **Into selection:** long press a row. The header takes the search row's place and the order becomes
   title, Settings, Back, the count, Remove, Share, Select all, then the rows. The add-files button is gone.
   Focus stays on the row that was pressed, which now reads "checked".
3. **Out of selection:** press Back in the header. The search row returns and focus lands on a stop, never
   on nothing; the order is the one in step 1.
4. **Two-pane:** the whole list pane comes first (step 1), then the right pane. With no document open the
   right pane is the one prompt. After a row is opened, the swipe that leaves the last row enters the
   viewer's Back button. Closing the document returns the right pane to the prompt and leaves focus in the
   list.
5. **Viewer:** Back, the title (a heading), Share where it is offered, then the overflow menu. Opening Find
   puts focus in the find field and raises the keyboard; the order is the field, the counter, Previous match,
   Next match, Close find, then the document. Read aloud sits between the header and the document: skip
   back, play or pause, skip forward, the scroll lock, speed, stop. With a text selection the overflow menu
   gives way to Copy, then Highlight and Remove highlight where offered, then Clear selection.
6. **Settings and Crash reports:** Back, the title (a heading), then the first section heading and its rows
   in the order they appear.
7. **Sheets and dialogs:** the first stop is the title (a heading). A dialog with a field (Go to, password,
   a bookmark's name) puts focus in the field and raises the keyboard.
8. **Hardware keyboard:** Tab moves through the same controls in the same order and skips text that is not
   a control, such as the selection count. The keyboard pass below walks it in full.

### Keyboard

Connect a hardware keyboard (the emulator takes the host's) and turn TalkBack off. Do everything here without
touching the screen. A control that cannot be reached, or that does not answer, is fixed or goes under Known
limits.

1. **Reach and press:** Tab and Shift+Tab move focus through the controls in the order of the focus-order steps
   above, and Enter or Space presses the focused one. Each stop shows that it has focus; a control that takes
   focus with no mark on it is a bug.
2. **Home:** the search field takes typing once Tab reaches it. Enter on a row opens it. On a row's more options
   button Enter opens its menu, the arrow keys step through Share, Star and Remove, Enter chooses one and Esc
   closes the menu. Selection cannot be started from a keyboard (see Known limits), so Remove and Share for
   several files at once are not available here; each row's own menu does both for one file.
3. **Selection started by touch:** Tab reaches Back, Remove, Share and Select all, then the rows. Enter on a row
   toggles it, and Back leaves selection.
4. **Viewer shortcuts:** Ctrl+F opens find and puts focus in its field, also when a field already has it. Ctrl+G
   opens Go to line or Go to page where the menu offers one. Ctrl+D adds a bookmark where the menu offers Add
   bookmark. Where the menu does not offer the action (a crash report has no menu) the shortcut does nothing.
5. **Find and read-aloud bars:** Tab walks each bar in the order `FocusOrderTest` pins, and Enter presses
   Previous match, Next match, Close find and each read-aloud control.
6. **Sheets and dialogs:** focus starts inside and stays inside, Esc closes them, and a dialog with a field
   puts focus in it.
7. **Settings:** Tab moves down the rows, Space or Enter flips a switch or opens a choice, and the arrow keys
   step through a choice's options.
8. **Two panes:** Tab leaves the last list row for the viewer's Back button. Back closes selection first, then
   the open document, then the app.

### Switch Access

Turn on Switch Access in Android's accessibility settings, give it a switch, and use auto-scan or linear
scanning with TalkBack off. Do everything here with the switch alone. A control that scanning never reaches is
fixed or goes under Known limits.

1. **Scanning reaches every control:** on each screen of the TalkBack script every control is highlighted in
   turn, and selecting it does what a tap does. Text that is not a control is passed over.
2. **A Home row:** selecting a row offers its actions: "Open", and "Select", which is the long press that starts
   selection. With selection on, each row offers "Select" or "Deselect". The more options button opens the row's
   menu, and every item in it can be scanned and selected.
3. **Selection:** Back, Remove, Share and Select all in the header are reached. Remove asks to confirm, and
   Cancel and the confirm button are reached. The add files button is gone while selecting, so it is not scanned.
4. **Scrolling:** Switch Access's scroll actions move the Home list, a text file, Markdown, an office document,
   an EPUB and a PDF a screen at a time, so no place in a long document needs a touch. Go to line and Go to page
   are reached for longer jumps.
5. **Viewer bars:** the header, the find bar and the read-aloud bar are scanned in the order of the focus-order
   steps. Selecting the speed chip cycles the speed.
6. **Sheets, dialogs and menus:** each opens with scanning on its first stop and closes from its own controls or
   through Back. The password prompt's field takes focus.

### Known limits

- PDF pages are bitmaps, so TalkBack reads their extracted text as one stop per page rather than
  walking the text line by line.
- A note is stored, drawn and read in Papyrus only. It is not written into a saved copy of the PDF,
  since the platform's annotation API offers no note annotation here.
- Pinch zoom is a two-finger gesture that TalkBack intercepts. Use Android's accessibility zoom
  instead. Text documents keep their normal TalkBack text navigation. Double-tap zoom and the fast-scroll
  knob are touch gestures too, with no other control; scrolling and Go to take the place of the knob.
- Headings and described images in Markdown are virtual nodes over one text view, so each is a stop of its
  own as well as part of the document's text. An image's alt text is read in both places.
- Selection on Home starts with a long press, which a hardware keyboard has no key for. Switch Access and
  TalkBack start it from the row's "Select" action. A keyboard still has Share, Star and Remove for one file in
  each row's menu; only Remove and Share for several files at once need selection.
- PDF text selection starts with a long press on the page and moves with drag handles, and a link on a PDF page
  is followed by tapping it. Neither is a stop of its own, so a keyboard or Switch Access cannot copy, highlight
  or add a note to PDF text, or follow a PDF's links. A note that already exists is a button and can be opened.

## Adding a check

Add a test to `AccessibilitySemanticsTest` next to the existing one for that component. A change to the order
a bar's controls are reached in goes in `FocusOrderTest`, which reads the order off the semantics tree. Use
`onNodeWithContentDescription` for the label and `assertHasClickAction` for the action. For touch
targets use the test's `minTouchTarget`, which reads touch bounds; a plain height assertion would
measure the visual size and fail on Material controls that are visually smaller than 48dp.
