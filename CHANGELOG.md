# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.5.0] - 2026-10-20

### Added

- **Health as an asset.** A health card on the assets tab and a health page: one weigh-in a day
  (weight required, body fat optional), weight / body fat / BMI lines over 30 days, 90 days, a year
  or everything with a rolling 7-day average, the healthy band and the target behind them; BMI
  grade (WS/T 428-2013), body-fat grade by sex and age on the day (Gallagher 2000), fat and lean
  mass, progress to the target and the scale's nine body types. Days without a weigh-in stay
  empty. A profile (sex, birthday, height, optional target) is stored with the data.
- **Cash at hand.** A card on the assets tab with the money that can be spent now: counted asset
  accounts minus investments and balances still locked.
- **Inline checklists.** A todo's checklist items show as small check points under its card and
  tick in place.

### Changed

- The diary opens for reading on every day; the keyboard comes up only when you tap the page.
- Database schema 4 (explicit migration from 3) and backup format 4; format 1–3 files still restore,
  with the health tables left empty.
- `LineChart` takes a background target line and band that never stretch the y range.

## [0.4.0] - 2026-10-19

First public release. The app is a personal tool and the DB schema and backup format may still change
between minor versions; backup format v3 reads v1 and v2.

### Added

- **Assets.** Net worth overview with allocation donut, batch stocktake with inline editing and
  historical backfill, net worth trend (3 months / 6 months / 1 year / all), investment accounts with
  buys, redemptions, dividends, transfers, repayments and loans, fee attribution, cumulative P&L and a
  market-value / net-invested chart, account groups, icons, include-in-net-worth switch, suspend,
  "locked until" dates and baseline edits.
- **Todo.** Inbox / today / upcoming / someday views on a radial dial, lists with sections, deadlines,
  importance, notes, checklists, one-off and repeating todos with per-occurrence reminders, strong
  reminders (full screen, ring, vibrate), snooze, catch-up after reboot, a missed-reminder banner,
  trash with 30-day retention, and a logbook for completed occurrences.
- **Diary.** One plain-text entry per day with lunar date, solar term, public holidays and make-up
  workdays, the day's weather, the day's habits and a read-only summary of completed todos and the
  stocktake; calendar browsing, full-text search and "this day in past years".
- **Daily habits.** Five habits ticked on a battery next to the todo views and shown read-only at the
  head of the diary.
- **Backup and restore.** One JSON file (`asset-board-backup.json`) in a folder you pick, overwritten
  by every backup and by the daily automatic one; versioned format; whole-database restore with an
  automatic pre-restore copy and a write gate that rejects stale operations.
- **Widgets and shortcuts.** Net worth widget with one-tap stocktake, today's-todos widget with quick
  add, and launcher shortcuts to stocktake, quick capture and the diary.
- **Settings.** Theme, up/down colours, network master switch, weather and holiday-update toggles, a
  reminder permission checklist, all-reminders-as-alarms mode and a nightly diary reminder.
- **Platform.** Foldable two-pane layout, dark mode, Android 10+ (API 29), no Google services required.

### Known limitations

- UI copy is Chinese only. No rate of return (XIRR) yet; no shares × NAV valuation.
- Reminder punctuality depends on the phone's autostart and background permissions; the in-app
  checklist walks through them.

[Unreleased]: https://github.com/konghw-git/OpenAssetBoard/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/konghw-git/OpenAssetBoard/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/konghw-git/OpenAssetBoard/releases/tag/v0.4.0
