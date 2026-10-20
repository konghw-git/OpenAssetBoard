# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.0] - 2026-10-21

### Changed

- **A new name and icon: 日盈 · Gain** (日日行动，日日有盈). The icon is a gold sun cut by one
  stroke that ticks and climbs out of it, on deep green; the themed icon follows. Settings › 关于
  shows it with the slogan.
- **Glass on every page.** Pages opened over the tabs (new account and account settings, account
  details, settings and its pages, stocktake, trend, body data, records, note pages and search)
  and the bottom sheets (recording body data, the body profile, cash, topics, extract, the todo
  sheet) sit on the same colour fields, with frosted cards, fields and segmented controls.
- **Account groups are glass cards of their own** on the assets tab; opened, a group takes a wash
  of its colour and each account sits on a pane of its own.
- **Swiping across a todo or note flicks the tabs.** A quick flick over a row turns the page; a
  row only opens its actions when the finger is put down and pushed. An open row still closes
  with any stroke.
- **Checklist items delete on their own**: swipe one left under its todo (or tap × in the
  detail) and only that item turns to dust, with an undo.
- **Undo stays 3 seconds** instead of 5 (plain messages 2.5); an undo before the dust settles
  still brings the row back whole.
- **Two decimals for every percentage** (shares, debt ratio, goal, trend change), and amounts in
  万 keep two decimals where they are read ("1.80万"); under 1万 the full amount shows.
- The todo ring's labels sit together in the middle of each segment, away from its edges.

## [0.8.0] - 2026-10-20

### Added

- **A net worth goal.** Set an amount on the net worth card (tap 设个攒钱目标; "100万" works):
  the card shows a gold bar of how far along it is, what is left, and how long the stocktakes of
  the last half year say it will take. No new card; the goal is backed up (format 7).
- **Updates inside the app.** Settings › 应用更新 checks the GitHub releases (and, on its own,
  once a day while networking is on), downloads the APK, checks that it is this app, newer and
  signed with the same key, backs up, and opens the system installer; data stays as with any
  upgrade. A download mirror can be set for slow networks.
- **Todos sort by hand.** Long press a todo and drag it, in Today, Upcoming, the inbox, someday
  and lists; a long press held still still selects. Rows only trade places with rows of the same
  standing (in Today, overdue and flagged ones keep their groups). The separate sort page is gone.

### Changed

