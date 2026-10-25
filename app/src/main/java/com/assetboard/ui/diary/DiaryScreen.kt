package com.assetboard.ui.diary

import com.assetboard.domain.AppClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import com.assetboard.ui.components.PullToCreateBox
import com.assetboard.ui.components.rememberPullToCreate
import com.assetboard.ui.components.LocalAmbient
import com.assetboard.ui.components.ambient
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.rememberAmbient
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import com.assetboard.ui.components.tint
import com.assetboard.domain.Habit
import com.assetboard.domain.HabitDay
import com.assetboard.domain.Mood
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import com.assetboard.ui.components.TabHeader
import com.assetboard.ui.components.HeaderAction
import com.assetboard.ui.components.SettingsAction
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import kotlin.math.abs
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import com.assetboard.ui.notes.ExtractOrigin
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.TabPageBottomInset
import androidx.compose.animation.scaleOut
import androidx.compose.material3.FabPosition
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.outlined.ViewWeek
import com.assetboard.data.Weeks
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.SnackbarResult
import com.assetboard.data.NoteText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.data.DiaryEntry
import com.assetboard.data.DiarySession
import com.assetboard.data.Place
import com.assetboard.data.WeatherService
import com.assetboard.domain.Dates
import com.assetboard.domain.Money
import com.assetboard.domain.calendar.ChineseCalendar
import com.assetboard.domain.todo.Recurrence
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import com.assetboard.ui.components.MESSAGE_MS
import com.assetboard.ui.components.TimedVisuals
import java.time.LocalDate
import com.assetboard.ui.components.verticalScrollWithIndicator

/**
 * The diary tab: today on open, always for reading first; tapping the page brings up the
 * keyboard. 完成 saves and puts the keyboard away. [pickedDate]
 * comes from the calendar, search or a note's source. [onOpenNote] opens a note the day was
 * extracted into.
 */
