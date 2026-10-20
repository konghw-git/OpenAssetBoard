package com.assetboard.ui.notes

import com.assetboard.ui.components.LocalAmbient
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.assetboard.ui.components.PullToCreateBox
import com.assetboard.ui.components.rememberPullToCreate
import com.assetboard.ui.components.TabHeader
import com.assetboard.ui.components.appear
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.theme.AppTheme
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.IconButton
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.assetboard.data.NoteText
import com.assetboard.ui.todo.rememberToday
import androidx.compose.ui.layout.layout
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.components.SwipeAction
import com.assetboard.ui.components.SwipeActionsBox
import com.assetboard.ui.components.UndoEvent
import com.assetboard.ui.components.UndoMessages
import com.assetboard.ui.components.ambient
import com.assetboard.ui.components.backdropSource
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.rememberAmbient
import com.assetboard.ui.components.rememberBackdrop
import com.assetboard.ui.components.rememberReorderState
import com.assetboard.ui.components.rememberSwipeGroup
import com.assetboard.ui.components.reorderable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** How the list is ordered. Only the hand-set order can be changed by dragging. */
enum class NoteSort(val label: String, val note: String) {
    CUSTOM("自定义顺序", "长按笔记拖动排序，新笔记排在最上面"),
    UPDATED("最近更新", "改过的笔记排到前面"),
    CREATED("最近创建", "新写的排到前面"),
}

class NotesViewModel(private val container: AppContainer) : ActionViewModel(container) {
    private val notes = container.notes
    val topics: StateFlow<List<NoteTopic>> = notes.observeTopics().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val all: StateFlow<List<Note>?> = notes.observeNotes().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val drafts: StateFlow<List<NoteDraft>> = notes.observeDrafts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** [ALL], [UNSORTED] or a topic id; kept while the tab is swiped away. */
    val filter = MutableStateFlow(ALL)

    val sort = MutableStateFlow(
        runCatching { NoteSort.valueOf(container.settings.string(KEY_SORT) ?: "") }.getOrDefault(NoteSort.CUSTOM),
    )

    private val _events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UndoEvent> = _events

