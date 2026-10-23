# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.8] - 2026-10-23

### Added

- **Selected todos move as one.** Long-pressing one of several selected todos and dragging it
  carries the others of its group along, folded under it with a count; they land right after it.
  Selected notes drag together the same way, and can be deleted together from the header.
- **Todos join an existing todo's checklist.** The selection's overflow menu adds 并入已有待办的
  检查项 (pick the todo from the view) and 解散检查项, which lets a checklist go as todos of their
  own in the todo's place, ticked items already done. Both can be undone.
- **Undo for a gathering, in the editor.** A todo just made from the selection shows 撤销 in its
  editor for as long as the edit lasts; the snackbar now sits above the keyboard too.
- **Habit rubrics.** Each daily habit can say what counts as done, one condition a line. Holding
  its battery cell floats the rubric beside it, gone when the finger lifts. Backup format 11
  carries the rubrics; database version 10 adds the column.
- **Pull to record** on the food page (记一笔), the health page (称重) and the diary (starts writing).

### Changed

- **Food:** every entry reads "how much · per what", a typed-in guess as "1 份 · 每份 650 kcal"
  like a label's "250 ml · 每 100 ml 40.9 kcal". A right swipe on today's portion takes one more
  portion in place (2 份 → 3 份) instead of adding a second row. 吃了什么 is folded to a line of
  names until opened. Recent foods are a grid of equal cells, two rows until 全部, narrowed by the
  name as it is typed.
- **Health:** the 7-day average is one continuous line, its windows reaching back before the chosen
  period, a lone reading standing for itself; 全部记录 is folded by default; 身体成分, 目标体重 and
  身体类型 take less room, the four body figures in one row.
- **Diary:** mood, daily habits, the stocktake, the week's review and the day's done todos sit on one
  card above the text, with the notes taken from the day and 那年今日 unfolding inside it; the
  habits line up in equal columns.
- **Notes:** 按主题看 is a grid of equal cells, counts aligned down each column.
- **Habits:** a long press no longer gives the day off; rest days already taken still show.
- The selection bar keeps 改期, 移动 and 删除 in view and the rest under ⋮.

## [0.9.7] - 2026-10-23

### Added

- **Todos edited where they sit.** Tapping a todo turns its card into fields for the title and the
  checklist: Enter adds an item, Enter on an empty one ends the edit, Backspace at the start of an
  item joins it upward, several pasted lines become several items. It is written once, when the
  edit ends (完成, Back, another row, the app leaving the screen). Notes, dates and the rest stay
  in the full editor, which a right swipe (编辑, or a full swipe) opens; 完成 left the swipe, the
  check box is a tap away.
- **Selected todos as a checklist.** With todos selected, a new button makes them the checklist of
  one new todo, in screen order, and opens it in place with the title to fill in. The new todo
  shows wherever they all showed (their list, the earliest plan date and deadline); they go to the
  trash, and the undo bar brings them back.
- **A time of day for todos.** 安排时间 picks the day and, optionally, the hour and minute, which
  is also when it reminds; quick add reads 明天下午3点, 周五 9:30, 下周一, 3天后 and the like out of the
  title, highlights them, and 不识别 keeps the text as typed.
- **The diary:** 一键排版 indents paragraphs, sets Chinese punctuation, folds blank lines and keeps
  titles and lists as they are, with an undo; a mood picked from five emoji shows in the day, the
  calendar and the week review.
- **Habit rest days.** A long press on a habit marks today 休: it does not count as missed, and the
  week review shows the rest days.
- **Amounts as sums.** Balances, valuations and flows take 12000+350 and the like, with + − × ÷
  keys under the field and the result beside it.
- **Hide amounts.** The eye next to 净资产 masks every amount on the overview, the trend and the
  widget.
- **Food:** a right swipe on an entry records it again today (再来一份, or 记到今天 from another day).
- **Diagnostics.** Crashes and the system's reasons for past exits are kept on the phone; 设置 ›
  导出诊断 shares them with the version and commit. A failed or stale backup puts a red dot on the
  settings button and a banner in settings.
- Backup format 10 carries the diary mood and habit rest days (schema 9); older backups restore
  without them.

### Changed

- **Food against the resting rate.** The day's intake is now compared with the basal metabolic
  rate alone (Mifflin–St Jeor); the × 1.2 daily burn is gone, since how much a day really burns
  cannot be measured here. The deficit is the one with no exercise at all, so any activity only
  adds to it, and the card shows how much of the resting rate was eaten, in percent.

### Fixed