@Composable
fun DiaryScreen(
    pickedDate: String?,
    onPickedHandled: () -> Unit,
    onCalendar: (LocalDate) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onStocktake: () -> Unit = {},
    /** The tab is the one shown, or being moved to; leaving it ends any typing. */
    active: Boolean = true,
    /** The text is being typed in; the shell stops tab swiping meanwhile. */
    onEditingChange: (Boolean) -> Unit = {},
    onOpenNote: (String) -> Unit = {},
    /** A week's review page, by any day of that week. */
    onOpenWeek: (LocalDate) -> Unit = {},
) {
    val container = LocalContainer.current
    val vm: DiaryViewModel = viewModel { DiaryViewModel(container) }
    val date by vm.date.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val sessionStatus = session?.status?.collectAsStateWithLifecycle()?.value
    val entry by vm.entry.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val pastMidnight by vm.pastMidnight.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var askDelete by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    val unsavedLeave by vm.unsavedLeave.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val body = session?.text?.collectAsStateWithLifecycle()?.value.orEmpty()
    /** The day and its text as they were when 提炼 was tapped: the sheet works on that snapshot. */
    var extracting by remember { mutableStateOf<Pair<LocalDate, String>?>(null) }
    // Set when the text came from a selection: then all of it is ticked to begin with.
    var extractingSelection by remember { mutableStateOf(false) }
    // What is selected in the entry right now, for the extract button over the keyboard.
    var selected by remember { mutableStateOf<String?>(null) }
    ErrorDialog(error) { vm.error.value = null }
    // The 排版 whose 撤销 is on screen, if any.
    var showingTidy by remember { mutableStateOf<DiaryViewModel.Tidied?>(null) }
    // One notice at a time and none for long: the newest replaces the one on screen, and each goes
    // by itself after a while (a snackbar with an action would otherwise stay until tapped).
    LaunchedEffect(vm) {
        merge(vm.messages.map { it to null }, vm.tidied.map { TIDIED_TEXT to it }).collectLatest { (text, t) ->
            snackbar.currentSnackbarData?.dismiss()
            // Typed on, or another day opened, before it got here: nothing left to undo.
            if (t != null && (vm.session.value !== t.session || t.session.text.value != t.after)) return@collectLatest
            val millis = if (t != null) NOTICE_MS else MESSAGE_MS
            showingTidy = t
            try {
                val result = withTimeoutOrNull(millis) { snackbar.showSnackbar(TimedVisuals(text, if (t != null) "撤销" else null, millis)) }
                if (result == null) snackbar.currentSnackbarData?.dismiss()
                if (result == SnackbarResult.ActionPerformed && t != null) vm.undoTidy(t)
            } finally {
                if (showingTidy === t) showingTidy = null
            }
        }
    }
    // The 撤销 goes as soon as it could no longer undo: typed on, another day, or the tab left.
    LaunchedEffect(showingTidy, body, session, active) {
        val t = showingTidy ?: return@LaunchedEffect
        if (!active || session !== t.session || body != t.after) snackbar.currentSnackbarData?.dismiss()
    }
    LaunchedEffect(vm) {
        vm.extracted.collect { r ->
            snackbar.currentSnackbarData?.dismiss()
            val res = withTimeoutOrNull(NOTICE_MS) { snackbar.showSnackbar(TimedVisuals(r.message, if (r.noteId != null) "打开" else null, NOTICE_MS)) }
            if (res == null) snackbar.currentSnackbarData?.dismiss()
            if (res == SnackbarResult.ActionPerformed && r.noteId != null) onOpenNote(r.noteId)
        }
    }
    LaunchedEffect(pickedDate) {
        if (pickedDate != null) {
            vm.open(LocalDate.parse(pickedDate))
            onPickedHandled()
        }
    }
    val locationAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.retryWeather() }
    LaunchedEffect(vm) { vm.askLocation.collect { locationAsk.launch(Manifest.permission.ACCESS_COARSE_LOCATION) } }
    // Save when going to the background; apply the midnight rule when coming back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> vm.flush()
                Lifecycle.Event.ON_RESUME -> vm.onResume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            vm.flush()
        }
    }
    val cs = MaterialTheme.colorScheme
    val today = AppClock.today()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val habits by remember(date) { container.habits.observeDay(date) }.collectAsStateWithLifecycle(null)
    val habitsSince by remember { container.habits.observeFirstDate() }.collectAsStateWithLifecycle(null)
    // From the first check on, every day shows its record, an empty one too; never the future.
    val showHabits = !date.isAfter(today) && (date == today || habitsSince?.let { !date.isBefore(it) } == true)
    var editing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val done: () -> Unit = {
        vm.flush()
        focusManager.clearFocus()
        keyboard?.hide()
    }
    BackHandler(editing) { done() }
    LaunchedEffect(active) { if (!active && editing) done() }
    LaunchedEffect(editing) { onEditingChange(editing) }
    // Pulling the page down past its top starts writing, as a tap on the text does.
    val editorFocus = remember { FocusRequester() }
    val pull = rememberPullToCreate(enabled = !editing && session != null && sessionStatus != DiarySession.Status.UNSAVED) {
        runCatching { editorFocus.requestFocus() }
        keyboard?.show()
    }

    val extractSelection: (String) -> Unit = { picked ->
        focusManager.clearFocus()
        extractingSelection = true
        extracting = date to picked
    }
    val ambient = rememberAmbient()
    Box(Modifier.fillMaxSize().ambient(ambient)) {
    CompositionLocalProvider(LocalAmbient provides ambient) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { GlassSnackbarHost(snackbar, Modifier.padding(bottom = TabPageBottomInset)) },
        // The system's selection menu puts app items last; this one is right above the keyboard.
        floatingActionButton = {
            val text = selected
            AnimatedVisibility(
                editing && text != null,
                enter = fadeIn() + scaleIn(initialScale = 0.8f) + slideInVertically { it / 2 },
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
                modifier = Modifier.imePadding(),
            ) {
                ExtractSelectionButton(text?.count { !it.isWhitespace() } ?: 0) { text?.let(extractSelection) }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        topBar = {
            TabHeader("日记", streakText(streak)) {
                AnimatedContent(editing, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() }, label = "actions") { on ->
                    if (on) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (body.isNotBlank()) HeaderAction(Icons.Outlined.AutoFixHigh, "一键排版", vm::tidy)
                            Row(
                                Modifier.height(42.dp).clip(RoundedCornerShape(50)).background(Brush.linearGradient(listOf(AppTheme.extra.heroEnd, AppTheme.extra.heroStart))).clickable(onClick = done).padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Check, null, tint = AppTheme.extra.onHero, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("完成", color = AppTheme.extra.onHero, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HeaderAction(Icons.Outlined.Search, "搜索", onSearch)
                            if (body.isNotBlank() && sessionStatus == DiarySession.Status.READY) HeaderAction(Icons.Outlined.AutoFixHigh, "一键排版", vm::tidy)
                            // From the session, not the entry: a save started by 完成 may not have landed yet.
                            if (entry != null && body.isNotBlank()) HeaderAction(Icons.Outlined.Lightbulb, "提炼到笔记", { extractingSelection = false; extracting = date to body })
                            // Nothing to delete while the text cannot be saved: the trash would keep the old body.
                            if (entry != null && sessionStatus != DiarySession.Status.UNSAVED) HeaderAction(Icons.Outlined.Delete, "删除这篇", { askDelete = true })
                            SettingsAction(onSettings)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Pinned: the day stays put while the page scrolls.
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val due by vm.dueWeek.collectAsStateWithLifecycle()
                due?.takeIf { !editing && date == today }?.let { w -> DuePrompt(w, today) { onOpenWeek(w) } }
                if (pastMidnight) {
                    Box(Modifier.fillMaxWidth().glass(MaterialTheme.shapes.large, glass, cs.secondary.copy(alpha = 0.12f))) {
                        Row(Modifier.padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("已过零点，这一页还是 ${date.monthValue}月${date.dayOfMonth}日", modifier = Modifier.weight(1f), color = cs.onSurface)
                            TextButton(onClick = vm::goToday) { Text("去今天") }
                        }
                    }
                }
                DayHeader(
                    vm, date, entry, compact = editing,
                    onPrev = { vm.open(date.minusDays(1)) },
                    onNext = if (date.isBefore(today)) ({ vm.open(date.plusDays(1)) }) else null,
                    onToday = if (date != today) vm::goToday else null,
                    onJump = { jump = true },
                    onCalendar = { onCalendar(date) },
                )
            }
            // Stepping a day slides the new page in from the side it lies on: a later day from the right.
            val lastDate = remember { arrayOf(date) }
            val shift = remember(date) { Animatable(lastDate[0].compareTo(date).coerceIn(-1, 1).toFloat() * -1f) }
            SideEffect { lastDate[0] = date }
            LaunchedEffect(shift) { shift.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) }
            PullToCreateBox(pull, "下拉开始写", "松手开始写", Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = shift.value * 32.dp.toPx()
                        alpha = 1f - abs(shift.value) * 0.85f
                    }
                    .verticalScrollWithIndicator(rememberScrollState()).padding(horizontal = 16.dp)
                    .padding(top = 12.dp + with(LocalDensity.current) { pull.pulled.toDp() }, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Part of the page, above the text, so it scrolls away with it; put away while writing.
                if (!editing) {
                    DayCard(
                        vm, date, today, entry,
                        habits = habits?.takeIf { showHabits && it.shown.isNotEmpty() },
                        onStocktake = onStocktake,
                        onOpenWeek = { onOpenWeek(date) },
                        onOpenNote = onOpenNote,
                    )
                }
                drafts.forEach { d ->
                    Box(Modifier.fillMaxWidth().glass(MaterialTheme.shapes.large, glass, cs.tertiary.copy(alpha = 0.12f))) {
                        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp)) {
                            Text("有一段没能保存进正文的文字（${d.reason}）", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                            Text(d.text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { vm.discardDraft(d) }) { Text("丢弃") }
                                TextButton(onClick = { vm.appendDraft(d) }) { Text("追加到正文") }
                            }
                        }
                    }
                }
                session?.let {
                    Editor(
                        it, onInput = vm::onInput, onFocus = { f -> editing = f },
                        onExtract = extractSelection,
                        onSelection = { selected = it },
                        focus = editorFocus,
                    )
                }
            }
            }
        }
    }
    }
    }

    if (askDelete) {
        ConfirmDialog(
            "删除这篇日记？", "日记会进回收站，30 天内可以恢复。", "删除", destructive = true,
            onConfirm = { askDelete = false; vm.delete() }, onDismiss = { askDelete = false },
        )
    }
    extracting?.let { (day, text) ->
        val notes by remember { container.notes.observeNotes() }.collectAsStateWithLifecycle(emptyList())
        val topics by remember { container.notes.observeTopics() }.collectAsStateWithLifecycle(emptyList())
        ExtractSheet(
            ExtractOrigin.day(day), text, notes, topics,
            fromSelection = extractingSelection,
            onDismiss = { extracting = null },
            onSaveNew = { b -> vm.extractToNew(day, b) { id -> extracting = null; onOpenNote(id) } },
            onAppend = { n, block -> extracting = null; vm.extractAppend(day, n, block) },
        )
    }
    if (jump) {
        DatePickDialog(initial = date, onDismiss = { jump = false }, onPick = { jump = false; vm.open(it) })
    }
    unsavedLeave?.let { target ->
        ConfirmDialog(
            "这一天的内容没能保存", "正文和草稿都写不进去，离开就会丢失。可以先把全文复制到剪贴板，再离开。", "复制并离开",
            onConfirm = {
                session?.let { s ->
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("日记", s.text.value))
                }
                vm.dropUnsavedAndOpen(target)
            },
            onDismiss = { vm.unsavedLeave.value = null },
        )
    }
}

