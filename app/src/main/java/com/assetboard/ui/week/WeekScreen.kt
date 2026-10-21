package com.assetboard.ui.week

import com.assetboard.ui.components.LocalAmbient
import androidx.compose.runtime.CompositionLocalProvider
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.outlined.EditNote
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.data.DiaryEntry
import com.assetboard.data.WeekSession
import com.assetboard.data.Weeks
import com.assetboard.domain.Dates
import com.assetboard.domain.Habit
import com.assetboard.domain.Money
import com.assetboard.domain.WeekStats
import com.assetboard.domain.health.Health
import com.assetboard.domain.todo.Recurrence
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.components.HeaderAction
import com.assetboard.ui.components.ambient
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.iconVector
import com.assetboard.ui.components.rememberAmbient
import com.assetboard.ui.components.tint
import com.assetboard.ui.diary.ExtractSheet
import com.assetboard.ui.notes.ExtractOrigin
import com.assetboard.ui.notes.NoteCard
import com.assetboard.ui.theme.AppTheme
import java.time.LocalDate
import com.assetboard.ui.components.verticalScrollWithIndicator

/**
 * A week's review, hung on the diary's timeline: the week in numbers, its seven days, what was
 * taken from them, and the review itself at the bottom, which saves as it is typed. The arrows
 * in the header card step between weeks.
 */
@Composable
fun WeekScreen(onBack: () -> Unit, onOpenDiary: (LocalDate) -> Unit, onOpenNote: (String) -> Unit) {
    val container = LocalContainer.current
    val vm: WeekViewModel = viewModel { WeekViewModel(container, createSavedStateHandle()) }
    val start by vm.start.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val taken by vm.taken.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val unsavedLeave by vm.unsavedLeave.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val status = session?.status?.collectAsStateWithLifecycle()?.value
    val text = session?.text?.collectAsStateWithLifecycle()?.value.orEmpty()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf<Pair<ExtractOrigin, String>?>(null) }
    val ambient = rememberAmbient()
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    ErrorDialog(error) { vm.error.value = null }
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(m.text, actionLabel = if (m.noteId != null) "打开" else null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed && m.noteId != null) onOpenNote(m.noteId)
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.flush() }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            vm.flush()
        }
    }
    val done: () -> Unit = {
        vm.flush()
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val go: (WeekViewModel.Leave) -> Unit = { target ->
        when (target) {
            WeekViewModel.Leave.Back -> onBack()
            is WeekViewModel.Leave.Week -> Unit
            is WeekViewModel.Leave.Diary -> onOpenDiary(target.date)
        }
    }
    val back: () -> Unit = { vm.leave(WeekViewModel.Leave.Back, go) }
    // Every way back waits for the save; while typing, back first puts the keyboard away.
    val typing = editing && status != WeekSession.Status.UNSAVED
    BackHandler(!typing) { back() }
    BackHandler(typing) { done() }
    val thisWeek = Weeks.start(vm.today())

    CompositionLocalProvider(LocalAmbient provides ambient) {
    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.ambient(ambient),
        snackbarHost = { GlassSnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderAction(Icons.AutoMirrored.Filled.ArrowBack, "返回", back)
                Spacer(Modifier.width(12.dp))
                Text("周复盘", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                AnimatedContent(editing, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() }, label = "actions") { on ->
                    if (on) {
                        DoneButton(done)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // The review sits at the bottom, under the week it looks back on: this goes straight there.
                            HeaderAction(Icons.Outlined.EditNote, "写复盘", {
                                scope.launch {
                                    scroll.animateScrollTo(scroll.maxValue)
                                    runCatching { focus.requestFocus() }
                                }
                            })
                            if (text.isNotBlank()) HeaderAction(Icons.Outlined.Lightbulb, "提炼到笔记", { extracting = ExtractOrigin.week(start) to text })
                            if (review != null && status != WeekSession.Status.UNSAVED) HeaderAction(Icons.Outlined.Delete, "删除这篇复盘", { askDelete = true })
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScrollWithIndicator(scroll)
                .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WeekHero(
                start, stats,
                onPrev = { vm.go(start.minusWeeks(1)) },
                onNext = if (start < thisWeek) ({ vm.go(start.plusWeeks(1)) }) else null,
            )
            if (!editing) {
                Section("这一周的日记") {
                    DayList(start, vm.today(), days, onOpenDiary = { d -> vm.leave(WeekViewModel.Leave.Diary(d), go) }, onExtract = { d, t -> extracting = ExtractOrigin.day(d) to t })
                }
                Section(if (taken.isEmpty()) "从这周提炼的笔记" else "从这周提炼的笔记 · ${taken.size}") {
                    if (taken.isEmpty()) {
                        Text(
                            "在日记页或下方复盘里点灯泡，把值得留下的段落提炼成笔记，就会出现在这里。",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            taken.forEach { n -> NoteCard(n, topics, { onOpenNote(n.id) }) }
                        }
                    }
                }
            }
            drafts.forEach { d ->
                Surface(color = cs.tertiaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp)) {
                        Text("有一段没能保存进复盘的文字（${d.reason}）", style = MaterialTheme.typography.titleSmall, color = cs.onTertiaryContainer)
                        Text(d.text, style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { vm.discardDraft(d) }) { Text("丢弃") }
                            TextButton(onClick = { vm.appendDraft(d) }) { Text("追加到正文") }
                        }
                    }
                }
            }
            Section("复盘") {
                session?.let { s -> ReviewEditor(s, focus, onInput = vm::onInput, onFocus = { editing = it }) }
            }
        }
    }
    }

    extracting?.let { (origin, body) ->
        val notes by vm.allNotes.collectAsStateWithLifecycle()
        ExtractSheet(
            origin, body, notes, topics,
            onDismiss = { extracting = null },
            onSaveNew = { b -> vm.extractToNew(origin.source, b) { id -> extracting = null; onOpenNote(id) } },
            onAppend = { n, block -> extracting = null; vm.extractAppend(origin.source, n, block) },
        )
    }
    if (askDelete) {
        ConfirmDialog(
            "删除这篇复盘？", "复盘会进回收站，30 天内可以恢复。", "删除", destructive = true,
            onConfirm = { askDelete = false; vm.delete() }, onDismiss = { askDelete = false },
        )
    }
    unsavedLeave?.let {
        ConfirmDialog(
            "这周的复盘没能保存", "正文和草稿都写不进去，离开就会丢失。可以先把全文复制到剪贴板，再离开。", "复制并离开",
            onConfirm = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("周复盘", text))
                vm.leaveUnsaved(go)
            },
            onDismiss = { vm.unsavedLeave.value = null },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        content()
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    val extra = AppTheme.extra
    Row(
        Modifier.height(42.dp).clip(RoundedCornerShape(50)).background(Brush.linearGradient(listOf(extra.heroEnd, extra.heroStart))).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Check, null, tint = extra.onHero, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("完成", color = extra.onHero, style = MaterialTheme.typography.labelLarge)
    }
}

