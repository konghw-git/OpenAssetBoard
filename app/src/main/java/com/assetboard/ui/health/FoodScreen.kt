package com.assetboard.ui.health

import com.assetboard.domain.AppClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import com.assetboard.ui.components.LocalDust
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import com.assetboard.ui.components.PullToCreateBox
import com.assetboard.ui.components.rememberPullToCreate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.AppContainer
import com.assetboard.data.BodyMeasurement
import com.assetboard.data.FoodDraft
import com.assetboard.data.FoodEntry
import com.assetboard.data.FoodUnit
import com.assetboard.data.HealthProfile
import com.assetboard.data.Sex
import com.assetboard.domain.health.Energy
import com.assetboard.domain.health.Health
import com.assetboard.domain.todo.Recurrence
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.Dot
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SwipeAction
import com.assetboard.ui.components.SwipeActionsBox
import com.assetboard.ui.components.UndoEvent
import com.assetboard.ui.components.UndoMessages
import com.assetboard.ui.components.animatedCents
import com.assetboard.ui.components.heroSurface
import com.assetboard.ui.components.appear
import com.assetboard.ui.components.glassBarColors
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.components.rememberSwipeGroup
import com.assetboard.ui.components.scrollIndicator
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.NumberStyle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** Every entry, oldest first, with what the burn needs: the profile and the weigh-ins. */
data class FoodState(val entries: List<FoodEntry>, val profile: HealthProfile?, val records: List<BodyMeasurement>) {
    fun day(date: LocalDate): Energy.Day = Energy.day(date, entries, profile, records)
    val recent: List<FoodEntry> by lazy { Energy.recent(entries, RECENT_MAX) }
}