    private val reviewId = MutableStateFlow<String?>(null)
    private var reviewDay: LocalDate? = null
    /** Today's review card, kept live as the note changes. */
    val review: StateFlow<Note?> = combine(all, reviewId) { list, id -> list?.firstOrNull { it.id == id && !it.pinned } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Picks (or reloads) today's review. Runs on every resume and whenever the list changes, so
     * a pick that was deleted or pinned meanwhile is replaced. Failing quietly is fine: the card
     * is a nicety.
     */
    fun refreshReview(today: LocalDate) = launchBound {
        val list = all.value ?: return@launchBound
        val id = reviewId.value
        if (reviewDay == today && (id == null || list.any { it.id == id && !it.pinned })) return@launchBound
        reviewId.value = runCatching { notes.reviewPick(today)?.id }.getOrNull()
        reviewDay = today
    }

    fun openReview(n: Note) = launchBound { runCatching { notes.setReviewed(n.id) } }

    fun dismissReview(n: Note, today: LocalDate) = action {
        notes.dismissReview(today, n.id)
        reviewId.value = null
    }

    fun setSort(s: NoteSort) {
        container.settings.putString(KEY_SORT, s.name)
        sort.value = s
    }

    /** Dragging needs the hand-set order; offers to switch to it. */
    fun explainDrag() {
        _events.tryEmit(UndoEvent("按「${sort.value.label}」排列时不能拖动", action = "改为自定义") { setSort(NoteSort.CUSTOM) })
    }

    fun draftToNote(d: NoteDraft, onDone: (String) -> Unit) = action { onDone(notes.draftToNote(d.id)) }
    fun discardDraft(d: NoteDraft) = action { notes.discardDraft(d.id) }

    fun togglePin(n: Note) = action { notes.setPinned(n.id, !n.pinned) }
    fun setTopic(n: Note, topicId: String?) = action { notes.setTopic(n.id, topicId) }
    fun createTopic(name: String, onCreated: (String) -> Unit = {}) = action { onCreated(notes.createTopic(name)) }
    fun reorder(ids: List<String>) = action { notes.reorder(ids) }

    fun delete(n: Note) = action {
        val batch = notes.delete(n.id)
        val t = n.title.let { if (it.length > 12) it.take(12) + "…" else it }
        _events.tryEmit(UndoEvent("《$t》已移到回收站") { notes.restore(batch) })
    }

    fun undo(e: UndoEvent) {
        val u = e.undo ?: return
        action { u() }
    }

    companion object {
        const val ALL = "*"
        const val UNSORTED = "-"
        private const val KEY_SORT = "note_sort"
    }
}

/** The dust and swipe key of a note's card. */
private fun noteKey(id: String) = "note:$id"

/**
 * The notes tab: only a list. Writing happens on its own page and searching on another, so
 * nothing here takes the keyboard and swiping between tabs never fights with typing. Cards are
 * glass over the page's ambient colour; swipe left deletes, right pins or files; in the
 * hand-set order a long press picks a card up to move it.
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
    val sort by vm.sort.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val today = rememberToday()
    LaunchedEffect(today, all) { vm.refreshReview(today) }
    var draftsOpen by rememberSaveable { mutableStateOf(false) }
    var topicsOpen by rememberSaveable { mutableStateOf(false) }
    var filingId by rememberSaveable { mutableStateOf<String?>(null) }
    ErrorDialog(error) { vm.error.value = null }
    val cs = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    UndoMessages(vm.events, snackbar, vm::undo)
    val backdrop = rememberBackdrop()
    val ambient = rememberAmbient()
    val swipe = rememberSwipeGroup()
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) swipe.closeAll() }

    // A topic deleted meanwhile falls back to everything.
    val filter = if (filterRaw == NotesViewModel.ALL || filterRaw == NotesViewModel.UNSORTED || topics.any { it.id == filterRaw }) filterRaw else NotesViewModel.ALL
    // Pull the list down to start a note, in the topic being looked at.
    val pull = rememberPullToCreate { onNew(filter.takeIf { it != NotesViewModel.ALL && it != NotesViewModel.UNSORTED }) }
    val notes = all.orEmpty()
    val counts = remember(notes) { notes.groupingBy { it.topicId }.eachCount() }
    val shown = remember(notes, filter, sort) {
        val picked = when (filter) {
            NotesViewModel.ALL -> notes
            NotesViewModel.UNSORTED -> notes.filter { it.topicId == null }
            else -> notes.filter { it.topicId == filter }
        }
        when (sort) {
            NoteSort.CUSTOM -> picked
            NoteSort.UPDATED -> picked.sortedByDescending { it.updatedAt }
            NoteSort.CREATED -> picked.sortedByDescending { it.createdAt }
        }
    }
    // The order on screen; a drag rearranges it here and saves it on release.
    var order by remember(shown) { mutableStateOf(shown) }
    val custom = sort == NoteSort.CUSTOM
    val reorder = rememberReorderState(
        listState,
        onMove = { from, to ->
            val a = order.indexOfFirst { noteKey(it.id) == from }
            val b = order.indexOfFirst { noteKey(it.id) == to }
            if (a < 0 || b < 0 || order[a].pinned != order[b].pinned) {
                false
            } else {
                order = order.toMutableList().apply { add(b, removeAt(a)) }
                true
            }
        },
        onDrop = { key ->
            val moved = order.firstOrNull { noteKey(it.id) == key } ?: return@rememberReorderState
            vm.reorder(order.filter { it.pinned == moved.pinned }.map { it.id })
        },
    )

    Box(Modifier.fillMaxSize().ambient(ambient)) {
        CompositionLocalProvider(LocalAmbient provides ambient) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TabHeader("笔记", subtitle(notes.size, topics.size)) {
                        HeaderAction(Icons.Outlined.Search, "搜索笔记", onSearch)
                        SortAction(sort, vm::setSort)
                        HeaderAction(Icons.Outlined.Settings, "设置", onSettings)
                    }
                },
            ) { padding ->
                val list = all
                PullToCreateBox(pull, "下拉新建笔记", "松手新建笔记", Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = with(LocalDensity.current) { pull.pulled.toDp() }, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "topics") {
                        TopicBar(
                            topics, counts, notes.size, filter,
                            onPick = { vm.filter.value = it },
                            onAll = { topicsOpen = true },
                            onNewTopic = onTopics,
                            modifier = Modifier.appear(0),
                        )
                    }
                    if (drafts.isNotEmpty()) {
                        item(key = "drafts") { DraftBanner(drafts.size, Modifier.animateItem()) { draftsOpen = true } }
                    }
                    review?.takeIf { filter == NotesViewModel.ALL }?.let { r ->
                        item(key = "review") {
                            ReviewCard(
                                r, topics, today,
                                onOpen = { vm.openReview(r); onOpen(r.id) },
                                onDismiss = { vm.dismissReview(r, today) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    when {
                        list == null -> Unit
                        notes.isEmpty() -> item(key = "empty") { EmptyNotes(Modifier.appear(1)) }
                        shown.isEmpty() -> item(key = "emptyTopic") {
                            Text(
                                if (filter == NotesViewModel.UNSORTED) "所有笔记都有主题了" else "这个主题下还没有笔记，往下拉写第一篇",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                            )
                        }
                        else -> {
                            val pinned = order.filter { it.pinned }
                            val rest = order.filterNot { it.pinned }
                            val showTopic = filter == NotesViewModel.ALL
                            val card: @Composable LazyItemScope.(Note) -> Unit = { n ->
                                val key = noteKey(n.id)
                                val dragged = reorder.dragging == key
                                SwipeActionsBox(
                                    key = key,
                                    group = swipe,
                                    shape = NoteCardShape,
                                    modifier = Modifier
                                        .zIndex(if (dragged) 1f else 0f)
                                        .graphicsLayer { translationY = if (dragged) reorder.offset else 0f }
                                        .then(if (dragged) Modifier else Modifier.animateItem()),
                                    actions = listOf(
                                        SwipeAction(if (n.pinned) "取消置顶" else "置顶", if (n.pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin, cs.secondary) { vm.togglePin(n) },
                                        SwipeAction("主题", Icons.AutoMirrored.Outlined.Label, cs.tertiary) { filingId = n.id },
                                    ),
                                    onDelete = { vm.delete(n) },
                                    enabled = reorder.dragging == null,
                                ) {
                                    NoteCard(
                                        n, topics, { onOpen(n.id) },
                                        Modifier.reorderable(reorder, key, enabled = custom),
                                        showTopic = showTopic,
                                        lifted = dragged,
                                        onLongClick = if (custom) null else vm::explainDrag,
                                    )
                                }
                            }
                            if (pinned.isNotEmpty()) {
                                item(key = "pinnedLabel") { Label("置顶", Modifier.animateItem()) }
                                items(pinned, key = { noteKey(it.id) }) { card(it) }
                                if (rest.isNotEmpty()) item(key = "restLabel") { Label(if (custom) "笔记" else sort.label, Modifier.animateItem()) }
                            }
                            items(rest, key = { noteKey(it.id) }) { card(it) }
                        }
                    }
                }
                }
            }
        }
        GlassSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), backdrop)
    }
}

    if (topicsOpen) {
        TopicSheet(
            "按主题看", topics, counts, filter, showAll = true,
            onPick = { vm.filter.value = it; topicsOpen = false },
            onCreate = { name -> vm.createTopic(name) { vm.filter.value = it } ; topicsOpen = false },
            onDismiss = { topicsOpen = false },
            onManage = { topicsOpen = false; onTopics() },
        )
    }
    filingId?.let { id ->
        val n = notes.firstOrNull { it.id == id }
        if (n == null) {
            filingId = null
        } else {
            TopicSheet(
                "放到哪个主题", topics, counts, n.topicId ?: NotesViewModel.UNSORTED, showAll = false,
                onPick = { k -> vm.setTopic(n, k.takeIf { it != NotesViewModel.UNSORTED }); filingId = null },
                onCreate = { name -> vm.createTopic(name) { t -> vm.setTopic(n, t) }; filingId = null },
                onDismiss = { filingId = null },
            )
        }
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

/** The sort order, from a header button. */
@Composable
private fun SortAction(sort: NoteSort, onPick: (NoteSort) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        HeaderAction(Icons.AutoMirrored.Outlined.Sort, "排序", { open = true })
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(18.dp), containerColor = cs.surfaceContainer) {
            NoteSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(s.label, style = MaterialTheme.typography.bodyLarge)
                            Text(s.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    },
                    trailingIcon = { if (s == sort) Icon(Icons.Default.Check, null, tint = cs.primary) },
                    onClick = { open = false; onPick(s) },
                )
            }
        }
    }
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

