# 0.9.12 release review — 2026-10-25

Bookkeeping, a rebuilt todo drag and two fixes from feedback on 0.9.11. VersionCode is **22**.
Database version **12** (was 11) and backup format **13** (was 12): flows gain a nullable
`category` column, `MIGRATION_11_12` adds it, and format 12 backups still restore.

## What changed

- 记账: the assets tab's pull opens a bill sheet (支出 or 收入, its own keypad with + and −,
  categories, account and day guessed from the last bill, 再记 to stay open). A bill is an
  `EXPENSE` flow out of one account or an `INCOME` flow into one, so balances move through the
  existing ledger rules and a later stocktake still wins. A card on the assets tab, a 账单 page by
  month, category and day, and 记一笔 on account pages. Create, edit and delete all undo.
- Todo drag: Today is in the hand-set order only (overdue and important rows no longer float to
  the top, so the order shifts once on upgrade); the drag offset follows the finger against the
  live layout; edge scrolling speeds up with nearness and dwell and refuses a move that would push
  the held row off screen, which used to dispose it mid-drag; merging into the row above is a right
  slide only, never a hover; a row dragged past a section title in a list joins that section.
- Checklist drag: the other items make room and the drop point shows a dashed slot, in place of
  an insertion line.
- Diary: the 已排版 notice lasts four seconds with a countdown, replaces rather than queues, a
  repeated tap offers the undo again, and it goes once undo is impossible (typing, another day,
  leaving the tab).

## Verification

- Full run with screenshot verification and lint: 540 tests, 0 failed.
- `ModuleScreenshotTest.todoSwipeAndDust` failed without `--shots` on 0.9.11's HEAD as well:
  Robolectric draws only when something captures, so the card's graphics layer was never recorded
  and the dust's `toImageBitmap()` threw on a zero size. The test now draws once
  (`captureToImage()`) before the swipe and passes with and without shots. No app change.
- Screenshot baselines were re-recorded across the whole suite; the previous set is under
  `build/review-0.9.12/screenshots-before/`. 151 baselines, 148 changed (dates in the demo data
  plus the new card, order and notices), none appeared or vanished apart from the skipped brand
  banner, copied over unchanged. The overview, pull hint, Today order, diary notice and dust shots
  were inspected.
- Upgrade in place on the Android 15 emulator (`asset-board-review-api35`): 0.9.11 with the
  previous review's data exported (format 12, schema 11); the signed 0.9.12 went on with
  `adb install -r`, opened to the same net worth (6,050.50) and exported as format 13, schema 12.
  `scripts/compare-backups.py` reports the ledger identical (groups 2, accounts 2, snapshots 1,
  valuations 4, snapshotItems 2, flows 0). No crash in logcat.
- On that emulator a finger-like pull armed 松手记一笔 and opened the sheet; 35 on 餐饮 saved to
  the largest cash account, the net worth went to 6,015.50 (较上次盘点 -35.00), the card showed
  本月支出 35.00 and the undo capsule came up; the export carries the bill as
  `EXPENSE … 3500 food`. A single straight `adb shell input swipe` does not arm the pull (it flings
  past it), as with the 0.9.11 pull. Captures, exports and scripts are under
  `build/review-0.9.12/device/`.
- Not covered on a device: the todo and checklist drags and the diary notice, which rest on the
  Compose UI tests (`TodoNestTest`, `ChecklistDragTest`, `DiaryTidyNoticeTest`). No physical phone
  was connected.
