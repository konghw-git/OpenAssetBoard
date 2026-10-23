# 0.9.11 release review — 2026-10-24

A polish release on 0.9.10's UI refresh, from two rounds of feedback on the installed build.
VersionCode is **21**. Database version **11** and backup format **12** are unchanged: no table,
column or backup field moved, so no migration or fixture was needed.

## What changed

- Overview: the food and health tiles sit between the net-worth hero and the allocation card;
  the hidden-amounts eye masks their figures too; the five account groups are compact rows
  (34 dp tile, count and share on one line, the share as a thin bar along the header's bottom).
- Todo: tapping a checklist item's text edits it in place; ticking a todo ticks its checklist
  and unticking restores it; the right swipe shows 改期 and 重要 only, the full swipe right opens
  the editor and arms a short push past the open actions (about half the width); list cards
  delete on a left swipe and archive on a right one, as every other row deletes.
- Settings: 外观 first, 关于 last, cards cascade in; download mirrors come preset (GitHub and six
  mainland accelerators) with a one-tap speed test that picks the fastest.
- Motion: the net worth counts up on first show, the hero and about cards tilt under the finger,
  the allocation ring spins in, a ring bursts from a ticked checkbox.
- Undo capsules on the four tab pages clear the floating tab bar (`TabPageBottomInset`); every
  deletion goes in dust, including trash purge/restore, health records, food entries deleted
  from the sheet, sections and selected notes.

## Verification

- Round 2 full run: 517 tests, 0 failed, 149 screenshots rendered and inspected (overview order,
  compact groups, settings order). Round 3 full run: 3 failures, all assertions of the behaviour
  that was changed on purpose (list swipe sides, todo right-swipe contents); the tests were
  rewritten and the two classes rerun green (18 tests). The threshold change reran the four
  swipe classes green (27 tests).
- Screenshot baselines were re-recorded for this UI; the previous set is kept under
  `build/review-0.9.11/screenshots-before/`. Of 150 baselines, 148 changed (the tab bar, card
  padding and motion affect nearly every page), 2 are identical, none appeared or vanished apart
  from the skipped brand banner, copied over unchanged.
- The recording run (517 tests) failed once in `NoteScreenshotTest.drafts`: the click on the
  drafts banner ran before the banner was in the merged tree. It had passed in both full runs
  earlier in the day; the test now waits for the banner's text first, and the class reran green.
- `scripts/publish-release.sh` runs the full suite with screenshot verification, lint against its
  baseline, the signed minified build and the certificate check before tagging; its log is kept
  under `build/review-0.9.11/`.
- The mirror speed test was exercised against the test container only, not live hosts: the probe
  is a ranged GET with 6 s timeouts and a failed host reads 不可用. No phone was connected; the
  user installed the pre-release build of this UI and the feedback rounds came from it.
