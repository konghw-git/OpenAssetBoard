package com.assetboard.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.ui.components.GlassSnackbarHost
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import com.assetboard.data.NoteSession
import com.assetboard.data.NoteTopic
import com.assetboard.data.SourceRef
import com.assetboard.domain.Dates
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.HeaderAction
import com.assetboard.ui.theme.AppTheme
import java.time.LocalDate
import com.assetboard.ui.components.verticalScrollWithIndicator

/**
 * One note, on its own page above the tabs. An existing note opens for reading and a tap brings
 * up the keyboard; a new one starts typing at once. The text saves itself as on the diary page.
 */
@Composable
fun NoteScreen(onBack: () -> Unit, onOpenDiary: (LocalDate) -> Unit, onOpenWeek: (LocalDate) -> Unit = {}) {
    val container = LocalContainer.current
    val vm: NoteViewModel = viewModel { NoteViewModel(container, createSavedStateHandle()) }
    val session by vm.session.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val pendingTopic by vm.pendingTopic.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val noteId by vm.noteId.collectAsStateWithLifecycle()
    val unsavedLeave by vm.unsavedLeave.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val status = session?.status?.collectAsStateWithLifecycle()?.value
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var askDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    ErrorDialog(error) { vm.error.value = null }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Save when going to the background or leaving.
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
    val go: (NoteViewModel.Leave) -> Unit = { target ->
        when (target) {
            NoteViewModel.Leave.Back -> onBack()
            is NoteViewModel.Leave.Diary -> onOpenDiary(target.date)
        }
    }
    fun leave(target: NoteViewModel.Leave) = vm.leave(target, go)
    // Every way back waits for the save; while typing, back first puts the keyboard away.
    val typing = editing && status != NoteSession.Status.UNSAVED
    BackHandler(!typing) { leave(NoteViewModel.Leave.Back) }
    BackHandler(typing) { done() }

    val cs = MaterialTheme.colorScheme
    val exists = note != null && note?.deletedAt == null
    val topicId = if (exists) note?.topicId else pendingTopic
    GlassScaffold(
        snackbarHost = { GlassSnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderAction(Icons.AutoMirrored.Filled.ArrowBack, "返回", { leave(NoteViewModel.Leave.Back) })
                Spacer(Modifier.weight(1f))
                if (!missing) {
                    TopicChip(topics, topicId, onPick = vm::setTopic, onCreate = vm::createTopic)
                    Spacer(Modifier.width(8.dp))
                    AnimatedContent(editing, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() }, label = "actions") { on ->
                        if (on) {
                            DoneButton(done)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (exists) {
                                    val pinned = note?.pinned == true
                                    HeaderAction(
                                        if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                        if (pinned) "取消置顶" else "置顶",
                                        vm::togglePin,
                                        tint = if (pinned) cs.secondary else cs.onSurface,
                                    )
                                    if (status != NoteSession.Status.UNSAVED) HeaderAction(Icons.Outlined.Delete, "删除这篇", { askDelete = true })
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (missing) {
            Gone(Modifier.padding(padding), onBack)
            return@GlassScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScrollWithIndicator(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            session?.let { s ->
                Editor(s, autoFocus = noteId == null, onInput = vm::onInput, onFocus = { editing = it })
            }
            if (sources.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("摘自", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sources.forEach { src ->
                            val d = runCatching { LocalDate.parse(src.ref) }.getOrNull() ?: return@forEach
                            when (src.kind) {
                                SourceRef.DAY -> SourceChip(d) { leave(NoteViewModel.Leave.Diary(d)) }
                                // The week page goes on top of this one: nothing here is left behind.
                                SourceRef.WEEK -> WeekSourceChip(d) { onOpenWeek(d) }
                            }
                        }
                    }
                }
            }
            note?.takeIf { exists }?.let { n ->
                Text(
                    "创建于 ${Dates.date(n.createdAt)} · 更新于 ${whenLabel(n.updatedAt)}",
                    style = MaterialTheme.typography.labelMedium, color = cs.outline,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (askDelete) {
        ConfirmDialog(
            "删除这篇笔记？", "笔记会进回收站，30 天内可以恢复。", "删除", destructive = true,
            onConfirm = { askDelete = false; vm.delete(onDone = onBack) }, onDismiss = { askDelete = false },
        )
    }
    unsavedLeave?.let { target ->
        ConfirmDialog(
            "这篇笔记的内容没能保存", "正文和草稿都写不进去，离开就会丢失。可以先把全文复制到剪贴板，再离开。", "复制并离开",
            onConfirm = {
                session?.let { s -> context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("笔记", s.text.value)) }
                vm.unsavedLeave.value = null
                go(target)
            },
            onDismiss = { vm.unsavedLeave.value = null },
        )
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

/** The note's topic; tapping it opens every topic in a sheet, with 未归类 and a new one. */
@Composable
private fun TopicChip(topics: List<NoteTopic>, topicId: String?, onPick: (String?) -> Unit, onCreate: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val topic = topics.firstOrNull { it.id == topicId }
    val color = topicColor(topics, topic?.id)
    Box {
        Row(
            Modifier
                .height(42.dp)
                .glass(RoundedCornerShape(50), glass, if (topic != null) color.copy(alpha = 0.12f) else Color.Unspecified, elevation = 6.dp)
                .clip(RoundedCornerShape(50))
                .clickable { open = true }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (topic != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            } else {
                Icon(Icons.Outlined.FolderOpen, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(7.dp))
            Text(
                topic?.name ?: "未归类",
                style = MaterialTheme.typography.labelLarge,
                color = if (topic != null) color else cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp),
            )
            Icon(Icons.Default.ExpandMore, "选择主题", tint = if (topic != null) color else cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
    if (open) {
        TopicSheet(
            "放到哪个主题", topics, null, topicId ?: NotesViewModel.UNSORTED, showAll = false,
            onPick = { k -> open = false; onPick(k.takeIf { it != NotesViewModel.UNSORTED }) },
            onCreate = { name -> open = false; onCreate(name) },
            onDismiss = { open = false },
        )
    }
}

/** One name field; the repository has the last word on what is allowed. */
@Composable
fun TopicNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it.take(com.assetboard.data.NoteRepository.TOPIC_NAME_MAX) },
                label = { Text("主题名") }, placeholder = { Text("比如 投资、健身、读书") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * The writing surface: a sheet of paper whose first line reads as the title. Local state keeps
 * the cursor; the session holds the text that gets saved.
 */
@Composable
private fun Editor(session: NoteSession, autoFocus: Boolean, onInput: (String) -> Unit, onFocus: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val text by session.text.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val savedAt by session.savedAt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var value by remember(session) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text && status != NoteSession.Status.UNSAVED) value = value.copy(text = text, selection = TextRange(text.length))
    var focused by remember(session) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // A new note is for writing: the keyboard comes up at once. An existing one opens for reading.
    LaunchedEffect(session) { if (autoFocus && session.noteId == null) runCatching { focus.requestFocus() } }

    if (status == NoteSession.Status.UNSAVED) {
        Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("无法保存，请先复制内容", color = cs.onErrorContainer, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("笔记", value.text))
                }) { Text("复制全部") }
            }
        }
    }
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = cs.onSurface,
        fontSize = 17.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    val look = remember(cs.primary) { NoteBodyLook(24.sp, cs.primary) }
    // The words straight on the page, no sheet around them; while typing, a slim bar of the
    // accent grows down the margin.
    val mark by animateFloatAsState(if (focused) 1f else 0f, tween(com.assetboard.ui.components.Motion.MEDIUM, easing = com.assetboard.ui.components.Motion.Emphasized), label = "editor")
    val accent = cs.primary
    Box(
        Modifier.fillMaxWidth().drawBehind {
            if (mark > 0f) {
                val x = 1.5.dp.toPx()
                drawLine(
                    Brush.verticalGradient(listOf(accent.copy(alpha = 0.7f), accent.copy(alpha = 0f)), endY = size.height * mark),
                    Offset(x, 8.dp.toPx()), Offset(x, 8.dp.toPx() + (size.height - 16.dp.toPx()) * mark),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 360.dp)) {
                if (value.text.isEmpty()) {
                    Column {
                        Text("标题", style = style.copy(color = cs.outline, fontSize = 24.sp, fontWeight = FontWeight.SemiBold))
                        Text("第一行是标题，接着写下你的想法。#标签 会高亮。", style = style.copy(color = cs.outline))
                    }
                }
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onInput(it.text)
                    },
                    textStyle = style,
                    visualTransformation = look,
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp).focusRequester(focus).onFocusChanged {
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
                    status != NoteSession.Status.READY -> null
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
private fun Gone(modifier: Modifier, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().navigationBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SearchOff, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("这篇笔记已不在了", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("它可能被删除了，30 天内可以在 设置 › 回收站 里恢复。", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("返回") }
    }
}
