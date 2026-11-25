# Settings

Labels match the app; each entry gives its startup default.

- **Theme**: system (default), light or dark; applied before the first frame.
- **Colorblind mode**: off by default; on uses a blue/orange/teal palette.
- **Night mode**: off by default; on inverts PDF pages and slide bitmaps.
- **Syntax highlighting**: on by default; colours comments, strings, keywords and numbers in source/config files.
- **Line numbers**: off by default; on shows a gutter in text, logs and code.
- **Wrap long lines**: off by default (scroll sideways); on wraps at the screen edge.
- **Hyphenation**: on by default for syllables in prose text files, not code or `.log`; a `.txt` that looks like code is
  still prose.
- **Pretty print structured files**: on by default; indents JSON, XML, HTML and SVG at the chosen **Tab size** (2 by
  default; 3, 4 and 8 also available). The stored file is unchanged, and files too large or too deeply nested to
  format open as stored.
- **Autosave external docs**: off by default; on adds another app's document to the library, off uses the viewer's
  **Save to library**.
- **Autosave encrypted docs**: off by default; on does the same for protected PDF/DOCX/ODT/DOC/PPTX, and never stores
  the password.
- **Remember reading position**: on by default; reopens at the saved position and shows Home progress. Stored values
  return if switched back on.
- **Search inside documents**: on by default; builds a background text index. Small files are immediate, larger files
  pause while reading or under battery saver; off stops new indexing but keeps existing matches
  ([ARCHITECTURE.md](ARCHITECTURE.md#content-search)).
- **Crash Reports**: no setting; lists, opens read-only or deletes local ACRA JSON reports.
- **About**: no setting; shows version, release notes, source, Apache 2.0 license and privacy policy.

Limits: [LIMITATIONS.md](LIMITATIONS.md). Features: [FEATURES.md](FEATURES.md).
