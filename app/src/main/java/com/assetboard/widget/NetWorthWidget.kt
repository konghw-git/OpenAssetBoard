package com.assetboard.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.core.graphics.createBitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import com.assetboard.MainActivity
import com.assetboard.R
import com.assetboard.domain.Dates
import com.assetboard.domain.LedgerData
import com.assetboard.domain.Money
import com.assetboard.ui.components.MASKED
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The assets on the home screen, on the warm glass of the app's net worth card: the figure, what
 * it did since the last stocktake, its line over the stocktakes, and with room the groups that
 * make it up. The eye hides every amount, here and in the app alike; 盘点 starts one.
 */
class NetWorthWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.container
        val initial = runCatching { container.repository.loadOnce() }.getOrNull()
        val initialGoal = runCatching { container.goal.observe().first() }.getOrNull()
        provideContent {
            val live by container.repository.ledger.collectAsState()
            val hide by container.settings.hideAmounts.collectAsState()
            val upIsRed by container.settings.upIsRed.collectAsState()
            val goal by remember { container.goal.observe() }.collectAsState(initialGoal)
            val ledger = live ?: initial
            if (ledger == null) {
                WidgetFrame(background = R.drawable.widget_aurora) { EmptyState("资产看板", "正在读取…") }
            } else {
                AssetContent(Board.of(ledger, goal), hide, upIsRed)
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { NetWorthWidget().updateAll(context) }
        }
    }
}

/** What the widget shows, worked out from the ledger once per drawing. */
internal data class Board(
    val netWorth: Long,
    val assets: Long,
    /** Positive. */
    val debts: Long,
    /** Since the last stocktake; null without one. */
    val change: Long?,
    val lastStocktakeAt: Long?,
    /** Net worth at each stocktake, oldest first, ending with today's. */
    val line: List<Long>,
    /** The groups' current totals (debts positive) with their index for colour, largest first. */
    val groups: List<Triple<String, Long, Int>>,
    val goal: Long?,
) {
    companion object {
        fun of(l: LedgerData, goal: Long?): Board {
            val t = l.currentTotals()
            val series = l.netWorthSeries()
            val byGroup = mutableMapOf<String, Long>()
            for (a in l.countedAccounts()) byGroup[a.groupId] = (byGroup[a.groupId] ?: 0L) + l.toDisplay(a.id, l.currentBalance(a.id))
            val groups = l.groups.mapIndexedNotNull { i, g -> byGroup[g.id]?.takeIf { it != 0L }?.let { Triple(g.name, it, i) } }
                .sortedByDescending { abs(it.second) }
            return Board(
                netWorth = t.netWorth,
                assets = t.assets,
                debts = -t.liabilities,
                change = series.lastOrNull()?.let { t.netWorth - it.netWorth },
                lastStocktakeAt = series.lastOrNull()?.snapshot?.at,
                line = series.takeLast(23).map { it.netWorth } + t.netWorth,
                groups = groups,
                goal = goal,
            )
        }
    }
}

/**
 * Laid out by the cell it is given, with the lines budgeted at the system's font scale so nothing
 * falls off the bottom (RemoteViews clips what does not fit without a trace, 盘点 first).
 * Slim (one cell high): the figure, the line and 盘点 side by side. Short: the figure with the
 * line beside it when wide, then the groups that fit, the goal, the footer. Tall: the line gets a
 * row of its own across the card and takes whatever height is left, so an estimate that runs
 * long squeezes the picture, never the footer; nothing is spread out over blank space.
 */
