package com.assetboard.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.AppContainer
import com.assetboard.data.Note
import com.assetboard.data.NoteText
import com.assetboard.data.NoteTopic
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.highlight
import com.assetboard.ui.components.snippet
import com.assetboard.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Full-text search over all live notes, pinned and recent first, with the hits highlighted. */
@Composable
fun NoteSearchScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Note>?>(null) }
    val topics by remember { container.notes.observeTopics() }.collectAsStateWithLifecycle(emptyList())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(q) {
        delay(250)
        results = if (q.isBlank()) null else container.notes.search(q)
    }
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        q, { q = it }, singleLine = true, placeholder = { Text("搜索笔记") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        shape = RoundedCornerShape(50),
                        textStyle = MaterialTheme.typography.bodyLarge,
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
            if (r == null) {
                item { Text("搜索标题和正文，包括 #标签", color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) }
            } else if (r.isEmpty()) {
                item { Text("没有找到「$q」", color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) }
            } else {
                item { Text("${r.size} 篇笔记", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp)) }
            }
            items(r.orEmpty(), key = { it.id }) { n ->
                val body = remember(n.body, q) { highlight(snippet(n.body.substringAfter(n.title, n.body).trim(), q), q, cs.primary) }
                NoteCard(n, topics, { onOpen(n.id) }, preview = body)
            }
        }
    }
}

/**
 * Picks the note an extract goes to: live notes, pinned and recent first, filtered by what is
 * typed. A page of the diary's extract sheet, so there is never a dialog over the sheet.
 */
