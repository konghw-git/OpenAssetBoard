package com.assetboard.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
import com.assetboard.AppContainer
import com.assetboard.data.DiaryEntry
import com.assetboard.data.NoteText
import com.assetboard.data.WeeklyReview
import com.assetboard.data.Weeks
import java.time.LocalDate
import com.assetboard.data.TodoKind
import com.assetboard.data.TodoRepository
import com.assetboard.data.TrashBatch
import com.assetboard.domain.Dates
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.assetboard.ui.components.scrollIndicator
import androidx.compose.foundation.lazy.rememberLazyListState

class TrashViewModel(private val container: AppContainer) : ActionViewModel(container) {
    private val todos = container.todos
    private val diary = container.diary
    private val notes = container.notes
    private val weeks = container.weeks
    val batches: StateFlow<List<TrashBatch>?> = todos.trash.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** A diary restore that would collide with an entry written since: (batch, the current entry). */
    val diaryConflict = MutableStateFlow<Pair<String, DiaryEntry>?>(null)
    /** The same for a weekly review: (batch, the week's current review). */
    val weekConflict = MutableStateFlow<Pair<String, WeeklyReview>?>(null)
    /** Purging a list batch that takes earlier-deleted sections with it: (batch, how many). */
    val purgeConfirm = MutableStateFlow<Pair<String, Int>?>(null)

    fun restore(b: TrashBatch) = action {
        when {
            b.diary.isNotEmpty() -> {
                val conflict = diary.restoreConflict(b.batch)
                if (conflict != null) diaryConflict.value = b.batch to conflict else diary.restore(b.batch, replace = false)
            }
            b.weeks.isNotEmpty() -> {
                val conflict = weeks.restoreConflict(b.batch)
                if (conflict != null) weekConflict.value = b.batch to conflict else weeks.restore(b.batch, replace = false)
            }
            // Notes never collide with a live one; restoreBatch below knows only lists, sections and todos.
            b.notes.isNotEmpty() -> notes.restore(b.batch)
            else -> todos.restoreBatch(b.batch)
        }
    }

    fun resolveDiary(replace: Boolean) {
        val (batch, _) = diaryConflict.value ?: return
        diaryConflict.value = null
        if (replace) action { diary.restore(batch, replace = true) }
    }

    fun resolveWeek(replace: Boolean) {
        val (batch, _) = weekConflict.value ?: return
        weekConflict.value = null
        if (replace) action { weeks.restore(batch, replace = true) }
    }

    fun restoreTodo(id: String) = action { todos.restoreTodo(id) }
    fun restoreSection(id: String) = action { todos.restoreSection(id) }

    fun askPurge(b: TrashBatch) = action { purgeConfirm.value = b.batch to todos.sectionsPurgedAlongside(b.batch) }

    fun purge() {
        val (batch, _) = purgeConfirm.value ?: return
        purgeConfirm.value = null
        action { todos.purgeBatch(batch) }
    }

    fun emptyTrash() = action { todos.emptyTrash() }
}

fun describe(b: TrashBatch): String {
    val open = b.todos.filter { it.kind != TodoKind.COMPLETION }
    return when {
        b.lists.isNotEmpty() -> "清单 · ${b.lists.first().name}" + if (open.isNotEmpty()) "（含 ${open.size} 条待办）" else ""
        b.sections.isNotEmpty() -> "分区 · ${b.sections.first().name}" + if (open.isNotEmpty()) "（含 ${open.size} 条待办）" else ""
        b.diary.isNotEmpty() -> "日记 · ${b.diary.first().date}"
        b.notes.isNotEmpty() -> "笔记 · ${b.notes.first().title}"
        b.weeks.isNotEmpty() -> "周复盘 · ${Weeks.title(LocalDate.parse(b.weeks.first().weekStart))}"
        b.todos.size == 1 -> "待办 · ${b.todos.first().title}"
        else -> "${b.todos.size} 条待办"
    }
}