@Composable
internal fun AssetContent(b: Board, hide: Boolean, upIsRed: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    val fs = context.fontScale
    val wide = size.width >= 250.dp
    val pad = if (wide) 16.dp else 13.dp
    val innerW = size.width - pad * 2
    val avail = size.height - pad * 2
    val amount: (String) -> String = { if (hide) MASKED else it }
    val chartable = b.line.size >= 2
    val hasGoal = b.goal != null && b.goal > 0
    val chart: @Composable (Dp, Dp, GlanceModifier) -> Unit = { w, h, modifier ->
        Image(
            ImageProvider(sparkline(context, b.line, w, h)), "净资产走势", modifier,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(WColors.flame),
        )
    }

    val labelH = 24.dp
    val changeH = lineDp(12f, fs)
    val goalH = lineDp(10f, fs) + 6.dp
    val footerH = if (wide) maxOf(12.dp + lineDp(12f, fs), lineDp(11f, fs) + lineDp(10f, fs)) else maxOf(34.dp, lineDp(10f, fs) + lineDp(11f, fs))
    val rowH = lineDp(11f, fs) + 6.dp
    val yen = (15f * fs * 0.62f).dp + 2.dp

    val tallFig = figure(b.netWorth, hide, innerW - yen, 200.dp, if (wide) 34f else 28f, fs)
    val tallFixed = labelH + lineDp(tallFig.second, fs, latin = true) + (if (b.change != null) changeH else 0.dp) + 10.dp +
        (if (hasGoal) goalH else 0.dp) + footerH + 6.dp
    val space = avail - tallFixed
    // The line across the card only with room for two groups under it as well: short of that,
    // the short layout's groups say more than a bigger line would.
    val tall = space >= (if (chartable) 72.dp + (if (b.groups.size >= 2) rowH * 2 + 8.dp else 0.dp) else rowH * 2)

    WidgetFrame(background = R.drawable.widget_aurora, padding = pad, onClick = appAction(context, MainActivity.OPEN_ASSETS)) {
        when {
            size.height < 150.dp -> {
                val pillW = if (wide) (42f + 24f * fs).dp else 34.dp
                val lastLine = lineDp(10f, fs)
                val withLast = avail - labelH - lastLine >= lineDp(20f, fs, latin = true)
                val figFor = { sparkW: Dp -> figure(b.netWorth, hide, innerW - yen - pillW - (if (sparkW > 0.dp) sparkW + 16.dp else 8.dp), avail - labelH - (if (withLast) lastLine else 0.dp), if (wide) 30f else 26f, fs) }
                // The figure in full comes before the line: the line gives way when they do not both fit.
                var sparkW = if (wide && chartable) minOf(innerW * 0.3f, 130.dp) else 0.dp
                var fig = figFor(sparkW)
                if (sparkW > 0.dp && fig.first != figFor(0.dp).first) {
                    sparkW = 0.dp
                    fig = figFor(0.dp)
                }
                Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) {
                        Header(b, hide, upIsRed, fig, showChange = false, wide = wide)
                        if (withLast) WText(b.lastStocktakeAt?.let { "上次盘点 " + Dates.relative(it) } ?: "还没有盘点", 10.sp, WColors.auroraMuted)
                    }
                    if (sparkW > 0.dp) {
                        val sparkH = minOf(40.dp, avail - 8.dp)
                        chart(sparkW, sparkH, GlanceModifier.width(sparkW).height(sparkH).padding(horizontal = 8.dp))
                    }
                    StocktakeButton(wide)
                }
            }
            tall -> {
                val rows = (((space - (if (chartable) 72.dp else 0.dp) - 8.dp) / rowH).toInt()).coerceIn(0, minOf(5, b.groups.size))
                Column { Header(b, hide, upIsRed, tallFig, showChange = b.change != null, wide = wide) }
                Spacer(GlanceModifier.height(10.dp))
                if (chartable) {
                    chart(innerW, space - (if (rows > 0) rowH * rows + 8.dp else 0.dp), GlanceModifier.fillMaxWidth().defaultWeight())
                }
                if (rows > 0) {
                    Spacer(GlanceModifier.height(8.dp))
                    Groups(b, rows, wide, fs, amount)
                }
                Spacer(GlanceModifier.height(4.dp))
                if (!chartable) Spacer(GlanceModifier.defaultWeight())
                if (hasGoal) Goal(b, amount)
                Footer(b, wide, amount)
            }
            else -> {
                var rest = avail - labelH - footerH
                val showChange = b.change != null && rest - changeH >= lineDp(20f, fs, latin = true)
                if (showChange) rest -= changeH
                val showGoal = wide && hasGoal && rest - goalH >= lineDp(20f, fs, latin = true)
                if (showGoal) rest -= goalH
                val sparkW = if (wide && chartable) minOf(innerW * 0.36f, 170.dp) else 0.dp
                val fig = figure(b.netWorth, hide, innerW - yen - (if (sparkW > 0.dp) sparkW + 8.dp else 0.dp), rest, if (wide) 30f else 26f, fs)
                val left = rest - lineDp(fig.second, fs, latin = true)
                val rows = if (wide) ((left - 10.dp) / rowH).toInt().coerceIn(0, minOf(4, b.groups.size)) else 0
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(GlanceModifier.defaultWeight()) { Header(b, hide, upIsRed, fig, showChange, wide) }
                    if (sparkW > 0.dp) {
                        val sparkH = minOf(46.dp, labelH + lineDp(fig.second, fs, latin = true) - 4.dp)
                        chart(sparkW, sparkH, GlanceModifier.width(sparkW).height(sparkH))
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                if (rows > 0) {
                    Groups(b, rows, wide, fs, amount)
                    Spacer(GlanceModifier.defaultWeight())
                }
                if (showGoal) Goal(b, amount)
                Footer(b, wide, amount)
            }
        }
    }
}