/** Which list the day card has unfolded under its tiles. */
private enum class DayFold { DONE, NOTES, YEARS }

/**
 * Everything the day gathered besides the writing, on one card above it instead of around it: the
 * mood; the daily habits as the battery left them; three tiles for the stocktake, the week's
 * review and what got done; and, when there are any, the notes taken from the day and the same
 * day in earlier years. Lists unfold inside the card, one at a time. Read-only but for the mood.
 */
@Composable
private fun DayCard(
    vm: DiaryViewModel,
    date: LocalDate,
    today: LocalDate,
    entry: DiaryEntry?,
    habits: HabitDay?,
    onStocktake: () -> Unit,
    onOpenWeek: () -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val stocktakes by vm.stocktakes.collectAsStateWithLifecycle()
    val review by vm.weekReview.collectAsStateWithLifecycle()
    val completed by vm.completed.collectAsStateWithLifecycle()
    val onThisDay by vm.onThisDay.collectAsStateWithLifecycle()
    val fromDay by vm.fromDay.collectAsStateWithLifecycle()
    var open by rememberSaveable(date) { mutableStateOf<DayFold?>(null) }
    fun toggle(f: DayFold) { open = if (open == f) null else f }
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .glass(shape, glass, cs.surface.copy(alpha = 0.10f))
            .clip(shape)
            .animateContentSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (!date.isAfter(today)) MoodLine(Mood.of(entry?.mood), today = date == today) { vm.setMood(it) }
        habits?.let {
            CardRule()
            HabitGrid(it, live = date == today)
        }
        CardRule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tile = Modifier.weight(1f).fillMaxHeight()
            val last = stocktakes.lastOrNull()
            DayTile(
                Icons.Outlined.Checklist,
                if (last != null && stocktakes.size > 1) "盘点 ${stocktakes.size} 次" else "当天盘点",
                last?.let { "¥" + Money.short(it.point.netWorth) } ?: if (date == today) "去盘点" else "没有盘点",
                note = last?.let { s -> s.change?.takeIf { it != 0L }?.let { Money.format(it, signed = true) } ?: Dates.time(s.point.snapshot.at) },
                tint = cs.primary,
                muted = last == null,
                modifier = tile,
                onClick = if (last != null || date == today) onStocktake else null,
            )
            val first = review?.body?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            DayTile(
                Icons.Outlined.ViewWeek,
                Weeks.shortTitle(Weeks.start(date)) + "复盘",
                if (first != null) "已写" else "去写",
                note = first ?: Weeks.range(Weeks.start(date)),
                tint = cs.secondary,
                muted = first == null,
                modifier = tile,
                onClick = onOpenWeek,
            )
            DayTile(
                Icons.Outlined.TaskAlt,
                "完成待办",
                "${completed.size} 项",
                note = completed.firstOrNull()?.title,
                tint = cs.tertiary,
                muted = completed.isEmpty(),
                active = open == DayFold.DONE,
                modifier = tile,
                onClick = if (completed.isNotEmpty()) ({ toggle(DayFold.DONE) }) else null,
            )
        }
        if (fromDay.isNotEmpty() || onThisDay.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fromDay.isNotEmpty()) FoldChip(Icons.Outlined.AutoStories, "提炼的笔记 ${fromDay.size}", open == DayFold.NOTES) { toggle(DayFold.NOTES) }
                if (onThisDay.isNotEmpty()) FoldChip(Icons.Outlined.History, "那年今日 ${onThisDay.size}", open == DayFold.YEARS) { toggle(DayFold.YEARS) }
            }
        }
        when (open) {
            DayFold.DONE -> FoldList {
                completed.forEach { t ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(cs.tertiary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, null, tint = cs.onTertiary, modifier = Modifier.size(12.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(t.title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
            DayFold.NOTES -> FoldList {
                fromDay.forEach { n ->
                    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenNote(n.id) }.padding(vertical = 6.dp)) {
                        Text(n.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        NoteText.preview(n.body).takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            DayFold.YEARS -> FoldList {
                onThisDay.forEach { e ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { vm.open(LocalDate.parse(e.date)) }.padding(vertical = 6.dp)) {
                        Text(e.date.take(4), style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.width(48.dp))
                        Text(e.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            null -> Unit
        }
    }
}

/** A faint dashed line between the day card's parts. */
@Composable
private fun CardRule() {
    val color = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).drawBehind {
            drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        },
    )
}

/** One of the day card's three tiles: what it is, its figure, and a quiet line under it. */
@Composable
private fun DayTile(
    icon: ImageVector,
    label: String,
    value: String,
    note: String?,
    tint: Color,
    modifier: Modifier,
    muted: Boolean = false,
    active: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(tint.copy(alpha = if (active) 0.20f else 0.09f), tween(200), label = "tile")
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.merge(com.assetboard.ui.theme.NumberStyle),
            fontWeight = FontWeight.SemiBold,
            color = if (muted) tint else cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Unfolds one of the day card's lists; picked, it fills. */
@Composable
private fun FoldChip(icon: ImageVector, text: String, open: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "foldChip")
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(cs.primary.copy(alpha = if (open) 0.16f else 0.07f))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = cs.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = cs.primary)
        Icon(Icons.Default.ExpandMore, null, tint = cs.primary, modifier = Modifier.size(16.dp).rotate(turn))
    }
}

@Composable
private fun FoldList(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, start = 2.dp)) { content() }
}

/** Habits to a row in the day card: as many as fit a label of [Habit.MAX_LABEL] characters. */
private const val HABIT_COLUMNS = 3

private const val TIDIED_TEXT = "已排版：首行缩进、标点和空行都理好了"
/** How long the 撤销 of a 排版, or a note just extracted into, stays offered: long enough to read and reach. */
private const val NOTICE_MS = 4_000L

/**
 * The day's habits, read from the todo tab's battery: set in a different face, not editable here,
 * and never counted as writing. Laid out in rows of equal cells, so the names line up in columns
 * however long each is. A day shows the habits it had (see [Habit.shownOn]), so changing them
 * later leaves past days as they were.
 */
@Composable
private fun HabitGrid(day: HabitDay, live: Boolean) {
    val cs = MaterialTheme.colorScheme
    val face = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, letterSpacing = 0.5.sp)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = cs.outline, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                "每日指标 ${day.done}/${day.due}" + if (live) " · 在待办页打勾，零点后定格" else "",
                style = face.copy(fontSize = 12.sp, color = cs.onSurfaceVariant),
            )
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            day.shown.chunked(HABIT_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { h ->
                        val on = h.key in day.checked
                        val rest = !on && h.key in day.skipped
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(if (on) h.tint.copy(alpha = 0.14f) else Color.Transparent)
                                .border(1.dp, if (on) Color.Transparent else cs.outlineVariant, RoundedCornerShape(50))
                                .padding(start = 7.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (rest) {
                                Text("休", style = face.copy(fontSize = 11.sp, color = cs.outline), modifier = Modifier.width(12.dp))
                            } else {
                                Icon(if (on) Icons.Default.Check else Icons.Default.Remove, null, tint = if (on) h.tint else cs.outline, modifier = Modifier.size(12.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                h.label,
                                style = face.copy(fontSize = 12.sp, color = if (on) h.tint else cs.outline, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    repeat(HABIT_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * The day's mood as five faces on the day card's first line; tapping the chosen one again clears
 * it. Only shown here, in the calendar and on the week's page: nothing adds it up.
 */
@Composable
private fun MoodLine(mood: Mood?, today: Boolean, onPick: (Mood?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (today) "今天心情" else "这天心情", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            AnimatedContent(mood, transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith fadeOut() }, label = "moodLabel") { m ->
                Text(m?.label ?: "点一下记下来", style = MaterialTheme.typography.titleSmall, color = if (m == null) cs.outline else cs.onSurface, maxLines = 1)
            }
        }
        Mood.entries.forEach { m ->
            val chosen = m == mood
            val scale by animateFloatAsState(if (chosen) 1.22f else 1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow), label = "moodScale")
            val fade by animateFloatAsState(if (mood == null || chosen) 1f else 0.4f, tween(220), label = "moodFade")
            val ring by animateColorAsState(if (chosen) cs.primary.copy(alpha = 0.16f) else Color.Transparent, tween(220), label = "moodRing")
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(ring)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onPick(if (chosen) null else m)
                    }
                    .semantics {
                        contentDescription = "心情：" + m.label
                        selected = chosen
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(m.emoji, fontSize = 22.sp, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = fade })
            }
        }
    }
}

private fun streakText(n: Int): String = when (n) {
    0 -> "今天写下第一篇吧"
    1 -> "已连续记录 1 天"
    else -> "已连续记录 $n 天"
}

private object EXTRACT_ITEM

/** A gradient capsule over the keyboard while text is selected: one tap takes it into a note. */
@Composable
private fun ExtractSelectionButton(chars: Int, onClick: () -> Unit) {
    val extra = AppTheme.extra
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .padding(bottom = 12.dp)
            .shadow(14.dp, shape, ambientColor = extra.heroStart, spotColor = extra.heroStart)
            .clip(shape)
            .background(Brush.linearGradient(listOf(extra.heroEnd, extra.heroStart)))
            .clickable(onClick = onClick)
            .height(48.dp)
            .padding(start = 8.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(extra.onHero), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Lightbulb, null, tint = extra.heroStart, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("提炼选中的 $chars 字", color = extra.onHero, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Editor(
    session: DiarySession,
    onInput: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    /** The selected text, from 提炼到笔记 in the selection menu. */
    onExtract: (String) -> Unit = {},
    /** The text selected now, or null when nothing (or only spaces) is. */
    onSelection: (String?) -> Unit = {},
    /** Lets the page start the writing (a pull) as a tap on the text does. */
    focus: FocusRequester? = null,
) {
    val cs = MaterialTheme.colorScheme
    val text by session.text.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val savedAt by session.savedAt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Local state keeps the cursor; the session holds the text that gets saved.
    var value by remember(session) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text && status != DiarySession.Status.UNSAVED) value = value.copy(text = text, selection = TextRange(text.length))
    var focused by remember(session) { mutableStateOf(false) }
    val selection = value.selection
    val picked = if (selection.collapsed) null else value.text.substring(selection.min, selection.max).trim().takeIf { it.isNotEmpty() }
    LaunchedEffect(picked) { onSelection(picked) }
    DisposableEffect(session) { onDispose { onSelection(null) } }
    // Every day opens for reading, an empty one too: the keyboard comes up only on a tap or a pull.

    if (status == DiarySession.Status.UNSAVED) {
        Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("无法保存，请先复制内容", color = cs.onErrorContainer, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("日记", value.text))
                }) { Text("复制全部") }
            }
        }
    }
    val lineHeight = 30.sp
    val rule = cs.outlineVariant.copy(alpha = 0.7f)
    val border by animateColorAsState(if (focused) cs.primary.copy(alpha = 0.5f) else Color.Transparent, tween(220), label = "editor")
    val shape = RoundedCornerShape(26.dp)
    // The page's glass, a shade denser so the ruled lines and the text stay calm over the colour.
    Box(Modifier.fillMaxWidth().glass(shape, glass, cs.surface.copy(alpha = 0.18f)).border(1.dp, border, shape)) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp)) {
            val style = MaterialTheme.typography.bodyLarge.copy(
                color = cs.onSurface,
                lineHeight = lineHeight,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            )
            Box(
                Modifier.fillMaxWidth().heightIn(min = 270.dp).drawBehind {
                    // Faint ruled lines under the text, one per line of writing.
                    val step = lineHeight.toPx()
                    var y = step - 1.dp.toPx()
                    while (y < size.height) {
                        drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        y += step
                    }
                },
            ) {
                if (value.text.isEmpty()) Text("今天过得怎么样？", style = style.copy(color = cs.outline))
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onInput(it.text)
                    },
                    textStyle = style,
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 270.dp)
                        .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                        .onFocusChanged {
                            focused = it.isFocused
                            onFocus(it.isFocused)
                        }
                        // Selecting text offers to take just that into a note.
                        .appendTextContextMenuComponents {
                            val sel = value.selection
                            if (!sel.collapsed && value.text.substring(sel.min, sel.max).isNotBlank()) {
                                separator()
                                item(EXTRACT_ITEM, "提炼到笔记") {
                                    onExtract(value.text.substring(sel.min, sel.max).trim())
                                    close()
                                }
                            }
                        },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val count = value.text.count { !it.isWhitespace() }
                Text("$count 字", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                val label = when {
                    status != DiarySession.Status.READY -> null
                    session.dirty -> "正在保存…"
                    savedAt != null -> "已保存 " + Dates.time(savedAt!!)
                    value.text.isNotEmpty() -> "已保存"
                    else -> null
                }
                label?.let {
                    Icon(Icons.Outlined.CloudDone, null, tint = cs.outline, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = cs.outline)
                }
            }
        }
    }
}