- A long press on a todo selected it and also opened it; it now only selects.
- The stocktake field dropped the + of a sum.

### Development

- The release script refuses to publish unless versionCode went up, the unit tests (and the
  screenshot baselines, when recorded) and lint pass, and the APK is signed with the release key;
  it uploads the R8 mapping next to the APK. Lint runs against a baseline and fails on new issues;
  debug builds run StrictMode; a real format-10 backup is kept as a test fixture.

## [0.9.6] - 2026-10-23

### Changed

- **A new launcher icon.** The rising chart over a gold coin read as a trading app. The icon is now
  日日行动，日日有盈 drawn: one unbroken line looping three times round, a trefoil knot, with a gold
  sun in the middle, on a deep green lit from the top left. The themed icon keeps the weave, and the
  README's icon and banner and the about card in settings show the new one.

## [0.9.5] - 2026-10-23

### Added

- **Food and energy.** A 饮食 tile on the assets tab and a page of its own: copy a nutrition label
  (energy in kJ or kcal, protein, fat, carbohydrate and sodium per 100 g/ml, then how much you had)
  and it works out the portion in kcal. Most food has no label, so the sheet opens on one number,
  a guessed kcal, with − and + for how many (two eggs, half a bowl); every nutrient is optional
  and folded away, and folds up again. Energy
  left empty is estimated from the three nutrients, and a label whose energy and nutrients disagree
  is pointed out. Anything eaten before refills the form in one tap. Each day shows its intake
  against a burn estimated from the profile and that day's weight (Mifflin–St Jeor × 1.2), the
  deficit, the nutrients and the last seven days. It records; there are no targets.
- Backup format 9 carries the food entries (schema 8); older backups restore with none.

### Changed

- **Cash at hand moved into the net worth card.** Cash, the goal and the debt ratio now sit side by
  side as three small tiles instead of three stacked bars, and the allocation takes one line a
  group; tapping cash still opens the cash accounts. The food tile takes its place beside health.
- **Slimmer rows.** Account rows, group headers, todo cards and food entries take less height,
  with smaller tiles and less padding.
- The health and food pages keep one record button, on the hero card; the app bar no longer repeats it.

## [0.9.4] - 2026-10-21

### Fixed

- **Backups never break each other.** The automatic, manual and pre-install backups write the same
  file; run together, they could delete each other's side files and leave no complete copy. They
  now run one at a time, each with side files of its own, and a failed run leaves either the old
  backup or the complete new one, named in the error.
- **A weekly review's diary days wait for its last save too**, as going back and stepping weeks do.
- **清空资产历史 starts the trend today**, with a stocktake of the balances it keeps; it used to wait
  for the next manual one. A valuation dated later than today no longer passes for today's balance.
- **Restoring an older backup says what it will drop**: custom daily habits (before format 8) and
  the net worth goal (before format 7). `docs/backup-format.md` describes format 8.
- **A restore refuses** two stocktakes at one instant, two valuations of one account at one instant,
  and a note or weekly review marked deleted without its trash batch, instead of failing midway or
  breaking the trash afterwards.
- **A flow at the very instant of a valuation is refused**: the valuation already holds that day's
  flows, so the flow could never move the balance. The valuation form says so.
- **A reminder whose channel is switched off** is listed as missed instead of being marked delivered.
  The diary reminder checks its channel too.
- **Ringing checks the todo first**: a todo completed, edited or restored away no longer rings, and a
  boot-time strong reminder taken over by its alarm page is no longer cut short five minutes in.
- **Updates**: cancelling a download no longer turns into “download failed”; the installer opens
  only while the app is in front and reports when it cannot; an APK without signatures is refused;
  a mirror without a trailing slash works; with the network off, nothing is downloaded.
- **Body fat**: the 7-day average no longer breaks at a day weighed without body fat, and women
  18–39 read as standard up to 33%, as in Gallagher et al. (2000).
- The diary calendar shows Gregorian festivals (国庆节, 元旦 …) as well as traditional ones.
- An account locked until a day is unlocked on that day, as its page says.
- The account chart forgets the picked point when its range changes.
- The amount typed into an account row survives a rotation; a new account cannot be saved twice by
  a double tap; the diary cannot be deleted while its text cannot be saved.
- The alarm page's clock ticks.
- A holiday file with a bad date is refused before it replaces the table, and the current year is
  refreshed along with the next.
- A leave that cannot finish says why: a restore in progress, or typing still being saved.
- Message collectors no longer pile up when the diary, notes or weekly review switch days.
- The test suite no longer fails at random: every test now waits for its background work to stop
  before closing its database.

