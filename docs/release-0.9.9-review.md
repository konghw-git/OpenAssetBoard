# 0.9.9 release review — 2026-10-24

Reviewed the uncommitted changes after `4df93f9`, including weekly statistics, account editing,
todo/list gestures, early reminders, database migration, backups and the development test runner.
Release version: **0.9.9**, versionCode **19**, database **11**, backup format **12**.

## Findings fixed before release

| Finding | Correction | Regression evidence |
|---|---|---|
| Cancelling a drag after arming a merge still merged the todo. | Cancellation clears the merge target; stationary long-press selection keeps its existing behavior. | `TodoNestTest.aCancelledDragDoesNotMerge` |
| Selected companions folded to zero height blocked the visible merge target above the dragged todo. | Find the preceding visible, nonzero-height row. | `TodoNestTest.selectedRowsMergeIntoTheVisibleRowAbove` |
| Tomorrow on a reminder delivered the previous day could immediately ring again. | If tomorrow's early reminder would already be past, schedule a one-time reminder at tomorrow's event time. | `ReminderAdvanceTest.tomorrowAfterAnEarlyReminderStaysInTheFuture`, covering one-day and midnight-crossing advances |
| A deadline plus early-reminder label could be truncated on a narrow card with enlarged text. | Wrap metadata chips while retaining date-before-reminder order. | `TodoMetaTest.deadlineAndEarlyReminderRemainReadableOnANarrowPhone`, native font rendering at 150% font size |

Each regression was confirmed to fail with the corresponding original implementation and pass
after correction. English and Chinese README descriptions are synchronized.

## Verification

- Full suite: **501 discovered, 500 passed, 0 failed, 1 intentionally skipped**. The skipped
  `BackupFixtureTest.writeTheCurrentFormat` only runs when explicitly generating a frozen fixture.
- Release lint: no new errors or warnings; the existing baseline remains unchanged.
- Signed, minified release APK builds successfully. Certificate SHA-256:
  `150609abe6d702ca6c254d3b413dd51c361884b9367461ab40f2c1a756d6f95d`.
- On an Android 15 / API 35 emulator, installed the published 0.9.8 APK, restored the synthetic
  format-11 fixture, exported a backup, installed 0.9.9 with `adb install -r`, and exported again.
  `scripts/compare-backups.py` reports the ledger identical; health/food records also match.
- Other backup tables and fields also match after accounting for the new `remindBefore=0`
  defaults and one October 21 reminder being marked missed on launch. The UI showed that missed
  reminder, consistent with the existing catch-up policy. Formats changed from 11/10 to 12/11
  (backup/database).
- Actual reminder delivery: event at **02:13**, advance **5 minutes**, exact alarm scheduled for
  **02:08** and delivered with notification text **“02:13 开始 · 收件箱”**. Opened the notification,
  checked all advance choices, and selected a different advance in the detail UI.
- Emulator gestures: holding Child and sliding right displayed the highlighted Parent and merge
  hint; release produced Parent's Child checklist item. A full left swipe archived the list.
  Automated Compose tests also cover reorder, cancellation, multiselection and archive undo.
- Phone and wide layouts were inspected. With user approval, the 49 reviewed screenshot changes
  were accepted after preserving the previous local baselines. Changes include the intended UI
  updates and current-date text. Baselines remain ignored by git.

Local logs, original baselines, comparison images, emulator captures and synthetic before/after
backups are retained under `build/review-0.9.9/` (ignored by git).

No physical phone was connected. OEM background restrictions, deep-doze punctuality and subjective
touch feel on a foldable phone still require device testing; the emulator checks do not establish
those properties.
