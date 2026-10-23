# 0.9.10 integration review — 2026-10-24

This release integrates the separately developed UI refresh with direct todo merging and movable
checklists. VersionCode is **20**. Database version **11** and backup format **12** are unchanged.

## Drag behavior

- Hold a todo's title and rest over another open todo for about 450 ms. The target highlights;
  release to merge into its checklist. A quick vertical drop still reorders. The existing
  right-slide shortcut and multi-selection remain available.
- Hold a checklist item's text to reorder it or move it between another todo's items. The
  insertion line and drag preview identify its destination. The list scrolls at its edges.
- Detail-page drag handles provide sorting without taking over text editing.
- Moves preserve item identity, text and checked state, run in one WriteGate transaction, and
  offer undo. Revision checks prevent undo from overwriting subsequent edits. Cancellation and
  a drop in the original position leave the checklist unchanged.

The gesture is owned by the page so a checklist item can scroll off screen while being dragged.
After pickup, pointer changes are consumed before the child list's scroll detector; a large
first movement therefore cannot steal the gesture.

## UI integration

Reviewed the theme, glass rendering, motion, charts, overview and health/food presentation changes.
The overview separates net worth, allocation and accounts. The light/dark palette and restrained
motion apply across the app. Narrow-screen and large-font amount rendering is covered by the
new presentation tests. Amount interpolation starts with the actual value and handles interrupted
updates. No ledger calculations or persistence format changed as part of the UI refresh.

## Verification

- The first integrated full run passed: **516 cases, 515 passed, one intentional fixture-writer
  skip**. An additional todo edge-scroll regression was then added and passed separately. The
  final publisher reruns the complete inventory with screenshot verification.
- Gesture tests cover direct nonadjacent merges, quick sorting, both kinds of edge scrolling,
  same-parent checklist sorting, cross-parent insertion, detail handles, cancellation and undo.
- Repository tests verify preserved IDs/ticks, both checklist orders after undo, no-op drops
  with sparse sort orders, rejected invalid destinations and stale-undo protection.
- Release lint and the signed, minified build passed. Existing lint baselines are unchanged.
- On an Android 15 emulator, the published 0.9.9 APK restored a synthetic fixture and exported
  it. The integrated 0.9.10 APK was installed with `adb install -r`; the next export matched
  **every field except exportedAt**, including all ledger, todo, diary and health data.
- Actual touch input reordered A1 after A3, moved the checked A2 between B1 and B2 in another
  todo, and merged Loose task into the nonadjacent Parent C. A fresh export confirmed the
  resulting order, unchanged IDs and checked states, and the merged source in the trash.
- Phone, dark-mode and wide layouts were inspected. Old local screenshot baselines were backed
  up before accepting the reviewed UI changes; baselines remain ignored by git.
- Fixed an existing health-chart screenshot race by waiting for the metric control before
  switching it. The common page screenshot helper also waits for loading indicators to disappear.
  The swipe/dust screenshot waits for the actual burst and committed deletion before settling
  surrounding rows. Tests that drag a parent now target the title, separately from checklist items.

Logs, before/after backups and emulator captures are retained under `build/review-0.9.10/`.
The release-script verification mode intentionally skips the fixture writer, branding banner
and wide todo screenshot; the two visual cases are rendered in the full comparison run and
reviewed separately. No physical phone was connected for this session.
