# 1.0.2 release review — 2026-10-26

Polish after a day of 1.0.1 on the phone: two widgets laid out badly once stretched, and the
app's cards looked like white slabs next to the note widget's tiles. VersionCode is **26**.
Database version **13** and backup format **14** are unchanged.

## What changed

- **Fitting a cell** (`WidgetKit.kt`): `fontScale`, `lineDp` (1.45 em for Chinese, 1.2 for a line
  of digits), `ems` and `fitSp`/`widthSp`. Text in sp grows with the system's font scale and a
  cell does not, and RemoteViews clips whatever does not fit at the bottom without a trace; on
  the phone the countdown's number and title were cut and, at 1.3 in the renders, the asset
  widget lost its footer with 盘点 at four of eight sizes.
- **Countdown widget**: alone, the card takes the whole widget at every size (1.0.1 gave a wide
  card's right half to "只有这一个"), with + in the head band when there is room; with others it
  sits beside the list when wide or over it when tall (≥ 290 dp). The count is fitted to the room
  left by the head and the date, up to 120 sp on large cards, with 还有 and 天 at a fifth of its
  size beside it; the head and the date grow on large cards too. A tenth of the width is kept
  spare: on the emulator at 1.3 the row's last glyph, 天, came out as an ellipsis.
- **Asset widget**: three layouts by height. One cell (< 150 dp): figure, line and 盘点 side by
  side, the line giving way when the figure would otherwise go to 万. Short: the figure with the
  line beside it, then the groups that fit, the goal and the footer. Tall, once there is room
  for the line and two groups under it (a 4×3 cell is short, as on the emulator the release
  build first showed it there as a bare large line, and four groups say more): the line across the
  card takes the leftover height through a weight, so an estimate that runs long squeezes the
  picture and never the footer, and the groups fill in under it; narrow cards list groups as a
  dot, name and amount, and 盘点 is a round button. The figure switches to 万 only when its
  width runs out. The line is a Fritsch–Carlson monotone curve, and its bitmap is scaled down as
  a whole (under 900 px a side) and stretched back with `ContentScale.FillBounds`.
- **Glass** (`Glass.kt`): light clear glass's body from 50% to 34% white, sheen 40% to 24%, rim
  shade 55% to 78% with the rim's middle stops at 0.6/0.75 of full rather than 0.25/0.4, edge ink
  6% to 4%, shadow 11% to 7%; the light ambient fields ×1.25. Dark clear's rim shade 12% to 18%.
  Deep glass is unchanged. Cards' resting shadows are lower (todo and list cards 6 to 3 dp,
  notes 10 to 4 dp), lifted ones as before.
- **Notes list**: each note is its own card with 8 dp between, as the widget's tiles; the topic
  and time moved to the end of the title line. The shared pane (`PanelPlace`, `panelSlice`) is
  gone. The card's corner is 20 dp.
- **Widget renders** (`WidgetShotTest`, baselines `80-widget-*`): Glance's `compose()` gives the
  RemoteViews a launcher would get; they are inflated and drawn over a wallpaper-like gradient,
  at the sizes people give the widgets and at font scale 1.3. Lists need the launcher's adapter
  and come out empty, so the countdown is drawn alone and the todo and note widgets are checked
  on a device. The pickers' previews for the countdown and asset widgets are cut from these
  renders (`build/review-1.0.2/previews.py`).

## Verification

- Renders at eight sizes (150×150 to 530×450, one cell high, two wide by four high) and two font
  scales were looked at as contact sheets before the subset kept as baselines.
- Emulator (Android 15, Launcher3), debug build: the countdown widget beside its list, the asset
  widget tall with the line across it, todo and notes; then the same at font scale 1.3, where
  the countdown's 天 was cut until the spare width was added. Font scale put back to 1.0.
- Gate (`scripts/dev-test.sh --all`): 558 tests, 0 failed; 124 screenshots changed by the glass
  and the notes cards, looked at in light and dark (notes, todo home, diary, overview, the
  unfolded todo page with a checklist) and accepted with the 14 new widget renders; the
  confirming run had 144 drawn, 0 changed, lint passed.
- The signed release build installed over 1.0.1 on the emulator: the widgets placed under 1.0.1
  redrew in the new layouts, the notes tab shows the new cards, no AndroidRuntime or Glance
  errors in the log.
- Not checked: the vivo phone.