class FoodViewModel(private val container: AppContainer) : ActionViewModel(container) {
    private val repo = container.food
    val state: StateFlow<FoodState?> = combine(repo.entries, container.health.profile, container.health.measurements, ::FoodState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UndoEvent> = _events

    fun save(draft: FoodDraft, onSaved: () -> Unit) = action(onSuccess = onSaved) { repo.save(draft) }

    /** Deleting is one swipe, so it is undone rather than confirmed. */
    fun delete(id: String) = action {
        val gone = repo.delete(id) ?: return@action
        _events.tryEmit(UndoEvent("已删除「${foodName(gone).take(12)}」") { repo.restore(gone) })
    }

    /**
     * Eaten again, from a right swipe, with an undo. Today's entry counted in portions takes one
     * more portion in place, so its row says how many; a label entry, or one from another day, is
     * recorded again today as an entry of its own.
     */
    fun eatAgain(e: FoodEntry) = action {
        val today = repo.today()
        if (e.date == today.toString() && e.unit !in FoodUnit.labelUnits) {
            val before = repo.oneMore(e.id)
            val now = Energy.servings(Energy.servingHalves(before) + Energy.SERVING_PER)
            _events.tryEmit(UndoEvent("「${foodName(e).take(12)}」再来一份，共 $now 份") { repo.putBack(before) })
            return@action
        }
        val id = repo.save(FoodDraft(null, today, e.name, e.per, e.amount, e.unit, e.energyCal, e.inKj, e.proteinMg, e.fatMg, e.carbMg, e.sodiumMg))
        val where = if (e.date == today.toString()) "又记了一份" else "已记到今天"
        _events.tryEmit(UndoEvent("$where「${foodName(e).take(12)}」") { repo.delete(id) })
    }

    fun undo(e: UndoEvent) {
        val u = e.undo ?: return
        action { u() }
    }

    fun saveProfile(sex: Sex, birth: LocalDate, heightMm: Int, targetG: Long?, onSaved: () -> Unit) =
        action(onSuccess = onSaved) { container.health.saveProfile(sex, birth, heightMm, targetG) }
}

fun foodName(e: FoodEntry): String = e.name ?: "未命名"

/**
 * How much and at what rate, the same shape however the energy went in: "500 ml · 每 100 ml
 * 40.9 kcal" from a label, "2 份 · 每份 70 kcal" from a guess, a single portion as "1 份".
 */
fun foodPortion(e: FoodEntry): String = when {
    e.unit in FoodUnit.labelUnits && e.per != null && e.amount != null ->
        "${e.amount} ${e.unit!!.label} · 每 ${e.per} ${e.unit.label} ${Energy.energyInput(e.energyCal, false)} kcal"
    else -> "${Energy.servings(Energy.servingHalves(e))} 份 · 每份 ${Energy.energyInput(e.energyCal, false)} kcal"
}

/** "水煮蛋×2", "燕麦奶": an entry in the folded list's one line. */
private fun foodShort(e: FoodEntry): String {
    val halves = Energy.servingHalves(e)
    val many = e.unit !in FoodUnit.labelUnits && halves != Energy.SERVING_PER
    return foodName(e) + if (many) "×" + Energy.servings(halves) else ""
}

/**
 * What was eaten on a day, against what that day burned: the day's intake on the hero with the
 * deficit, the nutrients, every entry, and the last seven days. Days without entries stay empty;
 * there is no target and nothing to keep up.
 */
@Composable
fun FoodScreen(onBack: () -> Unit, startRecording: Boolean = false, onWeigh: () -> Unit = {}) {
    val container = LocalContainer.current
    val vm: FoodViewModel = viewModel { FoodViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    ErrorDialog(error) { vm.error.value = null }
    val snackbar = remember { SnackbarHostState() }
    UndoMessages(vm.events, snackbar, vm::undo)
    val today = AppClock.today()
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    var editing by rememberSaveable { mutableStateOf<String?>(if (startRecording) NEW else null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var profileOpen by rememberSaveable { mutableStateOf(false) }
    val day = LocalDate.parse(selected)
    // Pulling the page down past its top is the hero's 记一笔.
    val pull = rememberPullToCreate(enabled = editing == null && !profileOpen) { editing = NEW }

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text("饮食") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { picking = true }) { Icon(Icons.Outlined.CalendarMonth, "选择日期") }
                },
                colors = glassBarColors(),
            )
        },
        snackbarHost = { GlassSnackbarHost(snackbar) },
    ) { padding ->
        val s = state
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@GlassScaffold
        }
        PullToCreateBox(pull, "下拉记一笔", "松手记一笔", Modifier.fillMaxSize().padding(padding), icon = Icons.Outlined.Restaurant) {
            FoodContent(
                s, day, today, with(LocalDensity.current) { pull.pulled.toDp() },
                onSelect = { selected = it.toString() },
                onRecord = { editing = NEW },
                onOpen = { editing = it.id },
                onDelete = { vm.delete(it.id) },
                onAgain = vm::eatAgain,
                onProfile = { profileOpen = true },
                onWeigh = onWeigh,
            )
        }
    }

    val s = state
    if (s != null) {
        editing?.let { id ->
            val dust = LocalDust.current
            val scope = rememberCoroutineScope()
            val cell = with(LocalDensity.current) { 3.dp.toPx() }
            val existing = if (id == NEW) null else s.entries.firstOrNull { it.id == id }
            // An entry gone while its sheet was open (an undo, a restore): nothing left to edit.
            val gone = id != NEW && existing == null
            LaunchedEffect(gone) { if (gone) editing = null }
            if (gone) return@let
            FoodEditSheet(
                existing = existing,
                date = if (day.isAfter(today)) today else day,
                recent = s.recent,
                onDismiss = { editing = null },
                onSave = { draft -> vm.save(draft) { editing = null; selected = draft.date.toString() } },
                // Deleted from its sheet, the entry's row still goes in dust, as a swiped one does.
                onDelete = { e ->
                    editing = null
                    scope.launch {
                        dust?.dissolve(listOf("food:${e.id}"), cell = cell)
                        vm.delete(e.id)
                    }
                },
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
    if (picking) {
        DatePickDialog(
            initial = day,
            maxDate = today,
            onDismiss = { picking = false },
            onPick = { selected = it.toString(); picking = false },
        )
    }
}

private const val NEW = "new"

/** How many things eaten before the sheet offers. */
private const val RECENT_MAX = 30

@Composable
private fun FoodContent(
    s: FoodState,
    date: LocalDate,
    today: LocalDate,
    /** The space a pull opens above the page. */
    top: Dp,
    onSelect: (LocalDate) -> Unit,
    onRecord: () -> Unit,
    onOpen: (FoodEntry) -> Unit,
    onDelete: (FoodEntry) -> Unit,
    onAgain: (FoodEntry) -> Unit,
    onProfile: () -> Unit,
    onWeigh: () -> Unit,
) {
    val day = remember(s, date) { s.day(date) }
    val barState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().scrollIndicator(barState), state = barState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Box(Modifier.appear(0)) { DayHero(s, day, today, onRecord, onToday = { onSelect(today) }, onProfile, onWeigh) } }
        item { Box(Modifier.appear(1)) { EntriesCard(day, today, onOpen, onDelete, onAgain) } }
        if (day.totals.hasNutrients) item { Box(Modifier.appear(2)) { NutrientsCard(day) } }
        item { Box(Modifier.appear(3)) { WeekCard(s, today, date, onSelect) } }
        item {
            Text(
                "基础代谢按 Mifflin-St Jeor 公式估算，体重取当天或之前最近的一次称重。缺口 = 基础代谢 − 摄入，是完全不运动时的缺口，动了只会更大。只是估算，供记录时对照。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** "今天", "昨天", "10月21日 周二". */
private fun dayTitle(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> dayLabel(d.toString(), today) + " " + Recurrence.weekName(d.dayOfWeek)
}

/**
 * The day on the hero gradient: what was eaten counting up, the deficit against the day's resting
 * rate (words, not a colour: eating more is not a loss), the share of the resting rate eaten so far
 * over a bar of the two, and where the resting rate comes from. Without a profile or a weigh-in, a quiet way to add them.
 */
@Composable
private fun DayHero(
    s: FoodState,
    day: Energy.Day,
    today: LocalDate,
    onRecord: () -> Unit,
    onToday: () -> Unit,
    onProfile: () -> Unit,
    onWeigh: () -> Unit,
) {
    val extra = AppTheme.extra
    val intake = animatedCents(day.intakeCal)
    val isToday = day.date == today
    val burn = day.burn
    Box(
        Modifier
            .fillMaxWidth()
            .heroSurface(MaterialTheme.shapes.extraLarge, extra)
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
                    Text(dayTitle(day.date, today) + "摄入", color = extra.onHeroMuted, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (day.entries.isEmpty()) "还没有记录" else "${day.entries.size} 笔记录",
                        color = extra.onHeroMuted, style = MaterialTheme.typography.labelSmall,
                    )
                }
                HeroAddButton(onRecord)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Energy.kcal(intake), color = extra.onHero, style = MaterialTheme.typography.displaySmall, maxLines = 1)
                Text("kcal", color = extra.onHero, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 6.dp, bottom = 5.dp))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val deficit = day.deficitCal
                if (deficit != null && day.entries.isNotEmpty()) {
                    HeroChip(
                        (if (deficit >= 0) "缺口 ${Energy.kcal(deficit)} kcal" else "超出基础代谢 ${Energy.kcal(-deficit)} kcal") +
                            if (isToday) " · 截至现在" else "",
                    )
                }
                if (!isToday) HeroChip("回到今天", outlined = true, onClick = onToday)
            }
            Spacer(Modifier.height(18.dp))
            if (burn != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("吃到基础代谢的", color = extra.onHeroMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(bottom = 3.dp))
                    // Counts up with the kcal above it.
                    Text(
                        "${(intake * 100 + burn.restingCal / 2) / burn.restingCal}%",
                        color = extra.onHero, style = MaterialTheme.typography.titleLarge.merge(NumberStyle), fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
                Spacer(Modifier.height(6.dp))
                IntakeBar(day.intakeCal, burn.restingCal)
                Spacer(Modifier.height(16.dp))
                Row {
                    HeroFigure("基础代谢", Energy.kcal(burn.restingCal) + " kcal", null, Modifier.weight(1f))
                    HeroFigure("按体重", Health.kg(burn.weight.weightG) + " kg", null, Modifier.weight(1f))
                }
            } else {
                val (text, action) = when {
                    s.profile == null -> "填写健康档案后估算基础代谢" to onProfile
                    s.records.isEmpty() -> "称一次体重后估算基础代谢" to onWeigh
                    else -> "18 岁以下不估算基础代谢" to null
                }
                HeroChip(text, outlined = true, onClick = action)
            }
        }
    }
}

