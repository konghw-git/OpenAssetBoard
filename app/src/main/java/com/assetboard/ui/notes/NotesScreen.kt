package com.assetboard.ui.notes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.AppContainer
import com.assetboard.data.Note
import com.assetboard.data.NoteDraft
import com.assetboard.data.NoteTopic
import com.assetboard.domain.Dates
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.HeaderAction
import com.assetboard.ui.components.PrimaryActionButton
import com.assetboard.ui.components.TabHeader
import com.assetboard.ui.components.appear
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class NotesViewModel(container: AppContainer) : ActionViewModel(container) {
    private val notes = container.notes
    val topics: StateFlow<List<NoteTopic>> = notes.observeTopics().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val all: StateFlow<List<Note>?> = notes.observeNotes().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val drafts: StateFlow<List<NoteDraft>> = notes.observeDrafts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** [ALL], [UNSORTED] or a topic id; kept while the tab is swiped away. */
    val filter = MutableStateFlow(ALL)

    fun draftToNote(d: NoteDraft, onDone: (String) -> Unit) = action { onDone(notes.draftToNote(d.id)) }
    fun discardDraft(d: NoteDraft) = action { notes.discardDraft(d.id) }

    companion object {
        const val ALL = "*"
        const val UNSORTED = "-"
    }
}

/**
 * The notes tab: only a list. Writing happens on its own page and searching on another, so
 * nothing here takes the keyboard and swiping between tabs never fights with typing.
 */
@Composable
fun NotesScreen(
    onOpen: (String) -> Unit,
    onNew: (topicId: String?) -> Unit,
    onSearch: () -> Unit,
    onTopics: () -> Unit,
    onSettings: () -> Unit,
) {
    val container = LocalContainer.current
    val vm: NotesViewModel = viewModel { NotesViewModel(container) }
    val topics by vm.topics.collectAsStateWithLifecycle()
    val all by vm.all.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val filterRaw by vm.filter.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var draftsOpen by rememberSaveable { mutableStateOf(false) }
    ErrorDialog(error) { vm.error.value = null }
    val cs = MaterialTheme.colorScheme
    // A topic deleted meanwhile falls back to everything.
    val filter = if (filterRaw == NotesViewModel.ALL || filterRaw == NotesViewModel.UNSORTED || topics.any { it.id == filterRaw }) filterRaw else NotesViewModel.ALL
    val notes = all.orEmpty()
    val shown = remember(notes, filter) {
        when (filter) {
            NotesViewModel.ALL -> notes
            NotesViewModel.UNSORTED -> notes.filter { it.topicId == null }
            else -> notes.filter { it.topicId == filter }
        }
    }
    val wash = AppTheme.extra.heroStart

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .drawBehind { drawRect(Brush.verticalGradient(listOf(wash.copy(alpha = 0.07f), Color.Transparent), endY = 360.dp.toPx())) },
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TabHeader("笔记", subtitle(notes.size, topics.size)) {
                    HeaderAction(Icons.Outlined.Search, "搜索笔记", onSearch)
                    HeaderAction(Icons.Outlined.Settings, "设置", onSettings)
                }
            },
        ) { padding ->
            val list = all
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "topics") {
                    TopicBar(topics, notes, filter, onPick = { vm.filter.value = it }, onManage = onTopics, modifier = Modifier.appear(0))
                }
                if (drafts.isNotEmpty()) {
                    item(key = "drafts") { DraftBanner(drafts.size, Modifier.animateItem()) { draftsOpen = true } }
                }
                when {
                    list == null -> Unit
                    notes.isEmpty() -> item(key = "empty") { EmptyNotes(Modifier.appear(1)) }
                    shown.isEmpty() -> item(key = "emptyTopic") {
                        Text(
                            if (filter == NotesViewModel.UNSORTED) "所有笔记都有主题了" else "这个主题下还没有笔记，点下方「写笔记」写第一篇",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                        )
                    }
                    else -> {
                        val pinned = shown.filter { it.pinned }
                        val rest = shown.filterNot { it.pinned }
                        val showTopic = filter == NotesViewModel.ALL
                        if (pinned.isNotEmpty()) {
                            item(key = "pinnedLabel") { Label("置顶", Modifier.animateItem()) }
                            items(pinned, key = { it.id }) { n ->
                                NoteCard(n, topics, { onOpen(n.id) }, Modifier.animateItem(), showTopic = showTopic)
                            }
                            if (rest.isNotEmpty()) item(key = "restLabel") { Label("最近更新", Modifier.animateItem()) }
                        }
                        items(rest, key = { it.id }) { n ->
                            NoteCard(n, topics, { onOpen(n.id) }, Modifier.animateItem(), showTopic = showTopic)
                        }
                    }
                }
            }
        }
        PrimaryActionButton(
            Icons.Outlined.Edit, "写笔记",
            onClick = { onNew(filter.takeIf { it != NotesViewModel.ALL && it != NotesViewModel.UNSORTED }) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        )
    }

    if (draftsOpen) {
        DraftsSheet(
            drafts, notes,
            onDismiss = { draftsOpen = false },
            onToNote = { d -> vm.draftToNote(d) { id -> draftsOpen = false; onOpen(id) } },
            onOpenNote = { id -> draftsOpen = false; onOpen(id) },
            onDiscard = vm::discardDraft,
        )
    }
}