/**
 * 全部, each topic and 未归类 in a row that scrolls, with a fixed button at its end that opens
 * every topic in a sheet, so a long row never hides the way to the rest.
 */
@Composable
private fun TopicBar(
    topics: List<NoteTopic>,
    counts: Map<String?, Int>,
    total: Int,
    filter: String,
    onPick: (String) -> Unit,
    onAll: () -> Unit,
    onNewTopic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val unsorted = counts[null] ?: 0
    val row = rememberLazyListState()
    val keys = buildList {
        add(NotesViewModel.ALL)
        topics.forEach { add(it.id) }
        if (topics.isNotEmpty() && (unsorted > 0 || filter == NotesViewModel.UNSORTED)) add(NotesViewModel.UNSORTED)
    }
    // A topic picked from the sheet scrolls into view.
    LaunchedEffect(filter) {
        val i = keys.indexOf(filter)
        val visible = row.layoutInfo.visibleItemsInfo
        val whole = visible.any { it.index == i && it.offset >= 0 && it.offset + it.size <= row.layoutInfo.viewportEndOffset }
        if (i >= 0 && !whole) row.animateScrollToItem((i - 1).coerceAtLeast(0))
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            Modifier
                .weight(1f)
                // Out to the screen's left edge, so chips scroll off it and their shadows are not cut.
                .layout { m, c ->
                    val edge = 16.dp.roundToPx()
                    val p = m.measure(c.copy(minWidth = c.minWidth + edge, maxWidth = c.maxWidth + edge))
                    layout(c.maxWidth, p.height) { p.place(-edge, 0) }
                }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // The row fades out under the button rather than being cut off.
                    if (topics.isNotEmpty()) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = size.width - 28.dp.toPx(), endX = size.width),
                            blendMode = BlendMode.DstOut,
                        )
                    }
                },
            state = row,
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, bottom = 14.dp, end = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = NotesViewModel.ALL) { FilterPill("全部", total, cs.primary, filter == NotesViewModel.ALL, null) { onPick(NotesViewModel.ALL) } }
            topics.forEachIndexed { i, t ->
                item(key = t.id) {
                    FilterPill(t.name, counts[t.id] ?: 0, AppTheme.extra.group(i), filter == t.id, AppTheme.extra.group(i), Modifier.animateItem()) { onPick(t.id) }
                }
            }
            if (NotesViewModel.UNSORTED in keys) {
                item(key = NotesViewModel.UNSORTED) {
                    FilterPill("未归类", unsorted, cs.onSurfaceVariant, filter == NotesViewModel.UNSORTED, cs.outline) { onPick(NotesViewModel.UNSORTED) }
                }
            }
            if (topics.isEmpty()) {
                item(key = "new") { NewTopicPill(onNewTopic) }
            }
        }
        if (topics.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            AllTopicsButton(onAll)
        }
    }
}

