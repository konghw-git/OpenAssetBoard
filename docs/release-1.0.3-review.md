# 1.0.3 release review — 2026-10-26

A fix for 1.0.2's countdown widget on the vivo phone. VersionCode is **27**. Database version
**13** and backup format **14** are unchanged.

## What was wrong

On the phone a 4×2 countdown with one countdown drew its card, head band and count over about
the left two thirds of the widget and left the rest empty. The card was given the width and
height of `LocalSize`, and in `SizeMode.Exact` that is the size the launcher reports in the
widget's options; this launcher reports less than it draws. On the emulator (Launcher3) the two
agree, and the Robolectric renders compose at the size they draw, so neither showed it.

## What changed

- `Hero` takes its card's size from a modifier that fills its slot (the whole widget when alone,
  the left part beside the list, the top over it); the reported size only picks the layout and
  sizes the text.
- All four widgets are `SizeMode.Responsive` (`responsive()` in `WidgetKit.kt`): each lists the
  sizes it has layouts for, every one is composed, and on Android 12 and later the launcher's
  own view picks the one that best fits the size it measures. Countdown 14 sizes, assets 15
  (RemoteViews takes at most 16), todo 2 and notes 3, as those only tell compact from not.
  The wide breakpoints are 290 dp, a 4-wide cell on phones being 290–340 dp.
- The asset widget's line is an `ALPHA_8` mask: every size carries its own bitmap in the same
  parcel, under the launcher's bitmap budget; the tint draws it as before (no baseline changed).
- The narrow short asset layout lists the groups that fit, as the wide one does.
- `WidgetShotTest` can tell a widget another size than it draws (`told`): a 370×165 countdown
  told 245×150 and a 370×260 asset widget told 245×230 are baselines now.

## Verification

- Renders: the told countdown fills its card; the told asset widget lays out narrow with its
  groups, filling the card.
- Emulator, debug build: the four widgets placed under 1.0.2 load after the update and draw the
  responsive layouts; the 4×3 asset widget (about 305 dp wide) picks the 290 dp layout; no
  bitmap-budget, Glance or AndroidRuntime errors in the log.
- Gate (`scripts/dev-test.sh --all`, after the version bump): 558 tests, 0 failed; the two told
  renders new and accepted, no other screenshot changed; the confirming run had 146 drawn,
  0 changed, lint passed.
- Not checked: the vivo phone, where the fault was seen.