/** The week's title, the arrows to its neighbours, and its numbers. */
@Composable
private fun WeekHero(start: LocalDate, stats: WeekStats?, onPrev: () -> Unit, onNext: (() -> Unit)?) {
    val extra = AppTheme.extra
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(extra.heroStart, extra.heroEnd)))
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * 0.7f), size.width * 0.7f, Offset(size.width, 0f))
            }
            .padding(horizontal = 8.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一周", tint = extra.onHero) }
            AnimatedContent(start, transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith fadeOut()
            }, label = "week", modifier = Modifier.weight(1f)) { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(Weeks.title(s), style = MaterialTheme.typography.titleLarge, color = extra.onHero)
                    Text(Weeks.range(s), style = MaterialTheme.typography.bodyMedium, color = extra.onHeroMuted)
                }
            }
            IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一周", tint = if (onNext != null) extra.onHero else extra.onHero.copy(alpha = 0.3f))
            }
        }
        Spacer(Modifier.height(12.dp))
        val s = stats
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("日记", if (s == null) "…" else if (s.diaryOf == 0) "–" else "${s.diaryDays}/${s.diaryOf}", "天")
            Tile("完成待办", s?.todosDone?.toString() ?: "…", "件")
        }
        if (s != null && (s.hasWeights || s.netWorthNow != null)) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.hasWeights) Tile("体重", s.weightChangeG?.let { Health.kgDelta(it) } ?: "记录不足", if (s.weightChangeG != null) "kg" else "")
                if (s.netWorthNow != null) Tile("净资产", s.netWorthChange?.let { (if (it >= 0) "+" else "") + Money.short(it) } ?: "首次盘点", "")
            }
        }
        if (s != null) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                s.habits.forEach { (h, n) -> HabitCount(h, n, s.diaryOf.coerceAtLeast(1), compact = s.habits.size > 6) }
            }
        }
    }
}