@Composable
fun TrashScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val vm: TrashViewModel = viewModel { TrashViewModel(container) }
    val batches by vm.batches.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val conflict by vm.diaryConflict.collectAsStateWithLifecycle()
    val weekConflict by vm.weekConflict.collectAsStateWithLifecycle()
    val purge by vm.purgeConfirm.collectAsStateWithLifecycle()
    var emptyAsk by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    ErrorDialog(error) { vm.error.value = null }
    val cs = MaterialTheme.colorScheme

    GlassScaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { if (!batches.isNullOrEmpty()) TextButton(onClick = { emptyAsk = true }) { Text("清空", color = cs.error) } },
                colors = glassBarColors(),
            )
        },
    ) { padding ->
        val list = batches ?: return@GlassScaffold
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("回收站是空的。删除的待办、清单、日记、周复盘和笔记会在这里保留 30 天。", color = cs.onSurfaceVariant, modifier = Modifier.padding(40.dp))
            }
            return@GlassScaffold
        }
        val barState = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).scrollIndicator(barState), state = barState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("按删除的批次列出，满 30 天自动彻底删除。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            items(list, key = { it.batch }) { b ->
                val left = TodoRepository.TRASH_DAYS - Dates.daysBetween(b.deletedAt, System.currentTimeMillis())
                SectionCard(contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
                    Text(describe(b), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
                    Text("删除于 ${Dates.dateTime(b.deletedAt)} · 还剩 ${left.coerceAtLeast(0)} 天", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    val preview = b.diary.firstOrNull()?.body?.lineSequence()?.firstOrNull() ?: b.notes.firstOrNull()?.let { NoteText.preview(it.body) }
                        ?: b.weeks.firstOrNull()?.body?.lineSequence()?.firstOrNull { it.isNotBlank() }
                    preview?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 12.dp)) }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        val multi = b.todos.size + b.sections.size > 1 || (b.lists.isNotEmpty() && b.todos.isNotEmpty())
                        if (multi) TextButton(onClick = { expanded = if (b.batch in expanded) expanded - b.batch else expanded + b.batch }) { Text(if (b.batch in expanded) "收起" else "展开") }
                        TextButton(onClick = { vm.askPurge(b) }) { Text("彻底删除", color = cs.error) }
                        TextButton(onClick = { vm.restore(b) }) { Text("恢复") }
                    }
                    if (b.batch in expanded) {
                        Column(Modifier.padding(bottom = 6.dp)) {
                            b.sections.forEach { s ->
                                RowDivider(0.dp)
                                ItemLine("分区 · ${s.name}") { vm.restoreSection(s.id) }
                            }
                            b.todos.filter { it.kind != TodoKind.COMPLETION }.forEach { t ->
                                RowDivider(0.dp)
                                ItemLine(t.title) { vm.restoreTodo(t.id) }
                            }
                        }
                    }
                }
            }
        }
    }

    conflict?.let { (_, current) ->
        AlertDialog(
            onDismissRequest = { vm.diaryConflict.value = null },
            title = { Text("${current.date} 已经有一篇日记") },
            text = { Text("保留现有的那篇，或者用回收站里的替换它（现有的那篇会进回收站）。两篇不会合并。") },
            confirmButton = { TextButton(onClick = { vm.resolveDiary(replace = true) }) { Text("用回收站里的替换") } },
            dismissButton = { TextButton(onClick = { vm.resolveDiary(replace = false) }) { Text("保留现有的") } },
        )
    }
    weekConflict?.let { (_, current) ->
        AlertDialog(
            onDismissRequest = { vm.weekConflict.value = null },
            title = { Text("${Weeks.title(LocalDate.parse(current.weekStart))}已经有一篇复盘") },
            text = { Text("保留现有的那篇，或者用回收站里的替换它（现有的那篇会进回收站）。两篇不会合并。") },
            confirmButton = { TextButton(onClick = { vm.resolveWeek(replace = true) }) { Text("用回收站里的替换") } },
            dismissButton = { TextButton(onClick = { vm.resolveWeek(replace = false) }) { Text("保留现有的") } },
        )
    }
    purge?.let { (_, sections) ->
        ConfirmDialog(
            title = "彻底删除？",
            text = "彻底删除后无法恢复。" + if (sections > 0) "\n另有 $sections 个之前删除的分区将一并永久删除，其中的待办保留。" else "",
            confirmLabel = "彻底删除",
            destructive = true,
            onConfirm = vm::purge,
            onDismiss = { vm.purgeConfirm.value = null },
        )
    }
    if (emptyAsk) {
        ConfirmDialog(
            title = "清空回收站？", text = "回收站里的所有内容都会被彻底删除，无法恢复。", confirmLabel = "清空", destructive = true,
            onConfirm = { emptyAsk = false; vm.emptyTrash() }, onDismiss = { emptyAsk = false },
        )
    }
}

@Composable
private fun ItemLine(title: String, onRestore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = onRestore) { Text("单独恢复") }
    }
}
