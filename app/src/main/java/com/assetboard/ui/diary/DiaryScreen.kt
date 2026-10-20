package com.assetboard.ui.diary

import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import com.assetboard.ui.components.tint
import com.assetboard.domain.Habit
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import com.assetboard.ui.components.TabHeader
import com.assetboard.ui.components.HeaderAction
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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.SnackbarHost
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
import java.time.LocalDate

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
    /** The text is being typed in; the shell stops tab swiping meanwhile. */
    onEditingChange: (Boolean) -> Unit = {},
    onOpenNote: (String) -> Unit = {},
) {
    val container = LocalContainer.current
    val vm: DiaryViewModel = viewModel { DiaryViewModel(container) }
    val date by vm.date.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
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
    ErrorDialog(error) { vm.error.value = null }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) {
        vm.extracted.collect { r ->
            val res = snackbar.showSnackbar(r.message, actionLabel = if (r.noteId != null) "打开" else null, withDismissAction = r.noteId == null)
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
    val today = LocalDate.now()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val habits by remember(date) { container.habits.observe(date) }.collectAsStateWithLifecycle(emptySet())
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
    LaunchedEffect(editing) { onEditingChange(editing) }

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TabHeader("日记", streakText(streak)) {
                AnimatedContent(editing, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() }, label = "actions") { on ->
                    if (on) {
                        Row(
                            Modifier.height(42.dp).clip(RoundedCornerShape(50)).background(Brush.linearGradient(listOf(AppTheme.extra.heroEnd, AppTheme.extra.heroStart))).clickable(onClick = done).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Check, null, tint = AppTheme.extra.onHero, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("完成", color = AppTheme.extra.onHero, style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HeaderAction(Icons.Outlined.Search, "搜索", onSearch)
                            // From the session, not the entry: a save started by 完成 may not have landed yet.
                            if (entry != null && body.isNotBlank()) HeaderAction(Icons.Outlined.Lightbulb, "提炼到笔记", { extractingSelection = false; extracting = date to body })
                            if (entry != null) HeaderAction(Icons.Outlined.Delete, "删除这篇", { askDelete = true })
                            HeaderAction(Icons.Outlined.Settings, "设置", onSettings)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Pinned: the day and its stocktake stay put while the text scrolls.
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pastMidnight) {
                    Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("已过零点，这一页还是 ${date.monthValue}月${date.dayOfMonth}日", modifier = Modifier.weight(1f), color = cs.onSecondaryContainer)
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
                StocktakeStrip(vm, date, today, onStocktake)
            }
            // Stepping a day slides the new page in from the side it lies on: a later day from the right.
            val lastDate = remember { arrayOf(date) }
            val shift = remember(date) { Animatable(lastDate[0].compareTo(date).coerceIn(-1, 1).toFloat() * -1f) }
            SideEffect { lastDate[0] = date }
            LaunchedEffect(shift) { shift.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) }
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = shift.value * 32.dp.toPx()
                        alpha = 1f - abs(shift.value) * 0.85f
                    }
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                drafts.forEach { d ->
                    Surface(color = cs.tertiaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp)) {
                            Text("有一段没能保存进正文的文字（${d.reason}）", style = MaterialTheme.typography.titleSmall, color = cs.onTertiaryContainer)
                            Text(d.text, style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 10.dp))
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
                        onExtract = { picked ->
                            focusManager.clearFocus()
                            extractingSelection = true
                            extracting = date to picked
                        },
                    ) {
                        if (showHabits) HabitRecord(habits, live = date == today)
                    }
                }
                if (!editing) DayExtras(vm, onOpenNote)
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
            day, text, notes, topics,
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

/**
 * The day's five habits at the head of the page, read from the todo tab's battery: set in a
 * different face, not part of the text, not editable here, and never counted as writing.
 */
@Composable
private fun HabitRecord(checked: Set<Habit>, live: Boolean) {
    val cs = MaterialTheme.colorScheme
    val face = TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, letterSpacing = 0.5.sp)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = cs.outline, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                "每日指标 ${checked.size}/${Habit.entries.size}" + if (live) " · 在待办页打勾，零点后定格" else "",
                style = face.copy(fontSize = 12.sp, color = cs.onSurfaceVariant),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            Habit.entries.forEach { h ->
                val on = h in checked
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) h.tint.copy(alpha = 0.14f) else Color.Transparent)
                        .border(1.dp, if (on) Color.Transparent else cs.outlineVariant, RoundedCornerShape(50))
                        .padding(start = 5.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (on) Icons.Default.Check else Icons.Default.Remove, null, tint = if (on) h.tint else cs.outline, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(h.label, style = face.copy(fontSize = 12.sp, color = if (on) h.tint else cs.outline, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                }
            }
        }
        Box(
            Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).drawBehind {
                drawLine(cs.outlineVariant, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            },
        )
    }
}