@Composable
private fun DayHeader(
    vm: DiaryViewModel,
    date: LocalDate,
    entry: DiaryEntry?,
    compact: Boolean,
    onPrev: () -> Unit,
    onNext: (() -> Unit)?,
    onToday: (() -> Unit)?,
    onJump: () -> Unit,
    onCalendar: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val holidays by vm.holidays.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val weatherOn by LocalContainer.current.settings.weatherEnabled.collectAsStateWithLifecycle()
    val networkOn by LocalContainer.current.settings.networkEnabled.collectAsStateWithLifecycle()
    val info = remember(date) { ChineseCalendar.info(date) }
    val mark = holidays.mark(date)
    var cityPicker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(onClick = onJump), verticalAlignment = Alignment.CenterVertically) {
                // The date rolls with the step: a later day comes up from below, an earlier one from above.
                val roll: AnimatedContentTransitionScope<LocalDate>.() -> ContentTransform = {
                    val later = targetState > initialState
                    (slideInVertically(tween(280, easing = FastOutSlowInEasing)) { if (later) it / 2 else -it / 2 } + fadeIn(tween(220, delayMillis = 40))) togetherWith
                        (slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { if (later) -it / 2 else it / 2 } + fadeOut(tween(140))) using
                        SizeTransform(clip = false)
                }
                AnimatedContent(date, transitionSpec = roll, label = "day") { d ->
                    Text(
                        d.dayOfMonth.toString(),
                        style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                AnimatedContent(date, transitionSpec = roll, label = "dayInfo") { d ->
                    Column {
                        Text("${d.monthValue}月 · ${Recurrence.weekName(d.dayOfWeek)}", style = MaterialTheme.typography.titleMedium)
                        Text("${d.year} · 农历${ChineseCalendar.info(d).lunar}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            // Day stepping around the calendar: previous, the month view, back to today when away, next up to today.
            Row(
                Modifier.glass(RoundedCornerShape(50), glass, elevation = 6.dp).clip(RoundedCornerShape(50)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "前一天") }
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)).clickable(onClick = onCalendar),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.CalendarMonth, "日历", tint = cs.primary, modifier = Modifier.size(19.dp)) }
                if (onToday != null) {
                    Text("今天", style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50)).clickable(onClick = onToday).padding(horizontal = 6.dp, vertical = 8.dp))
                }
                IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "后一天") }
            }
        }
        if (!compact) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                info.solarTerm?.let { Tag(it, cs.tertiary) }
                info.festivals.forEach { Tag(it, cs.secondary) }
                mark?.let { Tag(if (it.isOffDay) "${it.name} · 休" else "${it.name} · 班", if (it.isOffDay) AppTheme.extra.gain else cs.onSurfaceVariant) }
                if (networkOn && weatherOn && entry != null && entry.body.isNotBlank()) {
                    when {
                        weather == WeatherUi.Loading -> Chip(Icons.Outlined.Cloud, "正在获取天气…", cs.onSurfaceVariant)
                        entry.weatherCode != null -> Chip(
                            weatherIcon(entry.weatherCode),
                            listOfNotNull(
                                WeatherService.describe(entry.weatherCode),
                                if (entry.tempMin != null && entry.tempMax != null) "${entry.tempMin.toInt()}~${entry.tempMax.toInt()}°C" else null,
                                entry.city,
                            ).joinToString(" · "),
                            cs.tertiary,
                        ) { cityPicker = true }
                        weather == WeatherUi.NeedCity -> Chip(Icons.Outlined.LocationOn, "定位不可用 · 选城市", cs.primary) { cityPicker = true }
                        else -> {
                            Chip(Icons.Outlined.Refresh, "天气未获取 · 重试", cs.primary) { vm.retryWeather() }
                            Chip(Icons.Outlined.LocationOn, "选城市", cs.primary) { cityPicker = true }
                        }
                    }
                }
            }
        }
    }
    if (cityPicker) CityDialog(vm, onDismiss = { cityPicker = false }) { cityPicker = false; vm.retryWeather(it) }
}