@Composable
private fun HeroAddButton(onClick: () -> Unit) {
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
        Icon(Icons.Outlined.Restaurant, null, tint = extra.heroStart, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("记一笔", color = extra.heroStart, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** A small pill on the hero: filled for a figure, outlined for something to tap. */
@Composable
private fun HeroChip(text: String, outlined: Boolean = false, onClick: (() -> Unit)? = null) {
    val extra = AppTheme.extra
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = extra.onHero,
        style = MaterialTheme.typography.labelMedium.merge(NumberStyle),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(shape)
            .then(if (outlined) Modifier.border(1.dp, extra.onHero.copy(alpha = 0.3f), shape) else Modifier.background(extra.onHero.copy(alpha = 0.14f)))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * Intake against the day's resting rate on one track. The track is whichever is larger, so eating
 * past it grows the bar and leaves a tick where the resting rate sits.
 */
@Composable
private fun IntakeBar(intakeCal: Long, burnCal: Long) {
    val extra = AppTheme.extra
    val top = maxOf(intakeCal, burnCal).coerceAtLeast(1).toFloat()
    val fill = intakeCal / top
    val mark = burnCal / top
    val shown = remember { Animatable(0f) }
    LaunchedEffect(fill) { shown.animateTo(fill, tween(1_000, delayMillis = 200, easing = FastOutSlowInEasing)) }
    Box(
        Modifier.fillMaxWidth().height(8.dp).drawBehind {
            val h = size.height
            val r = CornerRadius(h / 2)
            drawRoundRect(extra.onHero.copy(alpha = 0.16f), cornerRadius = r)
            val w = size.width * shown.value
            if (w > 0f) drawRoundRect(Brush.horizontalGradient(listOf(extra.onHero.copy(alpha = 0.55f), extra.onHero), endX = w), size = Size(w.coerceAtLeast(h), h), cornerRadius = r)
            if (mark < 1f) {
                val x = size.width * mark
                drawLine(extra.onHero, Offset(x, -3.dp.toPx()), Offset(x, h + 3.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
        },
    )
}

/** A pane lighter than the glass under it (darker in the dark theme), as account rows sit in their group. */
@Composable
private fun pane(): Color {
    val cs = MaterialTheme.colorScheme
    return if (cs.background.luminance() < 0.5f) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.50f)
}

private val PaneShape = RoundedCornerShape(14.dp)

/**
 * The day's entries in the order they were eaten, folded to one line of names until opened (the
 * hero already has the sum); it opens and folds on a tap of its head. Tap an entry to edit it; a
 * left swipe deletes, a right swipe records it again today (再来一份, or 记到今天 from another
 * day); both with an undo.
 */
@Composable
private fun EntriesCard(day: Energy.Day, today: LocalDate, onOpen: (FoodEntry) -> Unit, onDelete: (FoodEntry) -> Unit, onAgain: (FoodEntry) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val swipe = rememberSwipeGroup()
    val again = if (day.date == today) "再来一份" else "记到今天"
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "entriesFold")
    val any = day.entries.isNotEmpty()
    SectionCard(contentPadding = PaddingValues(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .then(if (any) Modifier.clickable(onClickLabel = if (open) "收起" else "展开") { open = !open } else Modifier)
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("吃了什么", style = MaterialTheme.typography.titleMedium)
                    if (any) Text("  ${day.entries.size} 笔", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
                if (any) {
                    Text(
                        if (open) "右滑$again · 左滑删除 · 点一下修改" else day.entries.joinToString("、", transform = ::foodShort),
                        style = MaterialTheme.typography.labelMedium, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (any) Icon(Icons.Default.ExpandMore, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp).rotate(turn))
        }
        if (!any) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("这一天还没有记录", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Text("点上方「记一笔」或往下拉，估个大概的热量就能记", style = MaterialTheme.typography.bodySmall, color = cs.outline, modifier = Modifier.padding(top = 2.dp))
            }
            return@SectionCard
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                day.entries.forEach { e ->
                    key(e.id) {
                        SwipeActionsBox(
                            key = "food:${e.id}", group = swipe, shape = PaneShape, onDelete = { onDelete(e) },
                            actions = listOf(SwipeAction(again, Icons.Outlined.ContentCopy, cs.secondary) { onAgain(e) }),
                        ) {
                            Box(Modifier.fillMaxWidth().clip(PaneShape).background(pane())) { EntryRow(e) { onOpen(e) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: FoodEntry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(cs.secondary.copy(alpha = 0.26f), cs.secondary.copy(alpha = 0.10f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(e.name?.take(1) ?: "食", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.secondary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(foodName(e), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(foodPortion(e), style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Energy.kcal(Energy.energyOf(e)), style = MaterialTheme.typography.titleMedium.merge(NumberStyle))
            Text(" kcal", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/**
 * Protein, fat and carbohydrate in grams and as shares of the energy they carry, one bar split
 * between them, and sodium. Only what was filled in is summed, and the card says so.
 */
@Composable
private fun NutrientsCard(day: Energy.Day) {
    val cs = MaterialTheme.colorScheme
    val t = day.totals
    val parts = listOf(
        Triple("蛋白质", t.proteinMg, t.proteinMg * 4) to cs.primary,
        Triple("脂肪", t.fatMg, t.fatMg * 9) to cs.secondary,
        Triple("碳水", t.carbMg, t.carbMg * 4) to cs.tertiary,
    )
    val energy = parts.sumOf { it.first.third }.coerceAtLeast(1)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(900, delayMillis = 200, easing = FastOutSlowInEasing)) }
    SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.padding(start = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("营养素", style = MaterialTheme.typography.titleMedium)
            Text("  % 为供能占比", style = MaterialTheme.typography.labelSmall, color = cs.outline, modifier = Modifier.weight(1f))
            if (t.sodiumMg > 0) Text("钠 ${Energy.milligrams(t.sodiumMg)} mg", style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = cs.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(reveal.value).height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.filter { it.first.third > 0 }.forEach { (p, color) ->
                Box(Modifier.weight(p.third.toFloat()).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.forEach { (p, color) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(color, 6.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(p.first, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("${(p.third * 100 + energy / 2) / energy}%", style = MaterialTheme.typography.labelSmall.merge(NumberStyle), color = cs.outline)
                    }
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                        Text(Energy.grams(p.second), style = MaterialTheme.typography.titleMedium.merge(NumberStyle))
                        Text("g", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
                    }
                }
            }
        }
        if (t.partial) {
            Text(
                "有几笔没填全营养素，这里只算填了的",
                style = MaterialTheme.typography.labelSmall, color = cs.outline,
                modifier = Modifier.padding(start = 2.dp, top = 8.dp),
            )
        }
    }
}

/**
 * The last seven days ending today: a bar of each day's intake with its resting rate as a dashed level,
 * nothing at all on a day without entries. Tapping a day shows it above. The average deficit
 * counts only recorded days before today, which is not over yet.
 */
@Composable
private fun WeekCard(s: FoodState, today: LocalDate, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val days = remember(s, today) { (6 downTo 0).map { s.day(today.minusDays(it.toLong())) } }
    val average = remember(s, today) { Energy.averageDeficit(today, 7, s.entries, s.profile, s.records) }
    val top = days.maxOf { maxOf(it.intakeCal, if (it.entries.isEmpty()) 0 else it.burn?.restingCal ?: 0) }.coerceAtLeast(1)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(900, delayMillis = 150, easing = FastOutSlowInEasing)) }
    val bar = cs.secondary
    val level = cs.onSurfaceVariant
    SectionCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Text("近 7 天", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                average == null -> "前 7 天还没有可比较的记录"
                average.first >= 0 -> "前 7 天记了 ${average.second} 天，平均每天缺口 ${Energy.kcal(average.first)} kcal"
                else -> "前 7 天记了 ${average.second} 天，平均每天超出基础代谢 ${Energy.kcal(-average.first)} kcal"
            },
            style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            days.forEach { d ->
                val on = d.date == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (on) bar.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { onSelect(d.date) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (d.entries.isEmpty()) " " else Energy.kcal(d.intakeCal),
                        style = MaterialTheme.typography.labelSmall.merge(NumberStyle),
                        color = if (on) cs.onSurface else cs.onSurfaceVariant, maxLines = 1,
                    )
                    Canvas(Modifier.fillMaxWidth().height(76.dp).padding(top = 4.dp)) {
                        val w = 14.dp.toPx()
                        val x = (size.width - w) / 2
                        if (d.entries.isEmpty()) {
                            drawCircle(level.copy(alpha = 0.3f), 2.dp.toPx(), Offset(size.width / 2, size.height - 2.dp.toPx()))
                        } else {
                            val h = (size.height * d.intakeCal / top * reveal.value).coerceAtLeast(3.dp.toPx())
                            drawRoundRect(
                                bar.copy(alpha = if (on) 1f else 0.5f), Offset(x, size.height - h), Size(w, h),
                                cornerRadius = CornerRadius(w / 2),
                            )
                            d.burn?.let { b ->
                                val y = size.height - size.height * b.restingCal / top
                                drawLine(
                                    level, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                                )
                            }
                        }
                    }
                    Text(
                        if (d.date == today) "今天" else Recurrence.weekName(d.date.dayOfWeek).removePrefix("周"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) bar else cs.onSurfaceVariant,
                        fontWeight = if (on) FontWeight.SemiBold else null,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 10.dp, height = 12.dp).clip(RoundedCornerShape(50)).background(bar))
                Spacer(Modifier.width(5.dp))
                Text("摄入", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(18.dp).height(8.dp)) {
                    drawLine(level, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
                }
                Spacer(Modifier.width(5.dp))
                Text("基础代谢", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
