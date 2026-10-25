# 1.0.0 release review — 2026-10-26

A look-and-feel release asked for as one large step: the net worth card, todo entries, the diary's
day card and editor, and the notes list and editor. VersionCode is **24**. Database version **12**
and backup format **13** are unchanged; nothing in `data/` or `domain/` moved.

## What changed

- **Net worth card** (`auroraSurface` in `Chrome.kt`, `Aurora` in `Theme.kt`): warm clear glass in
  place of the dark green gradient. Four fields (amber, coral, persimmon, rose) drift on a 16 s loop
  whose laps are whole numbers, so it closes without a seam; a spark runs round the rim every 7 s,
  seen through a thin ring clipped between the outline and an inset copy; one shine pass on
  arrival. All of it is read in the draw phase, so the card's content does not recompose per frame.
  `OnAurora` swaps the hero ink colours for the card's, so the shared hero pieces inside read dark
  on light glass and light on dark. The trend page's headline uses the same surface. Other heroes
  (health, food, bills, week, settings) keep the green gradient.
- **Text centring**: every Material text style now carries
  `LineHeightStyle(Center, Trim.None)`. The default splits a line's extra height by the font's
  ascent and descent; with a phone font whose metrics differ from the emulator's, Chinese sat off
  the middle of rows. Every screenshot moved a pixel or two because of it.
- **Todo entries**: `todoTitleStyle` / `checkItemStyle` (16/22 sp medium, 14.5/20 sp) shared by the
  card, the in-place editor and a list's name, so starting an edit no longer shifts the text.
  Checklist items lost their grey strips; the in-place editor marks its item fields with a hairline.
  A list's card has a gradient icon tile and its count in a pill. On a todo's page the handle and
  delete are dimmer and the add row has a plus in the circles' column.
- **Diary day card**: habits are tags flowing after the count (the italic serif face and the
  three-column grid are gone, the "在待办页打勾" hint moved into the lock's description), the three
  figures share one row parted by hairlines with the note beside the figure, the dashed rules are
  gradient hairlines. The card is about a quarter shorter on the review data.
- **Editors**: the diary and note text sit on the page with no sheet; a 3 dp accent bar grows down
  the margin while focused. Body 17/31 sp (diary) and 17/30 sp (note); a note's title 24 sp.
- **Notes list**: a section's notes are one glass pane (`panelSlice`): each row draws the pane's
  whole outline extended past its own edges and keeps its slice, so fills, rims and shadow meet
  without seams or doubling; rows after the first draw an inset hairline. The list's spacing went to
  zero and the other items carry their own bottom padding. A lifted note becomes a card; a selected
  one gets an inset rounded ring. The drafts notice and the note page's topic button are glass.
- **Fixed**: the dark theme's `outline` (#4B5853) was used for secondary text and was barely
  visible on dark glass; it is #7D8C85 now.

Reference for the list and editor: Notesnook's note row (title one line, preview two, one small
line of meta, no card per row) and frameless journal editors such as Day One's.

## Verification

- Gate (`scripts/dev-test.sh --all`, after the version bump): 541 tests, 0 failed; 71 screenshots
  changed on purpose and were reviewed (light and dark overview and trend, narrow layout, todo home,
  list, detail, unfolded, in-place edit, drag; diary, diary dark, typing with a selection; notes
  light and dark, swiped, selected, note page), then accepted; the confirming run had 130 drawn,
  0 changed, lint passed. A lint error on the first run (suspicious indentation where the hero was
  split in two) was fixed.
- Upgrade in place on the Android 15 emulator: the installed 0.9.13 with the last review's data
  exported, the signed 1.0.0 (release certificate checked) installed with `adb install -r`, opened
  and exported again. `scripts/compare-backups.py` reports the ledger identical (groups 2,
  accounts 2, snapshots 1, valuations 4, snapshotItems 2, flows 1). No AndroidRuntime errors.
- The minified build on the emulator: overview (warm card with real data, the change chip in the
  loss colour), todo, diary and notes tabs all render as in the screenshots.
- The emulator's qemu process crashed once (SIGSEGV on the host, swiftshader) right after the todo
  tab was captured; after a cold boot the same walk through every tab ran without it. An earlier
  review's emulator log has a crash too. Not seen on a phone yet.
- Not checked: the vivo phone itself, where the centring change is meant to show.