- **Glass on every tab.** The assets and diary tabs (and the diary's calendar and search) get
  the same colour fields and frosted cards as todos and notes.
- **Checklists show in full** under a todo, each item on a strip of its own; the done/total count
  moved onto the title line, next to a small progress ring.
- **The dial's labels face its middle**, turning with their segments, so none crosses the edge.
- Today no longer orders by list first: the hand-set order decides after overdue and flagged.

### Fixed

- The daily card's ⚡ count and 每日 no longer overlap the habit cells.

## [0.7.1] - 2026-10-20

### Fixed

- A todo or note deleted by swipe came back blank after undo, or after a restore from the
  trash: its card stayed hidden once the dust had settled. It now shows again as soon as the
  dust settles or the row returns.

### Changed

- **Pull down to write a note.** The 写笔记 button is gone; pulling the list down past its top
  and letting go opens a new note, in the topic being looked at.
- **Extract a selection in one tap.** While text is selected in a diary entry, a 提炼选中的 N 字
  button sits above the keyboard (the system menu puts app items last).
- **Real glass.** Todo and note cards, chips and buttons are frosted glass over colour fields:
  the page blurred into them (Android 12 and later), a lit rim and a glow inside the top edge.
  Settings › 外观 › 卡片玻璃 picks 清透 (clear) or 深色 (denser), in light and dark themes.

## [0.7.0] - 2026-10-20

### Added

- **Weekly reviews**, hung on the diary's timeline. A week's page shows the week in numbers
  (diary days, daily habits, todos done, change in weight and net worth, worked out on opening),
  its seven diary days, the notes taken from it, and the review text, which saves like a diary
  entry. Every diary day links to its week; Monday and Sunday evening bring a nudge while the
  review is empty; the calendar marks each week. Reviews go to the trash and into backups.
- **Today's note review.** One note left alone the longest (a week at least) comes back to the
  top of the notes list for the day; opening or closing it sends it to the back of the queue.
- **Extract a selection.** Selecting text in a diary entry offers 提炼到笔记 in the selection menu.
  A week's review can be extracted too; such notes link back to the week.
- **Swipe actions on notes and todos.** Left deletes, and the card breaks into dust while the
  rows close up; right reveals pin and topic (notes) or complete, reschedule and the important
  flag (todos). A long swipe runs the first action.
- **Hand-set order for notes.** Long-press a note and drag it; the sort menu also offers last
  updated and last created.
- **All topics in one sheet.** The topic row keeps a fixed button at its end that opens every
  topic in a grid, searchable once there are many; the editor files notes from the same sheet.

### Changed

- Undo and other messages show as a small frosted capsule with a countdown ring instead of a
  full-width bar, so the list stays readable.
- Todo and note cards are translucent glass over a soft colour field.
- A note extracted from the diary always gets a title line, 「10月20日 日记摘录」 unless one is
  typed, so a long paragraph no longer becomes the title.
- Database schema 6 and backup format 6 (note order, weekly reviews); older backups still
  restore, and the confirmation says when that would clear the current weekly reviews.

## [0.6.0] - 2026-10-20

### Added

- **Notes, a fourth tab.** The diary goes by day; notes go by topic: one idea per note, revised
  over time. The first line is the title and `#tags` are highlighted. One level of topics
  (filter, reorder, rename, delete into 未归类), pinning and full-text search. A note opens on
  its own page and saves itself exactly like the diary: one second after typing stops, at most
  4.5 s apart, a conflict becomes a draft, and text that cannot be saved anywhere asks before the
  page is left.
- **Extract from the diary.** A light bulb on a diary day opens its paragraphs: tick the ones
  worth keeping, then save them as a new note (with an optional title) or append them to an
  existing one. The diary itself is not changed. A note lists the days it was taken from and
  takes you back to them; each diary day lists the notes taken from it.
- Notes go to the trash for 30 days like everything else, and unhandled note drafts show on the
  notes tab.

### Changed

- Backups are format 5 (database schema 5) and carry the notes; older backups still restore,
  and the confirmation says when that would clear the current notes.
- Unsaved note text is committed before a restore, as diary text already was.

- **Switches that move instead of blink.** One segmented control everywhere (health metric and
  range, net worth range, account chart range, theme, up/down colours, fee side): the thumb slides
  on a spring, the labels take the picked colour exactly where the thumb covers them, nothing
  shifts sideways, and a pick gives a light haptic tick. The health range picker is a lighter
  variant so the two rows no longer look like one control twice.
- **Charts morph between views.** Switching a metric or a range reshapes the line out of the one
  that was there, with the axis labels and the healthy band handing over; the left-to-right
  draw-in only plays the first time. Applies to the health chart, the net worth chart and account
  charts. Figures above the chart roll to their new values.
- Stepping to the previous or next diary day slides the page in from that side, and the date rolls.

## [0.5.0] - 2026-10-20

### Added

- **Health as an asset.** A health tile on the assets tab and a health page: one weigh-in a day
  (weight required, body fat optional), weight / body fat / BMI lines over 30 days, 90 days, a year
  or everything with a rolling 7-day average, the healthy band and the target behind them; BMI
  grade (WS/T 428-2013), body-fat grade by sex and age on the day (Gallagher 2000), fat and lean
  mass, progress to the target and the scale's nine body types. Days without a weigh-in stay
  empty. A profile (sex, birthday, height, optional target) is stored with the data.
- **Cash at hand.** A tile on the assets tab with the money that can be spent now: counted asset
  accounts minus investments and balances still locked. Tapping it opens the split and every cash
  account.
- **Inline checklists.** A todo's checklist items show as small check points under its card and
  tick in place.

### Changed

- A denser assets tab. Allocation moves into the net worth card as a small ring with a row per
  group, so the total is no longer shown twice. Cash at hand and health sit side by side as two
  tinted tiles; the health tile draws its last 30 weigh-ins (a dashed level while there is only
  one). All accounts live in one card, folded by group to the subtotal, its share and a few
  account tiles; a tap opens a group, the header opens them all. The health shortcut leaves the
  header, so the date line no longer gets cut off.
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

[Unreleased]: https://github.com/konghw-git/OpenAssetBoard/compare/v0.8.0...HEAD
[0.8.0]: https://github.com/konghw-git/OpenAssetBoard/compare/v0.7.1...v0.8.0
[0.7.1]: https://github.com/konghw-git/OpenAssetBoard/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/konghw-git/OpenAssetBoard/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/konghw-git/OpenAssetBoard/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/konghw-git/OpenAssetBoard/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/konghw-git/OpenAssetBoard/releases/tag/v0.4.0