private fun subtitle(notes: Int, topics: Int): String = when {
    notes == 0 -> "把值得留下的想法沉淀下来"
    topics == 0 -> "$notes 篇笔记"
    else -> "$notes 篇笔记 · $topics 个主题"
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 6.dp, top = 6.dp),
    )
}

/** 全部, each topic, 未归类 when it has anything, then the way to manage topics. */
@Composable
private fun TopicBar(
    topics: List<NoteTopic>,
    notes: List<Note>,
    filter: String,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val counts = remember(notes) { notes.groupingBy { it.topicId }.eachCount() }
    val unsorted = counts[null] ?: 0
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = NotesViewModel.ALL) { FilterPill("全部", notes.size, cs.primary, filter == NotesViewModel.ALL, null) { onPick(NotesViewModel.ALL) } }
        topics.forEachIndexed { i, t ->
            item(key = t.id) {
                FilterPill(t.name, counts[t.id] ?: 0, AppTheme.extra.group(i), filter == t.id, AppTheme.extra.group(i), Modifier.animateItem()) { onPick(t.id) }
            }
        }
        if (topics.isNotEmpty() && (unsorted > 0 || filter == NotesViewModel.UNSORTED)) {
            item(key = NotesViewModel.UNSORTED) {
                FilterPill("未归类", unsorted, cs.onSurfaceVariant, filter == NotesViewModel.UNSORTED, cs.outline) { onPick(NotesViewModel.UNSORTED) }
            }
        }
        item(key = "manage") {
            ManagePill(if (topics.isEmpty()) Icons.Default.Add else Icons.Outlined.Tune, if (topics.isEmpty()) "新建主题" else "管理", onManage)
        }
    }
}

@Composable
private fun FilterPill(name: String, count: Int, color: Color, selected: Boolean, dot: Color?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) color else cs.surfaceContainer, tween(220), label = "pill")
    // Dark mode's palette is light: picked text goes dark on it instead of white.
    val onColor = if (color.luminance() > 0.5f) Color(0xFF12201C) else Color.White
    val fg = if (selected) onColor else cs.onSurface
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(38.dp)
            .pressScale(source)
            .shadow(if (selected) 6.dp else 1.dp, RoundedCornerShape(50), spotColor = if (selected) color else Color.Black.copy(alpha = 0.1f))
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = if (dot != null) 12.dp else 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) onColor else dot))
            Spacer(Modifier.width(7.dp))
        }
        Text(name, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = if (selected) onColor.copy(alpha = 0.8f) else cs.onSurfaceVariant)
    }
}

@Composable
private fun ManagePill(icon: ImageVector, text: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(50))
            .background(cs.primary.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = cs.primary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = cs.primary)
    }
}

@Composable
private fun DraftBanner(count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.tertiaryContainer, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.clickable(onClick = onClick).padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(cs.onTertiaryContainer.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Drafts, null, tint = cs.onTertiaryContainer, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("有 $count 份草稿未处理", style = MaterialTheme.typography.titleSmall, color = cs.onTertiaryContainer)
                Text("保存冲突时留下的文字，点开处理", style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer.copy(alpha = 0.75f))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onTertiaryContainer)
        }
    }
}

@Composable
private fun EmptyNotes(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    Column(modifier.fillMaxWidth().padding(top = 36.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(Brush.linearGradient(listOf(extra.heroEnd, extra.heroStart))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AutoStories, null, tint = extra.onHero, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("还没有笔记", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "日记按天记，笔记按主题攒。一个想法一篇，越改越厚。",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Hint(Icons.Outlined.Lightbulb, "从日记提炼", "在日记页点右上角的灯泡，勾选值得留下的段落")
                Hint(Icons.Outlined.Edit, "直接写", "点下方「写笔记」，第一行就是标题")
            }
        }
    }
}

@Composable
private fun Hint(icon: ImageVector, title: String, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** Text that could not be saved into its note: keep it as a note of its own, open the note, or drop it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftsSheet(
    drafts: List<NoteDraft>,
    notes: List<Note>,
    onDismiss: () -> Unit,
    onToNote: (NoteDraft) -> Unit,
    onOpenNote: (String) -> Unit,
    onDiscard: (NoteDraft) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var discarding by remember { mutableStateOf<NoteDraft?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("未处理的草稿", style = MaterialTheme.typography.titleLarge)
            Text("这些文字没能存进笔记正文。可以另存成一篇新笔记，或者对照原笔记手动合并。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (drafts.isEmpty()) {
                Text("都处理完了", color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(), textAlign = TextAlign.Center)
            }
            drafts.forEach { d ->
                val origin = d.noteId?.let { id -> notes.firstOrNull { it.id == id } }
                Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp)) {
                        Text(
                            Dates.dateTime(d.createdAt) + " · " + d.reason,
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        if (origin != null) {
                            Text("来自《${origin.title}》", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.padding(top = 2.dp))
                        }
                        Text(d.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, end = 10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { discarding = d }) { Text("丢弃", color = cs.error) }
                            if (origin != null) TextButton(onClick = { onOpenNote(origin.id) }) { Text("打开原笔记") }
                            TextButton(onClick = { onToNote(d) }) { Text("存为新笔记") }
                        }
                    }
                }
            }
        }
    }
    discarding?.let { d ->
        ConfirmDialog(
            "丢弃这份草稿？", "丢弃后无法找回。", "丢弃", destructive = true,
            onConfirm = { discarding = null; onDiscard(d) }, onDismiss = { discarding = null },
        )
    }
}