@Composable
private fun RowScope.Tile(label: String, value: String, unit: String) {
    val extra = AppTheme.extra
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = extra.onHeroMuted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = extra.onHero, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodySmall, color = extra.onHeroMuted, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** A habit's icon over how many days it was ticked; a full week glows. */
@Composable
private fun HabitCount(h: Habit, n: Int, of: Int, compact: Boolean) {
    val extra = AppTheme.extra
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(if (compact) 34.dp else 38.dp).clip(CircleShape).background(if (n > 0) h.tint.copy(alpha = 0.28f + 0.5f * (n.toFloat() / of).coerceAtMost(1f)) else Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(h.iconVector, h.label, tint = if (n > 0) Color.White else extra.onHeroMuted, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("$n 天", style = MaterialTheme.typography.labelSmall, color = extra.onHeroMuted)
    }
}

/** Monday to Sunday: the first line of each day, the whole entry when opened. */
@Composable
private fun DayList(start: LocalDate, today: LocalDate, entries: List<DiaryEntry>, onOpenDiary: (LocalDate) -> Unit, onExtract: (LocalDate, String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable(start) { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().glass(shape, glass).clip(shape).padding(vertical = 6.dp)) {
        Weeks.days(start).forEach { d ->
            val body = entries.firstOrNull { it.date == d.toString() }?.body?.takeIf { it.isNotBlank() }
            val future = d.isAfter(today)
            val expanded = open == d.toString() && body != null
            Column(
                Modifier.fillMaxWidth().clickable(enabled = !future) { if (body == null) onOpenDiary(d) else open = if (expanded) null else d.toString() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(54.dp)) {
                        Text(Recurrence.weekName(d.dayOfWeek), style = MaterialTheme.typography.labelLarge, color = if (d == today) cs.primary else if (future) cs.outline else cs.onSurface)
                        Text("${d.monthValue}/${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    Text(
                        body?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: if (future) "" else "没写",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (body != null) cs.onSurface else cs.outline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (body == null && !future) Text("去写", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                }
                AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(start = 54.dp, top = 6.dp)) {
                        Text(body.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onExtract(d, body.orEmpty()) }) {
                                Icon(Icons.Outlined.Lightbulb, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("提炼")
                            }
                            TextButton(onClick = { onOpenDiary(d) }) { Text("打开这天") }
                        }
                    }
                }
            }
        }
    }
}

/** The review text on a sheet of paper; three questions wait in it while it is empty. */
@Composable
private fun ReviewEditor(session: WeekSession, focus: FocusRequester, onInput: (String) -> Unit, onFocus: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val text by session.text.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val savedAt by session.savedAt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var value by remember(session) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text && status != WeekSession.Status.UNSAVED) value = value.copy(text = text, selection = TextRange(text.length))
    var focused by remember(session) { mutableStateOf(false) }

    if (status == WeekSession.Status.UNSAVED) {
        Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("无法保存，请先复制内容", color = cs.onErrorContainer, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("周复盘", value.text))
                }) { Text("复制全部") }
            }
        }
    }
    val border by animateColorAsState(if (focused) cs.primary.copy(alpha = 0.45f) else Color.Transparent, tween(220), label = "editor")
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = cs.onSurface,
        lineHeight = 29.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = cs.surfaceContainer,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 12.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp)) {
                if (value.text.isEmpty()) {
                    Text(PROMPTS, style = style.copy(color = cs.outline))
                }
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onInput(it.text)
                    },
                    textStyle = style,
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).focusRequester(focus).onFocusChanged {
                        focused = it.isFocused
                        onFocus(it.isFocused)
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val count = value.text.count { !it.isWhitespace() }
                Text("$count 字", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                val label = when {
                    status != WeekSession.Status.READY -> null
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

private const val PROMPTS = "这周做成了什么？\n\n哪些没做好，为什么？\n\n下周最重要的是什么？"