private fun streakText(n: Int): String = when (n) {
    0 -> "今天写下第一篇吧"
    1 -> "已连续记录 1 天"
    else -> "已连续记录 $n 天"
}

private object EXTRACT_ITEM

@Composable
private fun Editor(
    session: DiarySession,
    onInput: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    /** The selected text, from 提炼到笔记 in the selection menu. */
    onExtract: (String) -> Unit = {},
    header: @Composable () -> Unit = {},
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
    // Every day opens for reading, an empty one too: the keyboard comes up only on a tap.

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
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = cs.surfaceContainer,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp)) {
            header()
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
                Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceContainer),
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

/**
 * The day's stocktake, pinned under the date: net worth and the change since the one before.
 * Today without one offers to start it.
 */
@Composable
private fun StocktakeStrip(vm: DiaryViewModel, date: LocalDate, today: LocalDate, onStocktake: () -> Unit) {
    val stocktakes by vm.stocktakes.collectAsStateWithLifecycle()
    val extra = AppTheme.extra
    val cs = MaterialTheme.colorScheme
    val last = stocktakes.lastOrNull()
    if (last == null && date != today) return
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (last != null) Brush.linearGradient(listOf(extra.heroStart, extra.heroEnd)) else SolidColor(cs.surfaceContainer))
            .clickable(onClick = onStocktake)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (last != null) extra.onHero else cs.onSurface
        val muted = if (last != null) extra.onHeroMuted else cs.onSurfaceVariant
        Box(Modifier.size(34.dp).clip(CircleShape).background(if (last != null) fg.copy(alpha = 0.14f) else cs.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Checklist, null, tint = if (last != null) fg else cs.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        if (last == null) {
            Column(Modifier.weight(1f)) {
                Text("今天还没盘点", style = MaterialTheme.typography.titleSmall, color = fg)
                Text("盘点后这里显示当天的净资产", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("去盘点", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.primary)
        } else {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (stocktakes.size > 1) "当天盘点 ${stocktakes.size} 次 · 最后 " else "当天盘点 · ") + Dates.time(last.point.snapshot.at),
                    style = MaterialTheme.typography.labelMedium, color = muted,
                )
                Text("¥" + Money.format(last.point.netWorth), style = MaterialTheme.typography.titleMedium.merge(com.assetboard.ui.theme.NumberStyle), color = fg)
            }
            last.change?.takeIf { it != 0L }?.let { c ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(fg.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (c > 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, tint = fg, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(Money.format(c, signed = true), style = MaterialTheme.typography.labelMedium.merge(com.assetboard.ui.theme.NumberStyle), color = fg)
                }
            }
        }
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

/** Read-only: what got done that day, the notes taken from it and the same day in earlier years. */
@Composable
private fun DayExtras(vm: DiaryViewModel, onOpenNote: (String) -> Unit) {
    val completed by vm.completed.collectAsStateWithLifecycle()
    val onThisDay by vm.onThisDay.collectAsStateWithLifecycle()
    val fromDay by vm.fromDay.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    if (completed.isNotEmpty()) {
        Fold(Icons.Outlined.TaskAlt, "完成的待办", completed.size) {
            completed.forEach { t ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(t.title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
    if (fromDay.isNotEmpty()) {
        Fold(Icons.Outlined.AutoStories, "从这天提炼的笔记", fromDay.size) {
            fromDay.forEach { n ->
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenNote(n.id) }.padding(vertical = 8.dp)) {
                    Text(n.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    NoteText.preview(n.body).takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (onThisDay.isNotEmpty()) {
        Fold(Icons.Outlined.History, "那年今日", onThisDay.size) {
            onThisDay.forEach { e ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { vm.open(LocalDate.parse(e.date)) }.padding(vertical = 8.dp)) {
                    Text(e.date.take(4), style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.width(48.dp))
                    Text(e.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Fold(icon: ImageVector, title: String, count: Int, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "fold")
    SectionCard(contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = cs.primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Icon(Icons.Default.ExpandMore, null, tint = cs.outline, modifier = Modifier.padding(start = 4.dp).size(20.dp).rotate(turn))
        }
        AnimatedVisibility(open) { Column(Modifier.padding(start = 4.dp, bottom = 8.dp)) { content() } }
    }
}
