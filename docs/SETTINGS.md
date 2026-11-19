# Settings

Every switch below lives in the app's Settings screen. Defaults are the app's own; the labels match the UI.

- **Theme** follows the system, or stays light or dark. It is applied before the first frame, so the app opens in the
  right scheme instead of flashing the other one.
- **Colorblind mode** swaps the theme colours for a colour-blind safe palette of blue, orange and teal.
- **Night mode** inverts PDF pages and slide bitmaps, photos included, so white paper reads black.
- **Syntax highlighting** colours comments, strings, keywords and numbers in source and config files.
- **Line numbers** adds a gutter beside plain text, logs and code.
- **Wrap long lines** wraps at the screen edge. Off, each line stays on one row and the view scrolls sideways.
- **Hyphenation** splits long words at syllables where a `.txt` file wraps. Logs and code are never hyphenated.
- **Pretty print structured files** indents JSON, XML, HTML and SVG as they open, at the chosen tab size. The stored
  file is never changed, and an oversized one opens as stored.
- **Tab size** sets the indent for pretty printing: 2, 3, 4 or 8 spaces.
- **Autosave external docs** adds a document another app hands over to the library as it opens. Off, the viewer's
  **Save to library** keeps a copy instead.
- **Autosave encrypted docs** does the same for password-protected PDF, Word and OpenDocument files. They stay
  encrypted and the password is never stored.
- **Remember reading position** reopens a library document where you stopped and shows progress on Home. Off, no
  position is read or written, and the stored ones apply again when it goes back on.
- **Search inside documents** lets the library be read in the background so search can find words inside it. Larger
  files are paused while you read and under battery saver; small files are read as soon as they join the library, as
  described under [Content search](ARCHITECTURE.md#content-search). Off, nothing new is read, small files included,
  and what is already indexed stays searchable.
- **Crash Reports** lists local crash logs by date and size, lets you open one in the viewer (read only, share
  button only), and deletes one after a confirmation dialog. Reports are ACRA JSON stored in app storage and
  nothing leaves the device.
- **About** shows the version and links to release notes, source code, the Apache 2.0 license and the privacy policy.

Format-specific limits are in [LIMITATIONS.md](LIMITATIONS.md); the feature tour is in [FEATURES.md](FEATURES.md).
