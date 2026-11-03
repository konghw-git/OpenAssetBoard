package com.assetboard.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.ui.components.GlassSnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import com.assetboard.data.NoteSession
import com.assetboard.data.NoteTopic
import com.assetboard.data.SourceRef
import com.assetboard.domain.AiAction
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
    var outline by rememberSaveable { mutableStateOf(false) }
    val editorState = remember(vm) { NoteEditorState(vm::onInput) }
    val aiBusy by vm.aiBusy.collectAsStateWithLifecycle()
    // The AI passes act on the selection when there is one, the whole body otherwise; the result
    // lands as one undo step on the editor state.
    val runAi: (AiAction) -> Unit = { kind ->
        val v = editorState.value
        vm.aiEdit(kind, v.text, v.selection.start, v.selection.end) { editorState.apply(it) }
    }
    val scroll = rememberScrollState()
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().imePadding().verticalScrollWithIndicator(scroll)
                    .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = if (editing) 112.dp else 80.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                session?.let { s ->
                    NoteEditor(s, editorState, autoFocus = noteId == null, scrollState = scroll, onFocus = { editing = it })
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
            AnimatedVisibility(
                visible = editing,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 2 },
            ) {
                NoteFormatBar(editorState, onAi = runAi, aiBusy = aiBusy)
            }
            AnimatedVisibility(
                visible = !editing,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 14.dp),
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 },
                exit = fadeOut(tween(120)),
            ) {
                NoteOutlinePill { outline = true }
            }
        }
    }

    if (outline) {
        NoteOutlineSheet(
            doc = editorState.document(),
            onJump = { offset -> outline = false; editorState.jumpTo?.invoke(offset) },
            onDismiss = { outline = false },
        )
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
    // A full-screen window behind a fixed panel: in the default wrap-content dialog window a text
    // field measures in a loop forever, which also keeps a test from ever seeing the dialog idle.
    // A tap outside the panel still dismisses: the dialog hit-tests its content, not its window.
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(328.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it.take(com.assetboard.data.NoteRepository.TOPIC_NAME_MAX) },
                label = { Text("主题名") }, placeholder = { Text("比如 投资、健身、读书") }, singleLine = true,
                modifier = Modifier.width(280.dp).focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
