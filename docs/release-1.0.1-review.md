# 1.0.1 release review — 2026-10-26

The home-screen widgets, rebuilt from scratch, and countdowns as a new kind of data. VersionCode is
**25**. Database version **13** (migration 12→13 adds an empty `countdowns` table) and backup
format **14** (`countdowns`, empty when an older file is read).

## What changed

- **Widgets** (`widget/`, Glance 1.1.1): 今天待办, 笔记, 倒数日 and 资产看板, on glass drawn as
  layer-list drawables with night variants, each with layouts by size (`SizeMode.Exact`). Data is
  collected as flows inside `provideContent`, so a write in the app redraws a live session;
  `WidgetRefresh` coalesces redraws per widget within 350 ms (a note autosaving while typed).
  A tick is drawn at once from `PendingTicks` and then written through the gate with the row's
  token and the epoch it was drawn in. `WidgetDay` redraws every widget just after midnight
  (inexact alarm, rescheduled on boot, time and zone changes).
- **Editing over the home screen**: `WidgetSheetActivity`, a transparent singleTask activity with
  a glass bottom sheet (`ui/widget/WidgetSheets.kt`): add a todo; rename, complete, flag, push to
  tomorrow or delete one; write a note with `NoteSession` autosave; add, edit or delete a
  countdown; place widgets with `requestPinAppWidget`. The old `QuickAddActivity` is gone.
- **Receivers** keep the class names `TodoWidgetReceiver` and `NetWorthWidgetReceiver`, so widgets
  placed under 1.0.0 load after the update instead of showing "can't load".
- **Countdowns** (`domain/Countdowns.kt`, `data/CountdownRepository.kt`): a title, a date, yearly
  or not (29 February falls on the 28th in other years), at most one pinned for the large figure.
- **Settings**: 倒数日 and 桌面小组件 rows under 待办与日记.
- **R8 keep rule** (`app/proguard-rules.pro`): Glance instantiates an `ActionCallback` by its class
  name through the empty constructor, which R8 removed; on the release build a tap on a widget's
  circle or eye logged `NoSuchMethodException: ToggleTodoAction.<init> []` and did nothing. 1.0.0's
  `CompleteTodoAction` had the same fault. Debug builds are not minified, so only the release APK
  showed it.

Reference: the layout and interaction of open-source widget and note apps found with `gh search`
(rounded glass cards, a tick target separate from the title, a sheet for edits rather than
opening the app).

## Verification

- Gate (`scripts/dev-test.sh --all`, after the version bump): 556 tests, 0 failed (new: countdown
  domain and repository, migration 12→13, backup format 14 and 13 fixtures). Two screenshots
  changed and were reviewed, then accepted: `10-settings` and `59-settings-backup-problem`, where
  the two new rows lengthen the 待办与日记 card; the second also had its error time move from
  19:33 to 19:30, which is what the pinned clock gives (an hour before 20:30) and which the old
  baseline predates. The confirming run: 130 drawn, 0 changed, lint passed; run again after the
  keep rule with the same result.
- Upgrade in place on the Android 15 emulator: the installed signed 1.0.0 with the last review's
  data (backup exported under 1.0.0), the signed 1.0.1 installed with `adb install -r`, opened and
  exported again: format 14, schema 13, `countdowns` 0, and `scripts/compare-backups.py` reports
  the ledger identical (groups 2, accounts 2, snapshots 1, valuations 4, snapshotItems 2,
  flows 1). No AndroidRuntime errors. The first export attempt ran under a "System UI isn't
  responding" dialog after the cold boot and pulled the older file both times; it was redone.
- The minified build on the home screen: 资产看板 and 今天待办 placed from Settings → 桌面小组件 with
  the picker's preview; the asset widget draws the ledger, sparkline and group bars; a tick on the
  todo widget completes the row (after the keep rule; before it, nothing), the eye hides and shows
  amounts, and undoing an older completion of a repeating todo whose source moved on shows the
  toast pointing to the app, as designed.
- The debug build was walked through every widget and sheet before (add, rename, complete and
  undo, delete; note editing and save on dismiss; countdown add and edit; resize; dark mode).
- Not checked: the vivo phone and its launcher.
