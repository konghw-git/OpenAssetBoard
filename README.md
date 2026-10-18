# asset-board

「强者之路」: a personal Android app with three tabs. **Assets** is a balance-sheet dashboard (net
worth, allocation, periodic stocktakes, investment flows with cumulative P&L). **Todo** is a
Things-style task manager (inbox / today / upcoming / someday / logbook, lists with sections,
repeats, reliable reminders, a home-screen widget), plus five daily habits checked off on a
battery next to the views. **Diary** is one plain-text entry per day with
the lunar calendar, holidays and weather attached, and the day's habits shown read-only at its head. Everything stays on the phone; the backup is one
JSON file in a folder you pick (`asset-board-backup.json`), overwritten by every backup.

Design notes and the calculation rules live in the Obsidian notes
`2026-10-18 资产看板 App - 需求对齐与数据模型.md` (assets; section 9 is mirrored by `LedgerTest.kt`
and `RepositoryTest.kt`) and `2026-10-19 资产看板 App - 待办与日记需求对齐与数据模型.md` (todo, diary,
trash, backup v2; section 11 is mirrored by `TodoViewsTest`, `TodoRepositoryTest`, `DiaryTest`,
`RestoreTest`, `MigrationTest` and `AppShellTest`).

## Build

```bash
scripts/setup-toolchain.sh          # JDK 21, Android SDK 36, Gradle; accepts the SDK licenses
scripts/create-keystore.sh          # release key in ~/.config/asset-board (back it up!)
source ~/.local/share/dev-dl/android-env.sh

./gradlew testDebugUnitTest         # calculation, repository, restore and migration rules
./gradlew testDebugUnitTest --tests '*Screenshot*' -Proborazzi.test.record=true   # build/screenshots/
./gradlew assembleRelease           # app/build/outputs/apk/release/app-release.apk
```

Behind a proxy, pass it to Gradle explicitly, e.g.
`./gradlew -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 assembleRelease`.

## Install and upgrade

Always install the **release** build and upgrade in place (`adb install -r`, or open the new APK on
the phone). The debug build uses a separate package (`com.assetboard.debug`) so it can never replace
the real install. Never sign with a different key: Android would refuse the upgrade, and uninstalling
wipes the local database.

Before upgrading, export a backup in the old version and copy `asset-board-backup.json` aside as
`before.json` (the next backup overwrites it); after `adb install -r`, export again and run
`scripts/compare-backups.py before.json asset-board-backup.json`: the ledger part must be identical.

## Layout

| Path | What |
|---|---|
| `domain/Ledger.kt` | Pure calculation rules: balance, net invested, P&L, stocktake totals |
| `domain/LedgerData.kt` | In-memory copy of the database with indexes |
| `domain/Rules.kt` | Constraints Room cannot express |
| `data/LedgerRepository.kt` | Every write: validation, sign handling, one transaction each |
| `data/BackupManager.kt` | JSON export (format 2, reads 1), validation, the restore coordinator, pre-restore copies |
| `data/WriteGate.kt` | The read-write gate every write passes, and the data epoch bound at each operation's origin |
| `data/TodoRepository.kt` | Todo writes: tokens, complete / skip / undo, repeat conversion, trash batches |
| `data/DiaryRepository.kt`, `DiarySession.kt` | Serial diary writes, versioned saves, drafts, autosave timing |
| `data/Migrations.kt` | The explicit 1 → 2 migration; it only adds tables |
| `domain/todo/` | Pure rules: views and ordering, recurrence, reminder state, invariants |
| `domain/calendar/` | Lunar calendar, solar terms, festivals, the official workday schedule |
| `reminder/` | Alarms, notifications, the strong-reminder ring service and full-screen alarm, boot catch-up |
| `ui/` | Compose screens; `ui/components` holds the charts and shared widgets |
| `widget/` | Glance widgets: net worth, and today's todos with quick add |