/** The figure's text and size for [width] by [height]: in full while its width allows it large, else in 万. */
private fun figure(cents: Long, hide: Boolean, width: Dp, height: Dp, max: Float, fs: Float): Pair<String, Float> {
    if (hide) return MASKED to fitSp(MASKED, width, height, max, fs)
    val full = Money.format(cents)
    val short = Money.short(cents)
    val text = if (widthSp(full, width, fs) >= max * 0.75f || short == full) full else short
    return text to fitSp(text, width, height, max, fs)
}

/** 净资产 with the eye, the figure, and what it did since the last stocktake. */
@Composable
private fun Header(b: Board, hide: Boolean, upIsRed: Boolean, fig: Pair<String, Float>, showChange: Boolean, wide: Boolean) {
    val gain = if (upIsRed) WColors.gainRed else WColors.gainGreen
    val loss = if (upIsRed) WColors.gainGreen else WColors.gainRed
    Row(verticalAlignment = Alignment.CenterVertically) {
        WText("净资产", 12.sp, WColors.auroraMuted, medium = true)
        // The eye: a bigger target than the icon.
        Box(
            GlanceModifier.padding(start = 4.dp).size(24.dp).cornerRadius(12.dp).clickable(actionRunCallback<ToggleHideAction>()),
            contentAlignment = Alignment.Center,
        ) {
            WIcon(if (hide) R.drawable.ic_w_eye_off else R.drawable.ic_w_eye, WColors.auroraMuted, 14.dp, description = if (hide) "显示金额" else "隐藏金额")
        }
    }
    Row(verticalAlignment = Alignment.Bottom) {
        WText("¥", 15.sp, WColors.flame, bold = true, modifier = GlanceModifier.padding(bottom = (fig.second * 0.12f).dp, end = 2.dp))
        WText(fig.first, fig.second.sp, WColors.auroraFigure, bold = true)
    }
    val c = b.change
    if (showChange && c != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!hide && c != 0L) WIcon(if (c > 0) R.drawable.ic_w_trend_up else R.drawable.ic_w_trend_down, if (c > 0) gain else loss, 13.dp, GlanceModifier.padding(end = 3.dp))
            WText(
                if (c == 0L) "与上次盘点持平" else if (hide) MASKED else Money.format(c, signed = true),
                12.sp, if (hide || c == 0L) WColors.auroraMuted else if (c > 0) gain else loss, bold = c != 0L,
            )
            if (wide && c != 0L) WText(" 较上次盘点", 11.sp, WColors.auroraMuted)
        }
    }
}

@Composable
private fun Goal(b: Board, amount: (String) -> String) {
    val goal = b.goal ?: return
    val p = (b.netWorth.toDouble() / goal).coerceIn(0.0, 1.0).toFloat()
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        WText("目标 ${amount(Money.short(goal))}", 10.sp, WColors.auroraMuted, modifier = GlanceModifier.padding(end = 8.dp))
        LinearProgressIndicator(p, GlanceModifier.defaultWeight().height(4.dp).cornerRadius(2.dp), color = WColors.flame, backgroundColor = WColors.auroraTrack)
        WText(" " + amount("${(p * 100).toInt()}%"), 10.sp, WColors.auroraMuted, bold = true)
    }
}

/** Assets and debts with when the last stocktake was, and 盘点. */
@Composable
private fun Footer(b: Board, wide: Boolean, amount: (String) -> String) {
    val last = b.lastStocktakeAt?.let { Dates.relative(it) }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            if (wide) {
                WText("资产 ${amount(Money.short(b.assets))}  ·  负债 ${amount(Money.short(b.debts))}", 11.sp, WColors.auroraInk, medium = true)
                WText(last?.let { "上次盘点 $it" } ?: "还没有盘点", 10.sp, WColors.auroraMuted)
            } else {
                WText(if (last == null) "还没有" else "上次盘点", 10.sp, WColors.auroraMuted)
                WText(last ?: "盘点过", 11.sp, WColors.auroraInk, medium = true)
            }
        }
        StocktakeButton(wide)
    }
}

/** 盘点: a pill when wide, a round button when narrow. */
@Composable
private fun StocktakeButton(wide: Boolean) {
    val stocktake = appAction(LocalContext.current, MainActivity.OPEN_STOCKTAKE)
    if (wide) {
        Row(
            GlanceModifier.background(ImageProvider(R.drawable.widget_btn_flame)).cornerRadius(16.dp)
                .clickable(stocktake).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WIcon(R.drawable.ic_w_stocktake, WColors.white, 14.dp, GlanceModifier.padding(end = 4.dp))
            WText("盘点", 12.sp, WColors.white, bold = true)
        }
    } else {
        RoundButton(R.drawable.ic_w_stocktake, "盘点", R.drawable.widget_btn_flame, stocktake, size = 34.dp, iconSize = 17.dp)
    }
}