- **Leaving a note or a weekly review waits for its last save.** Going back, stepping to another
  week or following a note's diary source used to leave at once; if that save and its draft both
  failed, the text was gone with the page. Now the page stays until the text is saved or in a
  draft, and asks (offering to copy it) when neither worked, as the diary already did.
- **Typing on while a save conflict is written into a draft is kept.** The diary, notes and weekly
  reviews ended the session with a draft of the text as it was when the save failed; whatever was
  typed while that draft was written disappeared with the reload. The draft now holds it all.
- **Deleting a todo right after editing it keeps the edit.** The title and notes typed just before
  went nowhere, so the trash and undo brought back the older text.
- **A restore checks the daily habits' dates and names** before it replaces anything: a backup with
  a malformed date was taken in and then broke the diary and the weekly review.
- **An alarm the system delivers more than a day late is listed as missed**, as one found on
  restart is, instead of notifying or ringing for something long past.
- **A failed backup before installing an update stops the install** and says why, with 重试备份
  and 不备份，仍然安装 to choose from, instead of installing without the promised backup.
- Two places read app state in a way the screen did not follow (lint errors): the alarm screen's
  theme and a new note's first focus.

## [0.9.3] - 2026-10-21

### Added

- **Settings → 数据初始化**, two ways to start over, each saving the current data as the
  pre-restore copy first:
  - **清空资产历史** keeps the groups, the accounts and their current balances and drops every
    earlier valuation, stocktake and flow: today becomes each account's baseline, the trend
    starts today and an investment's profit starts at zero. Everything outside the ledger stays.
  - **彻底清空** removes all data, back to a fresh install. Settings and the backup folder stay.

### Fixed

- **A new install no longer offers the author's own accounts.** The welcome screen's main button
  imported fifteen accounts with real balances; it now only sets up the five empty groups.
  Anyone who imported them and has since entered their own balances can use 清空资产历史 to drop
  the numbers they started from.

## [0.9.2] - 2026-10-21

### Added

- **Daily habits are yours to set.** The battery's 每日 pill opens them: rename one, give it
  another icon or color, move it up or down, add new ones (up to seven) or take one off. A new
  habit counts from the day it is added and a removed one stays in the days it was part of, so
  the diary and the weekly review show each day with the habits it had. Backups carry them;
  older backups bring back the five built in.
- **Pull the assets list down for a new account**, as the + in the header does.
- **Slide along the bottom bar** to turn the tabs: the pages follow the finger, a tab's width per
  page, and settle on the nearest tab, or the next one on a flick.
- A slim scroll bar on the right edge of every scrolling page and sheet, while it moves and for a
  moment after.

### Changed

- A todo's checklist hangs from its check circle as a thread: each item is a node on a line
  running down from it, filling with the primary color as items are done. The column that was
  empty under the circle now carries the line.

### Removed

- The tab handles on the screen edges. A swipe on a row is the row's and anywhere else turns the
  tabs.

### Fixed

- A swipe on a row could also drag the pages behind it, and the two would fight, shaking the
  screen. The pager takes back a stroke as soon as one event of it goes unclaimed, with all the
  distance since the touch; rows now hold on to every event of a stroke they have taken, the
  still and the purely vertical ones too, until the finger lifts.
- Leaving the diary while typing (by the bar, say) ends the typing first; before, the tabs
  stayed unswipeable afterwards.

## [0.9.1] - 2026-10-21

### Added

- **Pull the todo list down for a new todo**, as the hub's + does: the field opens under the
  dial. While it is open (or while selecting or dragging) the list just stops at its top.
- **Account rows swipe.** In an open group, left turns an account to dust and deletes it, or
  disables it once it has records (it moves to 已停用); both undo. Right offers 改余额 (市值,
  欠款), and a full swipe or a tap turns the row itself into the amount field, the old amount
  selected, keyboard up; saving records it now, with an undo.
- **Tab handles**: short slivers halfway down the left and right edges of the todo and notes
  tabs. A swipe that starts on one turns the tabs, even over a row; they light up while the
  pages move. The system back gesture gives way there.

### Changed

- **A swipe on a row is the row's**, fast or slow; 0.9.0 sent quick flicks to the tabs. The tabs
  turn from the handles and from anywhere that is not a row.
- The diary's stocktake strip scrolls with the page instead of staying pinned over the text.

### Fixed

- The allocation legend on the net worth card no longer cuts group names short: each name sits
  over its amount, the share beside them.

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
