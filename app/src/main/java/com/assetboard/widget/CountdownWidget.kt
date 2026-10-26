package com.assetboard.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.TextAlign
import com.assetboard.R
import com.assetboard.WidgetSheetActivity
import com.assetboard.data.Countdown
import com.assetboard.domain.AppClock
import com.assetboard.domain.CountdownState
import com.assetboard.domain.Countdowns
import com.assetboard.domain.todo.TodoViews
import java.time.LocalDate
import kotlin.math.abs

/**
 * Days to the days that matter. The first (the pinned one, else the nearest) is a card with a
 * coloured head and the count large; given room, the rest follow in a list beside it. Any of
 * them opens for editing over the home screen.
 */
class CountdownWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.container.countdowns
        val initial = runCatching { repo.all() }.getOrDefault(emptyList())
        provideContent {
            val live by remember { repo.observe() }.collectAsState(initial)
            CountdownContent(live)
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { CountdownWidget().updateAll(context) }
        }
    }
}

@Composable
private fun CountdownContent(items: List<Countdown>) {
    val context = LocalContext.current
    val size = LocalSize.current
    val today = TodoViews.localDate(AppClock.millis())
    val states = Countdowns.ordered(items, today)
    val add = sheetAction(context, WidgetSheetActivity.COUNTDOWN_ADD)
    val wide = size.width >= 250.dp

    WidgetFrame(background = R.drawable.widget_glass_warm, padding = 0.dp) {
        if (!wide) {
            Hero(states.firstOrNull(), today, GlanceModifier.fillMaxSize(), big = size.height >= 150.dp, band = R.drawable.widget_band_flame)
            return@WidgetFrame
        }
        Row(GlanceModifier.fillMaxSize()) {
            val heroWidth: Dp = minOf(size.width * 0.44f, 180.dp)
            // Beside the list, the head is rounded on the outer corner only.
            Hero(states.firstOrNull(), today, GlanceModifier.width(heroWidth).fillMaxHeight(), big = size.height >= 150.dp, band = R.drawable.widget_band_flame_start)
            Column(GlanceModifier.defaultWeight().fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight().clickable(sheetAction(context, WidgetSheetActivity.COUNTDOWN_LIST))) {
                        WText("倒数日", 15.sp, WColors.ink, bold = true)
                        WText(if (states.isEmpty()) "记下重要的日子" else "共 ${states.size} 个", 10.sp, WColors.muted)
                    }
                    RoundButton(R.drawable.ic_w_add, "添加倒数日", R.drawable.widget_btn_flame, add, size = 28.dp, iconSize = 16.dp)
                }
                Spacer(GlanceModifier.height(4.dp))
                val rest = states.drop(1)
                if (rest.isEmpty()) {
                    EmptyState(
                        if (states.isEmpty()) "还没有倒数日" else "只有这一个",
                        if (states.isEmpty()) "生日、纪念日、考试" else "点 + 再记一个",
                        GlanceModifier.fillMaxSize(),
                        onClick = add,
                    )
                } else {
                    LazyColumn(GlanceModifier.fillMaxSize()) {
                        items(rest.take(30), itemId = { it.countdown.id.hashCode().toLong() and 0x7FFFFFFFL }) { s -> CountdownRow(s, today) }
                    }
                }
            }
        }
    }
}

/** The card: the title in a coloured head, the count large, the date under it. */
@Composable
private fun Hero(s: CountdownState?, today: LocalDate, modifier: GlanceModifier, big: Boolean, band: Int) {
    val context = LocalContext.current
    val open = if (s == null) sheetAction(context, WidgetSheetActivity.COUNTDOWN_ADD) else sheetAction(context, WidgetSheetActivity.COUNTDOWN_EDIT, s.countdown.id)
    Column(modifier.clickable(open)) {
        Box(
            GlanceModifier.fillMaxWidth().height(if (big) 38.dp else 32.dp).background(ImageProvider(band)).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val head = when {
                s == null -> "倒数日"
                s.isToday -> s.countdown.title + " · 今天"
                else -> s.countdown.title + " " + s.lead
            }
            WText(head, if (big) 14.sp else 13.sp, WColors.white, bold = true, align = TextAlign.Center)
        }
        Column(
            GlanceModifier.fillMaxWidth().defaultWeight().padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (s == null) {
                WText("+", 40.sp, WColors.flame, bold = true)
                WText("记下第一个日子", 12.sp, WColors.muted)
            } else if (s.isToday) {
                WText("🎉", if (big) 44.sp else 34.sp, WColors.ink, align = TextAlign.Center)
                WText("就是今天", 13.sp, WColors.flame, bold = true)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    val n = abs(s.days).toString()
                    val numberSize = when {
                        n.length >= 5 -> if (big) 36.sp else 30.sp
                        n.length == 4 -> if (big) 44.sp else 36.sp
                        else -> if (big) 56.sp else 44.sp
                    }
                    WText(n, numberSize, if (s.passed) WColors.muted else WColors.ink, bold = true)
                    WText("天", 13.sp, WColors.muted, modifier = GlanceModifier.padding(start = 3.dp, bottom = if (big) 10.dp else 7.dp))
                }
            }
        }
        if (s != null) {
            WText(
                Countdowns.subtitle(s, today), 11.sp, WColors.faint, align = TextAlign.Center,
                modifier = GlanceModifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = if (big) 12.dp else 9.dp),
            )
        }
    }
}

@Composable
private fun CountdownRow(s: CountdownState, today: LocalDate) {
    val context = LocalContext.current
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 5.dp).clickable(sheetAction(context, WidgetSheetActivity.COUNTDOWN_EDIT, s.countdown.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            WText(s.countdown.title, 13.sp, WColors.ink, medium = true)
            WText(Countdowns.subtitle(s, today), 10.sp, WColors.faint)
        }
        when {
            s.isToday -> Chip("今天", WColors.flame, R.drawable.widget_chip_flame)
            s.passed -> Chip("已 ${-s.days} 天", WColors.muted, R.drawable.widget_chip_muted, bold = false)
            else -> Row(verticalAlignment = Alignment.Bottom) {
                WText("${s.days}", 17.sp, WColors.flame, bold = true)
                WText("天", 10.sp, WColors.muted, modifier = GlanceModifier.padding(start = 2.dp, bottom = 2.dp))
            }
        }
    }
}

class CountdownWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CountdownWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetDay.schedule(context)
    }
}
