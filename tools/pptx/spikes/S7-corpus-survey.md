# S7 Corpus survey

Ranked prevalence of charts, SmartArt, EMF/EMF+, WordArt, vertical text, embedded fonts, and Aptos in the local PPTX corpus. Re-run with `tools/pptx/survey_corpus.py <corpus_dir> --markdown tools/pptx/spikes/S7-corpus-survey.md` after fetching a corpus (e.g. `tools/pptx/fetch-corpus.sh`).

## Ranked table

Corpus decks surveyed: 0

| Rank | Feature | Decks | Share | Milestone note |
| --- | --- | ---: | ---: | --- |
| 1 | Aptos | 0 | n/a | M5/M6 |
| 2 | Charts | 0 | n/a | M5 |
| 3 | EMF/WMF | 0 | n/a | M5 |
| 4 | EMF+ only (subset of EMF) | 0 | n/a | M5/049 |
| 5 | Embedded fonts | 0 | n/a | M5/M6 |
| 6 | SmartArt (cached drawings) | 0 | n/a | M5 |
| 7 | Vertical text | 0 | n/a | M5/M6 |
| 8 | WordArt | 0 | n/a | M5/M6 |

## M5/M6 ordering

- No local corpus was available at survey time (0 decks). M5 ordering remains as planned pending a corpus run: charts step 1, SmartArt cached drawings, OLE/media fallbacks, EMF/WMF GDI subset, SVG-pictures fallback, pattern fills.
- Decision #8: keep M5 chart priority unless a corpus run shows charts in most decks, in which case pull Tier A charts before EMF.
- Decision #7 (EMF scope): the S7 survey found 0 EMF+ only decks in 0 decks surveyed; no local corpus was available. Keep the GDI subset only; no EMF+ renderer. Dual files render from GDI records and ignore EMF+ comments; EMF+ only files get a labelled placeholder. Revisit if a corpus run finds EMF+ only decks.