@Composable
fun NotePicker(notes: List<Note>, topics: List<NoteTopic>, onBack: () -> Unit, onPick: (Note) -> Unit, modifier: Modifier = Modifier) {
    var q by rememberSaveable { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    val shown = remember(notes, q) { if (q.isBlank()) notes else notes.filter { it.body.contains(q.trim(), ignoreCase = true) } }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回勾选") }
            Text("追加到哪篇笔记？", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            q, { q = it }, singleLine = true, placeholder = { Text("搜索笔记") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(50),
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
        )
        if (shown.isEmpty()) {
            Text(
                if (notes.isEmpty()) "还没有笔记，先存为新笔记吧" else "没有找到",
                color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
        }
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
            items(shown, key = { it.id }) { n ->
                val topic = topics.firstOrNull { it.id == n.topicId }
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onPick(n) }.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(topicColor(topics, topic?.id)))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(topic?.name ?: "未归类", whenLabel(n.updatedAt)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                    if (n.pinned) Icon(Icons.Filled.PushPin, "已置顶", tint = cs.secondary, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

class NoteTopicsViewModel(container: AppContainer) : ActionViewModel(container) {
    private val notes = container.notes
    val topics: StateFlow<List<NoteTopic>> = notes.observeTopics().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Live notes per topic id; null is 未归类. */
    val counts: StateFlow<Map<String?, Int>> = notes.observeNotes().map { n -> n.groupingBy { it.topicId }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    /** A topic about to be deleted, with how many live notes would move to 未归类. */
    val deleting = MutableStateFlow<Pair<NoteTopic, Int>?>(null)

    fun create(name: String, onDone: () -> Unit) = action(onDone) { notes.createTopic(name) }
    fun rename(id: String, name: String, onDone: () -> Unit) = action(onDone) { notes.renameTopic(id, name) }
    fun move(id: String, delta: Int) = action { notes.moveTopic(id, delta) }
    fun askDelete(t: NoteTopic) = action { deleting.value = t to notes.liveCountIn(t.id) }

    fun delete() {
        val (t, _) = deleting.value ?: return
        deleting.value = null
        action { notes.deleteTopic(t.id) }
    }
}

/** One level of topics: create, rename, move up and down, delete. 未归类 is not a topic and stays last. */
@Composable
fun NoteTopicsScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val vm: NoteTopicsViewModel = viewModel { NoteTopicsViewModel(container) }
    val topics by vm.topics.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    ErrorDialog(error) { vm.error.value = null }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text("笔记主题") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "新建主题") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "一篇笔记属于一个主题，或者不属于任何主题（未归类）。主题的顺序就是笔记页筛选条的顺序。",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item {
                SectionCard {
                    topics.forEachIndexed { i, t ->
                        if (i > 0) RowDivider(startIndent = 50.dp)
                        ListRow(
                            title = t.name,
                            subtitle = "${counts[t.id] ?: 0} 篇笔记",
                            leading = { Box(Modifier.size(14.dp).clip(CircleShape).background(extra.group(i))) },
                            onClick = { editing = t.id },
                            chevron = false,
                            trailing = {
                                Row {
                                    IconButton(onClick = { vm.move(t.id, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "上移") }
                                    IconButton(onClick = { vm.move(t.id, 1) }, enabled = i < topics.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "下移") }
                                }
                            },
                        )
                    }
                    if (topics.isNotEmpty()) RowDivider(startIndent = 50.dp)
                    ListRow(
                        title = "未归类",
                        subtitle = "${counts[null] ?: 0} 篇笔记 · 没有主题的笔记都在这里",
                        leading = { Box(Modifier.size(14.dp).clip(CircleShape).background(cs.outline)) },
                        chevron = false,
                    )
                }
            }
        }
    }

    val target = topics.firstOrNull { it.id == editing }
    if (creating) {
        TopicNameDialog("新建主题", "", onDismiss = { creating = false }) { name -> vm.create(name) { creating = false } }
    }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(target.name) },
            text = { Text("${counts[target.id] ?: 0} 篇笔记") },
            confirmButton = { TextButton(onClick = { renaming = target.id; editing = null }) { Text("重命名") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { editing = null; vm.askDelete(target) }) { Text("删除", color = cs.error) }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            },
        )
    }
    topics.firstOrNull { it.id == renaming }?.let { t ->
        TopicNameDialog("重命名主题", t.name, onDismiss = { renaming = null }) { name -> vm.rename(t.id, name) { renaming = null } }
    }
    deleting?.let { (t, n) ->
        ConfirmDialog(
            "删除主题「${t.name}」？",
            (if (n > 0) "其中 $n 篇笔记会移到未归类，" else "") + "笔记本身不会删除。",
            "删除", destructive = true,
            onConfirm = vm::delete, onDismiss = { vm.deleting.value = null },
        )
    }
}

/** The title a note extracted from [date] gets when none is typed. */
fun extractTitle(date: java.time.LocalDate): String = "${dayLabel(date)} 日记摘录"

/**
 * Where an extract comes from: how the sheet names it, the title a new note gets when none is
 * typed, the header of an appended block and the source the note records.
 */
data class ExtractOrigin(val label: String, val defaultTitle: String, val blockHeader: String, val source: com.assetboard.data.SourceRef) {
    companion object {
        fun day(date: java.time.LocalDate) = ExtractOrigin("${dayLabel(date)}的日记", extractTitle(date), date.toString(), com.assetboard.data.SourceRef.day(date))
        fun week(start: java.time.LocalDate) = ExtractOrigin(
            "${com.assetboard.data.Weeks.shortTitle(start)}复盘", "${com.assetboard.data.Weeks.shortTitle(start)}复盘摘录",
            com.assetboard.data.Weeks.title(start), com.assetboard.data.SourceRef.week(start),
        )
    }
}

/**
 * The extract as it will read in the note: always a title line of its own ([title], or
 * [fallback] when it is blank), so a long paragraph never becomes the title.
 */
fun extractBody(title: String, fallback: String, paragraphs: List<String>): String =
    (title.trim().ifEmpty { fallback }) + "\n\n" + paragraphs.joinToString("\n\n")

/** The block an append adds, headed by where it came from. */
fun extractBlock(header: String, paragraphs: List<String>): String = NoteText.block(header, paragraphs.joinToString("\n\n"))