@Composable
private fun NewTopicPill(onClick: () -> Unit) {
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
        Icon(Icons.Default.Add, null, tint = cs.primary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(4.dp))
        Text("新建主题", style = MaterialTheme.typography.labelLarge, color = cs.primary)
    }
}

/** Opens every topic in a sheet; stays put at the end of the row. */
@Composable
private fun AllTopicsButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(38.dp)
            .pressScale(source, 0.9f)
            .glass(CircleShape, glass, elevation = 6.dp)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.GridView, "全部主题", tint = cs.onSurface, modifier = Modifier.size(19.dp))
    }
}

/**
 * One older note back in view each day. It says how long it has been; opening it counts as
 * reviewed (the card stays for the day), and the close button puts it away until tomorrow.
 */
@Composable
private fun ReviewCard(note: Note, topics: List<NoteTopic>, today: LocalDate, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val shape = RoundedCornerShape(24.dp)
    val source = remember { MutableInteractionSource() }
    val now = System.currentTimeMillis()
    // Since it was last written or opened, not counting today's look.
    val days = Dates.daysBetween(maxOf(note.updatedAt, note.reviewedAt?.takeIf { Dates.daysBetween(it, now) > 0 } ?: 0L), now)
    val since = if (days < 60) "$days 天没碰过了" else "${days / 30} 个多月没碰过了"
    val seenToday = note.reviewedAt?.let { Dates.daysBetween(it, System.currentTimeMillis()) == 0L } == true
    val topic = topics.firstOrNull { it.id == note.topicId }
    Column(
        modifier
            .fillMaxWidth()
            .pressScale(source, 0.98f)
            .glass(shape, glass, tint = cs.secondary.copy(alpha = 0.07f), elevation = 12.dp)
            .clip(shape)
            .clickable(interactionSource = source, indication = ripple(), onClick = onOpen)
            .drawBehind {
                // A warm glow in the corner marks the card as something brought back.
                drawCircle(Brush.radialGradient(listOf(cs.secondary.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * 0.6f), size.width * 0.6f, Offset(size.width, 0f))
            }
            .padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(Brush.linearGradient(listOf(cs.secondary, cs.secondary.copy(alpha = 0.7f)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.History, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("今日回顾", style = MaterialTheme.typography.labelLarge, color = cs.secondary)
            Text(
                if (seenToday) " · 今天看过了" else " · $since",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, "今天不看了", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, end = 12.dp))
        val body = remember(note.body, cs.primary) { tagged(NoteText.preview(note.body), cs.primary) }
        if (body.isNotEmpty()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 12.dp))
        }
        if (topic != null) TopicTag(topic.name, topicColor(topics, topic.id), Modifier.padding(top = 10.dp))
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
                Hint(Icons.Outlined.Edit, "直接写", "在列表顶部往下拉，松手就新建一篇；第一行就是标题")
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