private fun weatherIcon(code: Int?): ImageVector = when (code) {
    0, 1 -> Icons.Outlined.WbSunny
    in 71..77, 85, 86 -> Icons.Outlined.AcUnit
    in 95..99 -> Icons.Outlined.Thunderstorm
    in 51..67, in 80..82 -> Icons.Outlined.WaterDrop
    else -> Icons.Outlined.Cloud
}

@Composable
private fun Chip(icon: ImageVector, text: String, color: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun CityDialog(vm: DiaryViewModel, onDismiss: () -> Unit, onPick: (Place) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选城市") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("城市名，如 上海") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { results = vm.searchCity(q) } }, enabled = q.isNotBlank()) { Text("搜索") }
                }
                results?.let { r ->
                    if (r.isEmpty()) Text("没有找到，或网络不可用", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    r.forEach { p ->
                        Text(p.city.orEmpty(), modifier = Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Monday for last week, Sunday evening for this one: a nudge while the review is empty. */
@Composable
private fun DuePrompt(week: LocalDate, today: LocalDate, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().glass(MaterialTheme.shapes.large, glass, cs.secondary.copy(alpha = 0.14f))) {
        Row(Modifier.padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ViewWeek, null, tint = cs.secondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (week == Weeks.start(today)) "这周快过完了，写写复盘？" else "上周的复盘还没写",
                color = cs.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClick) { Text("去写") }
        }
    }
}
