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
import androidx.compose.ui.unit.TextUnit
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

@Composable
private fun AssetContent(b: Board, hide: Boolean, upIsRed: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    val wide = size.width >= 250.dp
    val tall = size.height >= 200.dp
    val amount: (String) -> String = { if (hide) MASKED else it }
    val gain = if (upIsRed) WColors.gainRed else WColors.gainGreen
    val loss = if (upIsRed) WColors.gainGreen else WColors.gainRed

    WidgetFrame(background = R.drawable.widget_aurora, padding = if (wide) 16.dp else 13.dp, onClick = appAction(context, MainActivity.OPEN_ASSETS)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(GlanceModifier.defaultWeight()) {
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
                Figure(b.netWorth, hide, compact = !wide)
                b.change?.let { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!hide && c != 0L) WIcon(if (c > 0) R.drawable.ic_w_trend_up else R.drawable.ic_w_trend_down, if (c > 0) gain else loss, 13.dp, GlanceModifier.padding(end = 3.dp))
                        WText(if (c == 0L) "与上次盘点持平" else amount(Money.format(c, signed = true)), 12.sp, if (hide || c == 0L) WColors.auroraMuted else if (c > 0) gain else loss, bold = c != 0L)
                        if (wide && c != 0L) WText(" 较上次盘点", 11.sp, WColors.auroraMuted)
                    }
                }
            }
            if (wide && b.line.size >= 2) {
                val w = minOf(size.width * 0.36f, 170.dp)
                Image(
                    ImageProvider(sparkline(context, b.line, w, 46.dp)), "净资产走势",
                    GlanceModifier.width(w).height(46.dp),
                    colorFilter = ColorFilter.tint(WColors.flame),
                )
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        if (tall && wide && b.groups.isNotEmpty()) {
            Groups(b, amount)
            Spacer(GlanceModifier.defaultWeight())
        }
        if (wide && b.goal != null && b.goal > 0) {
            val p = (b.netWorth.toDouble() / b.goal).coerceIn(0.0, 1.0).toFloat()
            Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                WText("目标 ${amount(Money.short(b.goal))}", 10.sp, WColors.auroraMuted, modifier = GlanceModifier.width(96.dp))
                LinearProgressIndicator(p, GlanceModifier.defaultWeight().height(4.dp).cornerRadius(2.dp), color = WColors.flame, backgroundColor = WColors.auroraTrack)
                WText(" " + amount("${(p * 100).toInt()}%"), 10.sp, WColors.auroraMuted, bold = true)
            }
        }
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                if (wide) WText("资产 ${amount(Money.short(b.assets))}  ·  负债 ${amount(Money.short(b.debts))}", 11.sp, WColors.auroraInk, medium = true)
                WText(b.lastStocktakeAt?.let { "上次盘点 " + Dates.relative(it) } ?: "还没有盘点", 10.sp, WColors.auroraMuted)
            }
            Row(
                GlanceModifier.background(ImageProvider(R.drawable.widget_btn_flame)).cornerRadius(16.dp)
                    .clickable(appAction(context, MainActivity.OPEN_STOCKTAKE)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WIcon(R.drawable.ic_w_stocktake, WColors.white, 14.dp, GlanceModifier.padding(end = 4.dp))
                WText("盘点", 12.sp, WColors.white, bold = true)
            }
        }
    }
}

/** The figure: a small ¥ and the amount, smaller as it grows longer. */
@Composable
private fun Figure(cents: Long, hide: Boolean, compact: Boolean) {
    val text = when {
        hide -> MASKED
        compact && Money.format(cents).length > 10 -> Money.short(cents)
        else -> Money.format(cents)
    }
    val size: TextUnit = when {
        text.length <= 9 -> if (compact) 26.sp else 30.sp
        text.length <= 12 -> if (compact) 22.sp else 27.sp
        else -> if (compact) 19.sp else 23.sp
    }
    Row(verticalAlignment = Alignment.Bottom) {
        WText("¥", 15.sp, WColors.flame, bold = true, modifier = GlanceModifier.padding(bottom = 4.dp, end = 2.dp))
        WText(text, size, WColors.auroraFigure, bold = true)
    }
}

/** Up to four groups: a coloured bar for each one's share of what is counted. */
@Composable
private fun Groups(b: Board, amount: (String) -> String) {
    val total = b.groups.sumOf { abs(it.second) }.coerceAtLeast(1)
    Column(GlanceModifier.fillMaxWidth()) {
        for ((name, value, index) in b.groups.take(4)) {
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                WText(name, 11.sp, WColors.auroraInk, medium = true, modifier = GlanceModifier.width(64.dp))
                LinearProgressIndicator(
                    (abs(value).toDouble() / total).toFloat(),
                    GlanceModifier.defaultWeight().height(6.dp).cornerRadius(3.dp),
                    color = WColors.group(index),
                    backgroundColor = WColors.auroraTrack,
                )
                WText(amount(Money.short(value)), 11.sp, WColors.auroraMuted, modifier = GlanceModifier.width(72.dp).padding(start = 8.dp))
            }
        }
    }
}

/**
 * The net worth line as a white picture the widget tints: a line over a fill fading down, and a
 * dot on today. White, so one picture serves light and dark. Kept under 900 px a side: every
 * bitmap crosses to the launcher in a parcel.
 */
internal fun sparkline(context: Context, values: List<Long>, width: Dp, height: Dp): Bitmap {
    val density = context.resources.displayMetrics.density
    val w = (width.value * density).toInt().coerceIn(2, 900)
    val h = (height.value * density).toInt().coerceIn(2, 900)
    val bmp = createBitmap(w, h)
    val c = Canvas(bmp)
    val min = values.min()
    val max = values.max()
    // Padded so a flat line sits in the middle and the stroke never touches the edge.
    val span = (max - min).toDouble().takeIf { it > 0 } ?: 1.0
    val pad = 5f * density
    val usable = h - 2 * pad
    fun x(i: Int) = pad + (w - 2 * pad) * i / (values.size - 1).toFloat()
    fun y(v: Long) = if (max == min) h / 2f else (pad + usable * (1 - ((v - min) / span))).toFloat()
    val line = Path().apply {
        moveTo(x(0), y(values[0]))
        for (i in 1 until values.size) {
            // A gentle curve between points rather than a jagged polyline.
            val cx = (x(i - 1) + x(i)) / 2
            cubicTo(cx, y(values[i - 1]), cx, y(values[i]), x(i), y(values[i]))
        }
    }
    val fill = Path(line).apply {
        lineTo(x(values.size - 1), h.toFloat())
        lineTo(x(0), h.toFloat())
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
    val ex = x(values.size - 1)
    val ey = y(values.last())
    c.drawCircle(ex, ey, 4.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF })
    c.drawCircle(ex, ey, 2.6f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
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
