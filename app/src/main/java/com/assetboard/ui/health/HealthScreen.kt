package com.assetboard.ui.health

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.assetboard.ui.components.PullToCreateBox
import com.assetboard.ui.components.rememberPullToCreate
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.AppContainer
import com.assetboard.data.BodyMeasurement
import com.assetboard.data.HealthProfile
import com.assetboard.data.MeasurementDraft
import com.assetboard.data.Sex
import com.assetboard.domain.health.Health
import com.assetboard.domain.health.HealthTrend
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.SegmentStyle
import com.assetboard.ui.components.SegmentedControl
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.LineChart
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SwapText
import com.assetboard.ui.components.animatedCents
import com.assetboard.ui.components.appear
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import com.assetboard.ui.components.scrollIndicator
import androidx.compose.foundation.lazy.rememberLazyListState

/** The profile and every record, oldest first. */
data class HealthState(val profile: HealthProfile?, val records: List<BodyMeasurement>) {
    val latest: BodyMeasurement? get() = records.lastOrNull()
    val previous: BodyMeasurement? get() = records.getOrNull(records.size - 2)
}

class HealthViewModel(private val container: AppContainer) : ActionViewModel(container) {
    private val repo = container.health
    val state: StateFlow<HealthState?> = combine(repo.profile, repo.measurements, ::HealthState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(draft: MeasurementDraft, overwrite: Boolean, onSaved: () -> Unit) = action(onSuccess = onSaved) { repo.save(draft, overwrite) }
    fun delete(id: String, onDone: () -> Unit = {}) = action(onSuccess = onDone) { repo.delete(id) }
    fun saveProfile(sex: Sex, birth: LocalDate, heightMm: Int, targetG: Long?, onSaved: () -> Unit) =
        action(onSuccess = onSaved) { repo.saveProfile(sex, birth, heightMm, targetG) }
}

/** The day's derived figures, each null when its inputs are missing. */
class Reading(val m: BodyMeasurement, profile: HealthProfile?) {
    val bmi: Double? = profile?.let { Health.bmi(m.weightG, it.heightMm) }
    val bmiGrade: Health.Grade? = bmi?.let(Health::bmiGrade)
    val age: Int? = profile?.let { Health.ageOn(LocalDate.parse(it.birthDate), LocalDate.parse(m.date)) }
    val fatGrade: Health.FatGrade? = if (profile != null && m.bodyFatBp != null && age != null) Health.fatGrade(m.bodyFatBp, profile.sex, age) else null
    val fatMass: Long? = Health.fatMassG(m.weightG, m.bodyFatBp)
    val leanMass: Long? = Health.leanMassG(m.weightG, m.bodyFatBp)
    val bodyType: Health.BodyType? = Health.bodyType(bmi, m.bodyFatBp)
}

@Composable
fun gradeColor(g: Health.Grade): Color = when (g) {
    Health.Grade.LOW -> MaterialTheme.colorScheme.tertiary
    Health.Grade.STANDARD -> MaterialTheme.colorScheme.primary
    Health.Grade.HIGH -> MaterialTheme.colorScheme.secondary
    Health.Grade.OBESE -> MaterialTheme.colorScheme.error
}

@Composable
fun fatGradeColor(g: Health.FatGrade): Color = when (g) {
    Health.FatGrade.LOW -> MaterialTheme.colorScheme.tertiary
    Health.FatGrade.STANDARD -> MaterialTheme.colorScheme.primary
    Health.FatGrade.HIGH -> MaterialTheme.colorScheme.secondary
}

fun dayLabel(date: String, today: LocalDate = LocalDate.now()): String {
    val d = LocalDate.parse(date)
    return when (ChronoUnit.DAYS.between(d, today)) {
        0L -> "今天"
        1L -> "昨天"
        else -> if (d.year == today.year) "${d.monthValue}月${d.dayOfMonth}日" else "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
    }
}

/**
 * Health as an asset: the latest weigh-in, the line of a chosen metric over a chosen period, the
 * derived figures, the body-type matrix and every record. A day without a weigh-in is simply not
 * there: no zero, no interpolated point, no streak to break.
 */
@Composable
fun HealthScreen(onBack: () -> Unit, startRecording: Boolean = false) {
    val container = LocalContainer.current
    val vm: HealthViewModel = viewModel { HealthViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    ErrorDialog(error) { vm.error.value = null }
    var editing by rememberSaveable { mutableStateOf<String?>(if (startRecording) NEW else null) }
    var profileOpen by rememberSaveable { mutableStateOf(false) }
    var askedProfile by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BodyMeasurement?>(null) }
    val cs = MaterialTheme.colorScheme
    // Pulling the page down past its top is the hero's 称重.
    val pull = rememberPullToCreate(enabled = editing == null && !profileOpen) { editing = NEW }

    // The first visit without a profile asks for it once; cancelling leaves a weight-only page.
    LaunchedEffect(state) {
        val s = state ?: return@LaunchedEffect
        if (s.profile == null && !askedProfile && editing == null) {
            askedProfile = true
            profileOpen = true
        }
    }

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text("健康资产") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { profileOpen = true }) { Icon(Icons.Outlined.ManageAccounts, "健康档案") }
                },
                colors = glassBarColors(),
            )
        },
    ) { padding ->
        val s = state
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@GlassScaffold
        }
        PullToCreateBox(pull, "下拉称重", "松手称重", Modifier.fillMaxSize().padding(padding), icon = Icons.Outlined.MonitorWeight) {
            HealthContent(
                s,
                with(LocalDensity.current) { pull.pulled.toDp() },
                onRecord = { editing = NEW },
                onOpen = { editing = it.id },
                onDelete = { deleting = it },
                onProfile = { profileOpen = true },
            )
        }
    }

    val s = state
    if (s != null) {
        editing?.let { id ->
            HealthEditSheet(
                existing = if (id == NEW) null else s.records.firstOrNull { it.id == id },
                records = s.records,
                profile = s.profile,
                onDismiss = { editing = null },
                onSave = { draft, overwrite -> vm.save(draft, overwrite) { editing = null } },
                onDelete = { m -> editing = null; deleting = m },
            )
        }
        if (profileOpen) {
            HealthProfileSheet(
                profile = s.profile,
                onDismiss = { profileOpen = false },
                onSave = { sex, birth, height, target -> vm.saveProfile(sex, birth, height, target) { profileOpen = false } },
            )
        }
    }
    deleting?.let { m ->
        ConfirmDialog(
            title = "删除${dayLabel(m.date)}的记录？",
            text = "${Health.kg(m.weightG)} kg" + (m.bodyFatBp?.let { " · 体脂 ${Health.percent(it)}%" } ?: "") + "。删除后这一天就是空的，不会补点。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { vm.delete(m.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

private const val NEW = "new"

@Composable
private fun HealthContent(
    s: HealthState,
    /** The space a pull opens above the page. */
    top: Dp,
    onRecord: () -> Unit,
    onOpen: (BodyMeasurement) -> Unit,
    onDelete: (BodyMeasurement) -> Unit,
    onProfile: () -> Unit,
) {
    var range by rememberSaveable { mutableStateOf(HealthRange.D90) }
    var metric by rememberSaveable { mutableStateOf(HealthMetric.WEIGHT) }
    val latest = s.latest
    val barState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().scrollIndicator(barState), state = barState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (latest == null) {
            item { Box(Modifier.appear(0)) { EmptyHealth(onRecord) } }
            if (s.profile == null) item { ProfileBanner(onProfile) }
            return@LazyColumn
        }
        val reading = Reading(latest, s.profile)
        item { Box(Modifier.appear(0)) { HealthHero(s, reading, onRecord) } }
        if (s.profile == null) item { ProfileBanner(onProfile) }
        item {
            Column(Modifier.appear(1), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val metrics = if (s.profile == null) listOf(HealthMetric.WEIGHT, HealthMetric.FAT) else HealthMetric.entries
                if (metric !in metrics) metric = HealthMetric.WEIGHT
                SegmentedControl(metrics, metric, { it.label }, { metric = it })
                SegmentedControl(HealthRange.entries, range, { it.label }, { range = it }, style = SegmentStyle.TINT, height = 32.dp)
            }
        }
        item { Box(Modifier.appear(2)) { TrendCard(s, metric, range) } }
        item { Box(Modifier.appear(3)) { FiguresCard(reading, s.profile != null) } }
        if (s.profile != null) {
            item { Box(Modifier.appear(4)) { TargetCard(s, s.profile, range) } }
            item { Box(Modifier.appear(5)) { BodyTypeCard(reading) } }
        }
        item { Box(Modifier.appear(6)) { RecordsCard(s, onOpen, onDelete) } }
        item {
            Text(
                "本结果仅供参考，不建议作为医疗或健康状况依据。体脂率等阻抗数值由体脂秤估算，看趋势方向比看绝对值更可靠。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The latest weigh-in on the hero gradient: the weight counting to its value, the change since
 * the previous record (whenever that was), body fat and BMI. Changes are neutral: down is not
 * green and up is not red here, so they never read like a stock ticker.
 */
@Composable
private fun HealthHero(s: HealthState, r: Reading, onRecord: () -> Unit) {
    val extra = AppTheme.extra
    val m = r.m
    val weight = animatedCents(m.weightG)
    val daysAgo = ChronoUnit.DAYS.between(LocalDate.parse(m.date), LocalDate.now())
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(8.dp, MaterialTheme.shapes.extraLarge, ambientColor = extra.heroStart, spotColor = extra.heroStart)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(extra.heroStart, extra.heroEnd)))
            .drawBehind {
                val w = size.width
                val h = size.height
                val a = Offset(w, h * 0.1f)
                drawCircle(Brush.radialGradient(listOf(extra.onHeroAccent.copy(alpha = 0.10f), Color.Transparent), a, w), w, a)
            }
            .padding(22.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("最新体重", color = extra.onHeroMuted, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (daysAgo == 0L) "今天 " + com.assetboard.domain.Dates.time(m.measuredAt) else dayLabel(m.date) + " · " + HealthTrend.lastSeenLabel(daysAgo),
                        color = extra.onHeroMuted, style = MaterialTheme.typography.labelSmall,
                    )
                }
                HeroRecordButton(onRecord)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Health.kg(weight), color = extra.onHero, style = MaterialTheme.typography.displaySmall, maxLines = 1)
                Text("kg", color = extra.onHero, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 6.dp, bottom = 5.dp))
            }
            s.previous?.let { p ->
                val d = m.weightG - p.weightG
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(extra.onHero.copy(alpha = 0.14f))
                        .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (d != 0L) {
                        Icon(if (d > 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, tint = extra.onHero, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "较上次 ${Health.kgDelta(d)} kg · ${dayLabel(p.date)}",
                        color = extra.onHero, style = MaterialTheme.typography.labelMedium.merge(NumberStyle),
                    )
                }
            }
            if (daysAgo > 7) {
                Text(
                    "没称的日子就空着，想起来再记就好",
                    color = extra.onHeroMuted, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row {
                HeroFigure("体脂率", m.bodyFatBp?.let { Health.percent(it) + "%" } ?: "—", r.fatGrade?.label, Modifier.weight(1f))
                if (s.profile != null) {
                    HeroFigure("BMI", r.bmi?.let(Health::bmiText) ?: "—", r.bmiGrade?.label, Modifier.weight(1f))
                    val target = Health.targetWeightG(s.profile.heightMm, s.profile.targetWeightG)
                    HeroFigure("距目标", Health.kgDelta(m.weightG - target, signed = true) + " kg", null, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeroRecordButton(onClick: () -> Unit) {
    val extra = AppTheme.extra
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(source, 0.92f)
            .shadow(6.dp, RoundedCornerShape(50), ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(RoundedCornerShape(50))
            .background(extra.onHero)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .height(38.dp)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.MonitorWeight, null, tint = extra.heroStart, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("称重", color = extra.heroStart, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun HeroFigure(label: String, value: String, grade: String?, modifier: Modifier) {
    val extra = AppTheme.extra
    Column(modifier) {
        Text(label, color = extra.onHeroMuted, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = extra.onHero, style = MaterialTheme.typography.titleMedium.merge(NumberStyle), maxLines = 1)
            if (grade != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    grade,
                    color = extra.onHero,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(extra.onHero.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}


/**
 * The chosen metric over the chosen period: the summary of what changed between its two ends,
 * the line with its moving average, the target and the healthy band behind it, and a readout
 * of whichever day is being touched.
 */
@Composable
private fun TrendCard(s: HealthState, metric: HealthMetric, range: HealthRange) {
    val cs = MaterialTheme.colorScheme
    val period = remember(s.records, range) { HealthTrend.period(s.records, range.days, System.currentTimeMillis()) } ?: return
    val chart = remember(period, metric, s.profile, cs.primary) { healthChart(metric, period.points, s.profile, cs.primary, cs.tertiary, s.records) }
    var selected by remember(chart) { mutableStateOf<Int?>(null) }
    SectionCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                SwapText("${metric.label}变化", style = MaterialTheme.typography.titleMedium)
                SwapText(HealthTrend.coverageLabel(range.days, period.records), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            val first = chart.records.firstOrNull()
            val last = chart.records.lastOrNull()
            if (first != null && last != null && first !== last) {
                val h = s.profile?.heightMm
                val d = metric.value(last, h)!! - metric.value(first, h)!!
                Column(horizontalAlignment = Alignment.End) {
                    SwapText(metric.deltaWithUnit(d), style = MaterialTheme.typography.titleMedium.merge(NumberStyle), textAlign = TextAlign.End)
                    SwapText("自 ${dayLabel(first.date)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val readout = selected?.let { chart.records.getOrNull(it) }
        AnimatedContent(readout, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) }, label = "readout") { r ->
            val h = s.profile?.heightMm
            Text(
                if (r == null) {
                    if (chart.records.size < 2) "新记录不足，无法比较" else "按住曲线查看每一次记录"
                } else {
                    dayLabel(r.date) + " · " + metric.withUnit(metric.format(metric.value(r, h)!!))
                },
                style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                color = if (r == null) cs.outline else cs.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        // An empty period fades in place of the chart; the chart itself morphs between views.
        // Keyed on empty-or-not, so the outgoing side keeps the data it had.
        AnimatedContent(
            chart.takeIf { it.records.isNotEmpty() },
            contentKey = { it == null },
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
            label = "chart",
        ) { shown ->
            if (shown == null) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Text(if (metric == HealthMetric.FAT) "这段时间没有记录体脂率" else "这段时间没有记录", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LineChart(
                    series = shown.series,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    formatY = metric::format,
                    onSelect = { selected = it },
                    target = shown.target,
                    targetLabel = shown.targetLabel,
                    band = shown.band,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(cs.primary, dashed = false, "每次记录")
            androidx.compose.animation.AnimatedVisibility(chart.series.size > 1, enter = fadeIn(tween(260)), exit = fadeOut(tween(160))) {
                Legend(cs.tertiary, dashed = true, "7 天平均")
            }
            // Only when the band actually reaches the plotted values; the chart clips it otherwise.
            val ys = chart.series.first().points.map { it.y }
            val band = chart.band
            val reached = band != null && ys.isNotEmpty() && band.first <= ys.max() && band.last >= ys.min()
            androidx.compose.animation.AnimatedVisibility(reached, enter = fadeIn(tween(260)), exit = fadeOut(tween(160))) {
                LegendBand(if (metric == HealthMetric.FAT) "标准区间" else "健康区间")
            }
        }
    }
}

@Composable
private fun Legend(color: Color, dashed: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(18.dp).height(8.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5f, 4f)) else null,
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendBand(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 18.dp, height = 10.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * BMI, body fat, fat mass and lean mass for the latest day, side by side in one row; "—" where
 * that day lacks the input.
 */
@Composable
private fun FiguresCard(r: Reading, hasProfile: Boolean) {
    SectionCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        Text("身体成分", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val cell = Modifier.weight(1f).fillMaxHeight()
            FigureCell(
                "BMI", r.bmi?.let(Health::bmiText) ?: "—", "",
                r.bmiGrade?.let { it.label to gradeColor(it) }, cell,
                hint = if (!hasProfile) "需要身高" else null,
            )
            FigureCell("体脂率", r.m.bodyFatBp?.let(Health::percent) ?: "—", "%", r.fatGrade?.let { it.label to fatGradeColor(it) }, cell)
            FigureCell("脂肪量", r.fatMass?.let(Health::kg) ?: "—", "kg", null, cell)
            FigureCell("去脂体重", r.leanMass?.let(Health::kg) ?: "—", "kg", null, cell)
        }
    }
}

@Composable
private fun FigureCell(label: String, value: String, unit: String, grade: Pair<String, Color>?, modifier: Modifier, hint: String? = null) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium.merge(NumberStyle), maxLines = 1)
            if (value != "—" && unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
        }
        grade?.let { (text, color) ->
            Text(
                text, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                modifier = Modifier.padding(top = 3.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp),
            )
        }
        hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.outline, maxLines = 1, modifier = Modifier.padding(top = 3.dp)) }
    }
}

/**
 * The target weight, how far away it is, how much of the way this period covered, and the
 * healthy band. Progress is shown only when it means something (see HealthTrend.targetProgress).
 */
@Composable
private fun TargetCard(s: HealthState, profile: HealthProfile, range: HealthRange) {
    val cs = MaterialTheme.colorScheme
    val latest = s.latest ?: return
    val target = Health.targetWeightG(profile.heightMm, profile.targetWeightG)
    val healthy = Health.healthyRangeG(profile.heightMm)
    val period = remember(s.records, range) { HealthTrend.period(s.records, range.days, System.currentTimeMillis()) }
    val start = period?.start
    val progress = if (start != null && start !== latest) HealthTrend.targetProgress(start.weightG, latest.weightG, target) else null
    SectionCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        val gap = latest.weightG - target
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("目标体重", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (profile.targetWeightG == null) "按 BMI 22 计算，可在档案里改" else "自定目标",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${Health.kg(target)} kg", style = MaterialTheme.typography.titleMedium.merge(NumberStyle))
                Text(
                    if (gap == 0L) "已经到达" else "还差 ${Health.kg(kotlin.math.abs(gap))} kg",
                    style = MaterialTheme.typography.labelSmall.merge(NumberStyle), color = cs.onSurfaceVariant,
                )
            }
        }
        if (progress != null && start != null) {
            val shown = remember { Animatable(0f) }
            // A slow fill on first show; a switch of range just moves it.
            LaunchedEffect(progress) {
                val first = shown.value == 0f
                shown.animateTo(progress.toFloat(), tween(if (first) 1_000 else 520, delayMillis = if (first) 200 else 0, easing = FastOutSlowInEasing))
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(cs.primary.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(shown.value).height(6.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(cs.primary.copy(alpha = 0.6f), cs.primary))))
            }
            Spacer(Modifier.height(4.dp))
            SwapText(
                "自 ${dayLabel(start.date)}（${Health.kg(start.weightG)} kg）走完 ${(progress * 100).roundToLong()}%",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        Text(
            "健康区间 ${Health.kg(healthy.first)}–${Health.kg(healthy.last + 1)} kg（BMI 18.5–24）",
            style = MaterialTheme.typography.labelSmall, color = cs.outline,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * The scale's nine body types: BMI down the side, body fat across. The latest day's cell lights
 * up and its dot springs into place; a day without body fat places no dot.
 */
@Composable
private fun BodyTypeCard(r: Reading) {
    val cs = MaterialTheme.colorScheme
    val type = r.bodyType
    val rows = listOf(
        "BMI ≥ 24" to listOf(Health.BodyType.STRONG, Health.BodyType.PLUMP, Health.BodyType.OBESE),
        "18.5–24" to listOf(Health.BodyType.MUSCULAR, Health.BodyType.HEALTHY, Health.BodyType.HIDDEN_FAT),
        "< 18.5" to listOf(Health.BodyType.THIN, Health.BodyType.SLIM, Health.BodyType.UNDERWEIGHT),
    )
    SectionCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("身体类型", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            AnimatedContent(type?.label, label = "type") { t ->
                Text(t ?: "", style = MaterialTheme.typography.titleMedium, color = cs.primary)
            }
        }
        if (type == null) {
            Text("记录体脂率后显示身体类型", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            return@SectionCard
        }
        Row(Modifier.padding(start = 56.dp, bottom = 2.dp)) {
            listOf("体脂 < 10%", "10–20%", "≥ 20%").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        rows.forEachIndexed { ri, (label, cells) ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(56.dp))
                cells.forEachIndexed { ci, cell ->
                    val on = cell == type
                    val bg by animateColorAsState(if (on) cs.primaryContainer else cs.surfaceContainerHigh, tween(300), label = "cell")
                    val pop by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "dot")
                    Box(
                        Modifier.weight(1f).padding(horizontal = 3.dp).height(36.dp).clip(RoundedCornerShape(10.dp)).background(bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            cell.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            fontWeight = if (on) FontWeight.SemiBold else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (pop > 0.01f) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset((-6).dp, 6.dp).size(8.dp)
                                    .graphicsLayer { scaleX = pop; scaleY = pop }
                                    .clip(CircleShape).background(cs.primary),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every record, newest first, each against the record before it, folded under its head until
 * opened (the chart already shows them); a tap on the head opens and folds it. A day without a
 * weigh-in has no row. Long press deletes; tap edits.
 */
@Composable
private fun RecordsCard(s: HealthState, onOpen: (BodyMeasurement) -> Unit, onDelete: (BodyMeasurement) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    var all by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "recordsFold")
    val desc = s.records.asReversed()
    val shown = if (all) desc else desc.take(20)
    SectionCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "收起" else "展开") { open = !open }.padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("全部记录", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (open) "${s.records.size} 条 · 长按删除" else "${s.records.size} 条", style = MaterialTheme.typography.labelSmall, color = cs.outline)
            Icon(Icons.Default.ExpandMore, null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp).size(22.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                shown.forEachIndexed { i, m ->
                    val prev = s.records.getOrNull(s.records.size - 2 - i)
                    if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 72.dp), color = cs.outlineVariant)
                    RecordRow(m, prev, s.profile, onClick = { onOpen(m) }, onLongClick = { onDelete(m) })
                }
                if (!all && desc.size > shown.size) {
                    TextButton(onClick = { all = true }, modifier = Modifier.fillMaxWidth()) { Text("显示全部 ${desc.size} 条") }
                }
            }
        }
    }
}

@Composable
private fun RecordRow(m: BodyMeasurement, prev: BodyMeasurement?, profile: HealthProfile?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val d = LocalDate.parse(m.date)
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.primary.copy(alpha = 0.08f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall.merge(NumberStyle), color = cs.primary)
            Text("${d.monthValue}月", style = MaterialTheme.typography.labelSmall, color = cs.primary.copy(alpha = 0.75f))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Health.kg(m.weightG), style = MaterialTheme.typography.bodyLarge.merge(NumberStyle), fontWeight = FontWeight.SemiBold)
                Text(" kg", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            val sub = listOfNotNull(
                m.bodyFatBp?.let { "体脂 ${Health.percent(it)}%" },
                profile?.let { Health.bmi(m.weightG, it.heightMm) }?.let { "BMI ${Health.bmiText(it)}" },
                m.note,
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (prev != null) {
            val delta = m.weightG - prev.weightG
            Column(horizontalAlignment = Alignment.End) {
                Text(Health.kgDelta(delta), style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = cs.onSurface)
                Text(dayLabel(prev.date).let { "较 $it" }, style = MaterialTheme.typography.labelSmall, color = cs.outline)
            }
        }
    }
}

@Composable
private fun ProfileBanner(onProfile: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("补充身高生日后显示 BMI、分级与目标", color = cs.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onProfile) { Text("填写") }
        }
    }
}

@Composable
private fun EmptyHealth(onRecord: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionCard(contentPadding = PaddingValues(28.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MonitorWeight, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("还没有健康记录", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "称一次，记下体重（有体脂秤就加上体脂率），这里会画出变化曲线。没称的日子空着就好。",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRecord) { Text("记录第一次测量") }
        }
    }
}
