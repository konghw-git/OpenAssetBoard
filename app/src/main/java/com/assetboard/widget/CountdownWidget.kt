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
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
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
 * coloured head and the count large; given room, the rest follow in a list beside it when wide
 * or under it when tall. Alone, the card takes the whole widget at any size, the count sized to
 * what it is given. Any of them opens for editing over the home screen.
 */
class CountdownWidget : GlanceAppWidget() {
    override val sizeMode = responsive(
        110 to 110, 160 to 160, 160 to 220, 160 to 300, 250 to 110, 250 to 160, 250 to 220, 250 to 300,
        290 to 160, 290 to 220, 290 to 300, 420 to 200, 420 to 300, 420 to 400,
    )

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
internal fun CountdownContent(items: List<Countdown>) {
    val context = LocalContext.current
    val size = LocalSize.current
    val today = TodoViews.localDate(AppClock.millis())
    val states = Countdowns.ordered(items, today)
    val add = sheetAction(context, WidgetSheetActivity.COUNTDOWN_ADD)
    val rest = states.drop(1)

    WidgetFrame(background = R.drawable.widget_glass_warm, padding = 0.dp) {
        when {
            // Beside the list: the head rounded on the outer corner only.
            rest.isNotEmpty() && size.width >= 250.dp -> Row(GlanceModifier.fillMaxSize()) {
                val heroW = (size.width * 0.42f).coerceIn(140.dp, 210.dp)
                Hero(states.first(), today, heroW, size.height, R.drawable.widget_band_flame_start, add = null, GlanceModifier.width(heroW).fillMaxHeight())
                Column(GlanceModifier.defaultWeight().fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(GlanceModifier.defaultWeight().clickable(sheetAction(context, WidgetSheetActivity.COUNTDOWN_LIST))) {
                            WText("倒数日", 15.sp, WColors.ink, bold = true)
                            WText("共 ${states.size} 个", 10.sp, WColors.muted)
                        }
                        RoundButton(R.drawable.ic_w_add, "添加倒数日", R.drawable.widget_btn_flame, add, size = 28.dp, iconSize = 16.dp)
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    Rows(rest, today)
                }
            }
            // Over the list, when tall and narrow.
            rest.isNotEmpty() && size.height >= 290.dp -> Column(GlanceModifier.fillMaxSize()) {
                val heroH = (size.height * 0.5f).coerceIn(150.dp, 230.dp)
                Hero(states.first(), today, size.width, heroH, R.drawable.widget_band_flame, add, GlanceModifier.fillMaxWidth().height(heroH))
                Column(GlanceModifier.fillMaxWidth().defaultWeight().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)) { Rows(rest, today) }
            }
            else -> Hero(states.firstOrNull(), today, size.width, size.height, R.drawable.widget_band_flame, add.takeIf { size.width >= 160.dp }, GlanceModifier.fillMaxSize())
        }
    }
}

@Composable
private fun Rows(rest: List<CountdownState>, today: LocalDate) {
    LazyColumn(GlanceModifier.fillMaxSize()) {
        items(rest.take(20), itemId = { it.countdown.id.hashCode().toLong() and 0x7FFFFFFFL }) { s -> CountdownRow(s, today) }
    }
}

/**
 * The card: the title in a coloured head (with + when [add] is given), how many days large, the
 * date under it. The count is sized to the room left by the head and the date at the system's
 * font scale, so a long count or a large font shrinks it rather than cutting it short.
 *
 * [width] and [height] are what the launcher says the card has, and only size the text: the card
 * itself fills what it is given through [modifier]. Launchers do not all report the size they
 * draw (a vivo one gave a 4×2 cell about two thirds of its width), and a card set to the
 * reported size left the rest of the widget empty.
 */
@Composable
private fun Hero(s: CountdownState?, today: LocalDate, width: Dp, height: Dp, band: Int, add: Action?, modifier: GlanceModifier) {
    val context = LocalContext.current
    val fs = context.fontScale
    val open = if (s == null) sheetAction(context, WidgetSheetActivity.COUNTDOWN_ADD) else sheetAction(context, WidgetSheetActivity.COUNTDOWN_EDIT, s.countdown.id)
    val big = height >= 150.dp
    val huge = height >= 260.dp && width >= 200.dp
    val headSp = if (huge) 16f else if (big) 14f else 13f
    val dateSp = if (huge) 13f else 11f
    val bandH = maxOf(if (huge) 44.dp else if (big) 38.dp else 32.dp, lineDp(headSp, fs) + 8.dp)
    val dateH = if (s != null) lineDp(dateSp, fs) + (if (big) 12.dp else 9.dp) else 0.dp
    val area = height - bandH - dateH - 8.dp
    // The count grows with the card; a large one would look lost at the small card's size.
    val maxSp = if (huge) (area.value * 0.5f).coerceIn(72f, 120f) else if (big) 72f else 56f
    val bodyW = width - 20.dp
    Column(modifier.clickable(open)) {
        Row(
            GlanceModifier.fillMaxWidth().height(bandH).background(ImageProvider(band)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // As wide as the button, so the title stays in the middle.
            if (add != null) Spacer(GlanceModifier.width(26.dp))
            WText(
                if (s == null) "倒数日" else s.countdown.title, headSp.sp, WColors.white, bold = true, align = TextAlign.Center,
                modifier = GlanceModifier.defaultWeight().padding(horizontal = 2.dp),
            )
            if (add != null) RoundButton(R.drawable.ic_w_add, "添加倒数日", R.drawable.widget_btn_on_flame, add, size = 26.dp, iconSize = 15.dp)
        }
        Column(
            GlanceModifier.fillMaxWidth().defaultWeight().padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                s == null -> {
                    WText("+", fitSp("+", bodyW, area - lineDp(12f, fs), 48f, fs).sp, WColors.flame, bold = true)
                    WText("记下第一个日子", 12.sp, WColors.muted)
                }
                s.isToday -> {
                    WText("🎉", fitSp("🎉", bodyW, area - lineDp(13f, fs), 48f, fs).sp, WColors.ink, align = TextAlign.Center)
                    WText("就是今天", 13.sp, WColors.flame, bold = true)
                }
                else -> Row(verticalAlignment = Alignment.Bottom) {
                    val n = abs(s.days).toString()
                    // 还有 before the count and 天 after it, a fifth of its size within bounds.
                    val sides = { side: Float -> ((side * fs * 2 + 4) + (side * fs + 3)).dp }
                    val first = fitSp(n, bodyW - sides(12f), area, maxSp, fs, min = 18f)
                    val sideSp = (first * 0.2f).coerceIn(12f, 18f)
                    // A tenth spare: large bold digits run wider than the estimate, and the row
                    // takes what is over out of 天, which shows as an ellipsis.
                    val numSp = fitSp(n, (bodyW - sides(sideSp)) * 0.9f, area, maxSp, fs, min = 18f)
                    val lift = (numSp * 0.17f).dp
                    WText(s.lead, sideSp.sp, WColors.muted, modifier = GlanceModifier.padding(end = 4.dp, bottom = lift))
                    WText(n, numSp.sp, if (s.passed) WColors.muted else WColors.ink, bold = true)
                    WText("天", (sideSp + 1).sp, WColors.muted, modifier = GlanceModifier.padding(start = 3.dp, bottom = lift))
                }
            }
        }
        if (s != null) {
            WText(
                Countdowns.subtitle(s, today), dateSp.sp, WColors.faint, align = TextAlign.Center,
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
