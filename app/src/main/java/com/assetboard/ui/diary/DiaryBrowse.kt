package com.assetboard.ui.diary

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.data.DiaryEntry
import com.assetboard.domain.calendar.ChineseCalendar
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.highlight
import com.assetboard.ui.components.snippet
import com.assetboard.ui.theme.AppTheme
import kotlinx.coroutines.delay
import com.assetboard.data.Weeks
import androidx.compose.foundation.shape.CircleShape
import java.time.LocalDate
import java.time.YearMonth

/**
 * Month grid: days with text are filled in, today is ringed, 休 / 班 mark official days off and
 * make-up workdays. Swipe sideways to change month; the summary counts this month and the
 * current streak.
 */
@Composable
fun DiaryCalendarScreen(initial: String?, onBack: () -> Unit, onPick: (LocalDate) -> Unit, onWeek: (LocalDate) -> Unit = {}) {
    val container = LocalContainer.current
    var month by rememberSaveable { mutableStateOf((initial?.let(LocalDate::parse) ?: LocalDate.now()).withDayOfMonth(1).toString()) }
    val ym = YearMonth.from(LocalDate.parse(month))
    val today = LocalDate.now()
    val written by remember(month) { container.diary.observeDatesWithText(ym.atDay(1), ym.atEndOfMonth()) }
        .collectAsStateWithLifecycle(emptyList())
    // Weeks that reach into this month from either side, so a week shared by two months shows its mark in both.
    val reviewed by remember(month) { container.weeks.observeWeeksWithText(Weeks.start(ym.atDay(1)), ym.atEndOfMonth()) }
        .collectAsStateWithLifecycle(emptyList())
    val recent by remember(today) { container.diary.observeDatesWithText(today.minusDays(400), today) }
        .collectAsStateWithLifecycle(emptyList())
    val holidays by container.holidays.table.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val writtenSet = written.toSet()
    val streak = remember(recent, today) { DiaryStreak.of(recent.toSet(), today) }
    // Months ahead show holidays and make-up workdays for planning; their days cannot be written yet.
    val lastMonth = YearMonth.from(today).plusMonths(MONTHS_AHEAD)
    val go: (Int) -> Unit = { delta ->
        val next = ym.plusMonths(delta.toLong())
        if (next <= lastMonth) month = next.atDay(1).toString()
    }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("日记日历") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { if (YearMonth.from(today) != ym) TextButton(onClick = { month = today.withDayOfMonth(1).toString() }) { Text("回到本月") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Summary(written.size, ym, today, streak)
            SectionCard(
                Modifier.pointerInput(month) {
                    var total = 0f
                    detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                        if (total > 80f) go(-1) else if (total < -80f) go(1)
                    }) { _, dx -> total += dx }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { go(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上个月") }
                    AnimatedContent(ym, transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith fadeOut()
                    }, label = "month", modifier = Modifier.weight(1f)) { m ->
                        Text("${m.year}年${m.monthValue}月", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = { go(1) }, enabled = ym < lastMonth) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下个月") }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                        Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    Text("周", modifier = Modifier.width(WEEK_COLUMN), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                }
                AnimatedContent(ym, transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it else it } + fadeOut())
                }, label = "grid") { m ->
                    Column {
                        val lead = m.atDay(1).dayOfWeek.value - 1
                        val cells = List(lead) { null } + (1..m.lengthOfMonth()).map { m.atDay(it) }
                        cells.chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth()) {
                                week.forEach { d ->
                                    Box(Modifier.weight(1f).aspectRatio(0.8f).padding(2.dp)) {
                                        if (d != null) DayCell(d, d == today, d.toString() in writtenSet, holidays.mark(d), enabled = !d.isAfter(today)) { onPick(d) }
                                    }
                                }
                                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                                val start = Weeks.start(week.first { it != null }!!)
                                Box(Modifier.width(WEEK_COLUMN).align(Alignment.CenterVertically), contentAlignment = Alignment.Center) {
                                    if (!start.isAfter(today)) WeekMark(start.toString() in reviewed) { onWeek(start) }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(MaterialTheme.shapes.extraSmall).background(cs.primary))
                Text("  写了日记", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Box(Modifier.size(14.dp).border(1.5.dp, cs.primary, MaterialTheme.shapes.extraSmall))
                Text("  今天", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Spacer(Modifier.width(16.dp))
                Box(Modifier.size(14.dp).clip(CircleShape).background(cs.secondary))
                Text("  周复盘", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

private const val MONTHS_AHEAD = 12L
private val WEEK_COLUMN = 30.dp

/** A week's review in the calendar's last column: filled once written; tap to open the week. */
@Composable
private fun WeekMark(written: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .background(if (written) cs.secondary else Color.Transparent)
            .border(1.5.dp, if (written) Color.Transparent else cs.secondary.copy(alpha = 0.5f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("复", style = MaterialTheme.typography.labelSmall, color = if (written) cs.onSecondary else cs.secondary)
    }
}

/** This month counts the days so far, a past month all of its days, a future one none yet. */
@Composable
private fun Summary(count: Int, ym: YearMonth, today: LocalDate, streak: Int) {
    val current = YearMonth.from(today)
    val days = when {
        ym == current -> today.dayOfMonth
        ym > current -> 0
        else -> ym.lengthOfMonth()
    }
    val extra = AppTheme.extra
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(Brush.linearGradient(listOf(extra.heroStart, extra.heroEnd))).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (days == 0) "${ym.monthValue}月" else "${ym.monthValue}月写了", style = MaterialTheme.typography.labelMedium, color = extra.onHeroMuted)
            if (days == 0) {
                Text("还没到", style = MaterialTheme.typography.headlineMedium, color = extra.onHero, fontWeight = FontWeight.Bold)
            } else Row(verticalAlignment = Alignment.Bottom) {
                AnimatedContent(count, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "count") {
                    Text(it.toString(), style = MaterialTheme.typography.headlineMedium, color = extra.onHero, fontWeight = FontWeight.Bold)
                }
                Text(" / $days 天", style = MaterialTheme.typography.bodyMedium, color = extra.onHeroMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        Box(Modifier.width(1.dp).height(40.dp).background(extra.onHero.copy(alpha = 0.2f)))
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text("连续记录", style = MaterialTheme.typography.labelMedium, color = extra.onHeroMuted)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(streak.toString(), style = MaterialTheme.typography.headlineMedium, color = extra.onHero, fontWeight = FontWeight.Bold)
                Text(" 天", style = MaterialTheme.typography.bodyMedium, color = extra.onHeroMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun DayCell(
    d: LocalDate,
    isToday: Boolean,
    written: Boolean,
    mark: com.assetboard.domain.calendar.HolidayTable.DayMark?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val label = remember(d) { ChineseCalendar.cellLabel(d) }
    val festival = label.length > 2 || label.endsWith("节") || label == "除夕"
    val shape = MaterialTheme.shapes.small
    val fg = when {
        written -> cs.onPrimary
        enabled -> cs.onSurface
        else -> cs.outline
    }
    Column(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(if (written) Brush.verticalGradient(listOf(cs.primary, AppTheme.extra.heroStart)) else SolidColor(Color.Transparent))
            .then(if (isToday) Modifier.border(2.dp, if (written) cs.onPrimary.copy(alpha = 0.7f) else cs.primary, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(top = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                d.dayOfMonth.toString(),
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
                color = fg,
                fontWeight = if (isToday || written) FontWeight.Bold else null,
            )
            mark?.let {
                Text(
                    if (it.isOffDay) "休" else "班",
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 3.dp),
                    fontSize = 9.sp,
                    color = if (written) cs.onPrimary else if (it.isOffDay) AppTheme.extra.gain else cs.onSurfaceVariant,
                )
            }
        }
        Text(
            label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Clip,
            color = when {
                written -> cs.onPrimary.copy(alpha = 0.8f)
                festival -> cs.secondary
                else -> cs.onSurfaceVariant
            },
        )
    }
}

/** Full-text search over all live entries, newest first, with the hits highlighted. */
@Composable
fun DiarySearchScreen(onBack: () -> Unit, onPick: (LocalDate) -> Unit) {
    val container = LocalContainer.current
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DiaryEntry>?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(q) {
        delay(250)
        results = if (q.isBlank()) null else container.diary.search(q)
    }
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        q, { q = it }, singleLine = true, placeholder = { Text("搜索日记") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {}),
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp).focusRequester(focus),
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { padding ->
        val r = results
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (r != null && r.isEmpty()) item { Text("没有找到「$q」", color = cs.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            items(r.orEmpty(), key = { it.id }) { e ->
                SectionCard(Modifier.clickable { onPick(LocalDate.parse(e.date)) }, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                    Text(e.date, style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    Text(highlight(snippet(e.body, q), q, cs.primary), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
