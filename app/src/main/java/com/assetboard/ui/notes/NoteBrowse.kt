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
import com.assetboard.ui.components.GlassScaffold
import com.assetboard.ui.components.glassBarColors
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
import com.assetboard.ui.components.scrollIndicator
import androidx.compose.foundation.lazy.rememberLazyListState

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
    GlassScaffold(
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
                colors = glassBarColors(),
            )
        },
    ) { padding ->
        val r = results
        val barState = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).scrollIndicator(barState), state = barState,
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
        val barState = rememberLazyListState()
        LazyColumn(Modifier.heightIn(max = 440.dp).scrollIndicator(barState), state = barState) {
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