/** The first [rows] groups: a coloured bar for each one's share when wide, a dot and the amount when narrow. */
@Composable
private fun Groups(b: Board, rows: Int, wide: Boolean, fs: Float, amount: (String) -> String) {
    val total = b.groups.sumOf { abs(it.second) }.coerceAtLeast(1)
    val nameW = (11f * fs * 4 + 6).dp.coerceIn(56.dp, 84.dp)
    val valueW = (11f * fs * 5 + 10).dp.coerceIn(60.dp, 90.dp)
    Column(GlanceModifier.fillMaxWidth()) {
        for ((name, value, index) in b.groups.take(rows)) {
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (wide) {
                    WText(name, 11.sp, WColors.auroraInk, medium = true, modifier = GlanceModifier.width(nameW))
                    LinearProgressIndicator(
                        (abs(value).toDouble() / total).toFloat(),
                        GlanceModifier.defaultWeight().height(6.dp).cornerRadius(3.dp),
                        color = WColors.group(index),
                        backgroundColor = WColors.auroraTrack,
                    )
                    WText(amount(Money.short(value)), 11.sp, WColors.auroraMuted, modifier = GlanceModifier.width(valueW).padding(start = 8.dp))
                } else {
                    Box(GlanceModifier.size(6.dp).cornerRadius(3.dp).background(WColors.group(index))) {}
                    WText(name, 11.sp, WColors.auroraInk, medium = true, modifier = GlanceModifier.defaultWeight().padding(start = 6.dp))
                    WText(amount(Money.short(value)), 11.sp, WColors.auroraMuted)
                }
            }
        }
    }
}

/**
 * The net worth line as a white picture the widget tints: a line over a fill fading down, and a
 * dot on today. White, so one picture serves light and dark. Kept under 900 px a side, scaled
 * down whole so it stretches back without distortion: every bitmap crosses to the launcher in a
 * parcel. The curve is monotone between points, so a jump does not overshoot into a hook.
 */
internal fun sparkline(context: Context, values: List<Long>, width: Dp, height: Dp): Bitmap {
    val wDp = width.value.coerceAtLeast(1f)
    val hDp = height.value.coerceAtLeast(1f)
    val density = minOf(context.resources.displayMetrics.density, 900f / wDp, 900f / hDp)
    val w = (wDp * density).toInt().coerceAtLeast(2)
    val h = (hDp * density).toInt().coerceAtLeast(2)
    val bmp = createBitmap(w, h)
    val c = Canvas(bmp)
    val min = values.min()
    val max = values.max()
    // Padded so a flat line sits in the middle and the stroke never touches the edge.
    val span = (max - min).toDouble().takeIf { it > 0 } ?: 1.0
    val pad = 5f * density
    val usable = h - 2 * pad
    val n = values.size
    val xs = FloatArray(n) { pad + (w - 2 * pad) * it / (n - 1).toFloat() }
    val ys = FloatArray(n) { if (max == min) h / 2f else (pad + usable * (1 - ((values[it] - min) / span))).toFloat() }
    // Fritsch–Carlson tangents: each segment's slope, averaged at a point unless it turns there.
    val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
    val m = FloatArray(n) { i ->
        when (i) {
            0 -> d[0]
            n - 1 -> d[n - 2]
            else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2
        }
    }
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f; m[i + 1] = 0f
        } else {
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]
            }
        }
    }
    val line = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val dx = (xs[i + 1] - xs[i]) / 3
            cubicTo(xs[i] + dx, ys[i] + m[i] * dx, xs[i + 1] - dx, ys[i + 1] - m[i + 1] * dx, xs[i + 1], ys[i + 1])
        }
    }
    val fill = Path(line).apply {
        lineTo(xs[n - 1], h.toFloat())
        lineTo(xs[0], h.toFloat())
        close()
    }
    c.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    })
    c.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    })
    c.drawCircle(xs[n - 1], ys[n - 1], 4.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF })
    c.drawCircle(xs[n - 1], ys[n - 1], 2.6f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
    return bmp
}

/** The eye on the widget: the same switch as the overview's, so the app hides amounts too. */
class ToggleHideAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val settings = context.container.settings
        settings.setBool(settings.hideAmounts, !settings.hideAmounts.value)
        runCatching { NetWorthWidget().update(context, glanceId) }
    }
}

class NetWorthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NetWorthWidget()
}
