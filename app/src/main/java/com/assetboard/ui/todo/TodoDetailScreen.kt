package com.assetboard.ui.todo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.AppContainer
import com.assetboard.data.ChecklistItem
import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.data.TodoList
import com.assetboard.data.TodoSection
import com.assetboard.data.UndoOutcome
import com.assetboard.domain.Dates
import com.assetboard.domain.calendar.ChineseCalendar
import com.assetboard.domain.todo.IntervalUnit
import com.assetboard.domain.todo.Recurrence
import com.assetboard.domain.todo.RepeatRule
import com.assetboard.domain.todo.RepeatType
import com.assetboard.domain.todo.TodoViews
import com.assetboard.ui.ActionViewModel
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.DustHost
import com.assetboard.ui.components.DustState
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.components.LocalDust
import com.assetboard.ui.components.UndoEvent
import com.assetboard.ui.components.UndoMessages
import com.assetboard.ui.components.dissolvable
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.ListPickerDialog
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.RowDivider
import com.assetboard.ui.components.SectionCard
import com.assetboard.ui.components.SectionHeader
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime

data class DetailState(
    val todo: Todo?,
    val checklist: List<ChecklistItem>,
    val lists: List<TodoList>,
    val sections: List<TodoSection>,
    val source: Todo?,
    val loaded: Boolean,
)

@OptIn(FlowPreview::class)
class TodoDetailViewModel(container: AppContainer, private val id: String) : ActionViewModel(container) {
    private val repo = container.todos
    val state: StateFlow<DetailState> = repo.data.map { d ->
        val t = d?.todoById?.get(id)
        DetailState(
            todo = t,
            checklist = d?.checklistByTodo?.get(id).orEmpty().sortedBy { it.sortOrder },
            lists = d?.lists?.filter { it.archivedAt == null }.orEmpty(),
            sections = d?.sections.orEmpty(),
            source = t?.repeatSourceId?.let { d?.todoById?.get(it) },
            loaded = d != null,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DetailState(null, emptyList(), emptyList(), emptyList(), null, false))

    val events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)
    val confirmRecord = MutableStateFlow(false)
    private val title = MutableStateFlow<String?>(null)
    private val notes = MutableStateFlow<String?>(null)
    /** One text write at a time, each writing the latest text, so an older one never lands last. */
    private val textLock = Mutex()

    init {
        launchBound { title.debounce(600).collect { runCatching { commitText() } } }
        launchBound { notes.debounce(600).collect { runCatching { commitText() } } }
    }

    fun editTitle(v: String) { title.value = v }
    fun editNotes(v: String) { notes.value = v }

    /** Writes the typed title and notes now; the repository skips what is already saved. */
    private suspend fun commitText() = textLock.withLock {
        title.value?.let { if (it.isNotBlank()) repo.setTitle(id, it) }
        notes.value?.let { repo.setNotes(id, it) }
    }

    /** Leaving the page: write pending text now, on the app scope so it outlives the screen. */
    fun flush(container: AppContainer) {
        if (title.value == null && notes.value == null) return
        container.appScope.launch(epoch) { runCatching { commitText() } }
    }

    fun setPlan(date: LocalDate?, time: LocalTime?) = action { repo.setPlan(id, date, time) }
    fun setSomeday(v: Boolean) = action { repo.setSomeday(id, v) }
    fun setDeadline(d: LocalDate?) = action { repo.setDeadline(id, d) }
    fun setImportant(v: Boolean) = action { repo.setImportant(id, v) }
    fun setStrong(v: Boolean) = action { repo.setStrongRemind(id, v) }
    fun setRepeat(rule: RepeatRule?, onDone: () -> Unit) = action(onSuccess = onDone) { repo.setRepeat(id, rule) }
    fun move(listId: String?, sectionId: String?) = action { repo.move(id, listId, sectionId) }
    fun addItem(text: String) = action { if (text.isNotBlank()) repo.addChecklistItem(id, text) }
    fun toggleItem(itemId: String) = action { repo.toggleChecklist(itemId) }
    fun editItem(itemId: String, text: String) = action { repo.setChecklistText(itemId, text) }
    fun deleteItem(itemId: String) = action {
        val item = repo.deleteChecklistItem(itemId) ?: return@action
        events.tryEmit(UndoEvent("已删除检查项「${item.text.take(14)}」") { repo.restoreChecklistItem(item) })
    }

    fun undo(e: UndoEvent) {
        val u = e.undo ?: return
        action { u() }
    }

    // Completing and skipping copy the title into the record and fix the undo revision, so the
    // typed text goes in first.
    fun complete() = action {
        val t = state.value.todo ?: return@action
        commitText()
        repo.complete(t.id, t.stateSeq)
        events.tryEmit(UndoEvent(if (t.kind == TodoKind.REPEAT_SOURCE) "本期已完成，已排到下一期" else "已完成"))
    }

    fun uncomplete(deleteOnly: Boolean = false) = action {
        if (repo.uncomplete(id, deleteOnly) == UndoOutcome.NEEDS_CONFIRM) confirmRecord.value = true
    }

    fun skip() = action {
        val t = state.value.todo ?: return@action
        commitText()
        repo.skip(t.id, t.stateSeq)
        events.tryEmit(UndoEvent("已跳过本期"))
    }

    fun delete(onDeleted: () -> Unit) = action(onSuccess = onDeleted) { repo.delete(id) }
}

/** The detail as a page of its own: notification taps that land outside the todo tab, tests. */
@Composable
fun TodoDetailScreen(todoId: String, onBack: () -> Unit, onOpenTodo: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        TodoDetailContent(todoId, onClose = onBack, onOpenTodo = onOpenTodo, inSheet = false, modifier = Modifier.statusBarsPadding())
    }
}

/** The todo's page: title and notes, checklist, schedule, list. [inSheet] drops the back arrow for a close. */
@Composable
fun TodoDetailContent(todoId: String, onClose: () -> Unit, onOpenTodo: (String) -> Unit, inSheet: Boolean, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val vm: TodoDetailViewModel = viewModel(key = "todo-$todoId") { TodoDetailViewModel(container, todoId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val confirmRecord by vm.confirmRecord.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val today = rememberToday()
    ErrorDialog(error) { vm.error.value = null }
    UndoMessages(vm.events, snackbar, vm::undo)
    // The detail is usually a sheet, a window over the app's dust overlay: it has dust of its own.
    val dust = remember { DustState() }
    DisposableEffect(vm) { onDispose { vm.flush(container) } }
    LaunchedEffect(state.loaded, state.todo) { if (state.loaded && state.todo == null) onClose() }
    val t = state.todo ?: return
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    val record = t.kind == TodoKind.COMPLETION
    val done = !TodoViews.isOpen(t)
    val ended = t.kind == TodoKind.REPEAT_SOURCE && t.repeatEndedAt != null
    val editable = !record && !ended

    Box(modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalDust provides dust) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(if (inSheet) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack, if (inSheet) "关闭" else "返回")
                }
                Text(if (record) "完成记录" else "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(menu, { menu = false }) {
                        if (t.kind == TodoKind.REPEAT_SOURCE && !ended) DropdownMenuItem(text = { Text("跳过本期") }, onClick = { menu = false; vm.skip() })
                        DropdownMenuItem(text = { Text("删除", color = cs.error) }, onClick = { menu = false; vm.delete(onClose) })
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Title and notes
                SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        CheckCircle(
                            checked = done, important = t.important, repeat = t.kind == TodoKind.REPEAT_SOURCE,
                            onToggle = { if (record || (t.kind == TodoKind.ORDINARY && done)) vm.uncomplete() else if (!ended) vm.complete() },
                        )
                        var title by rememberSaveable(t.id) { mutableStateOf(t.title) }
                        BasicTextField(
                            value = title,
                            onValueChange = { title = it.replace("\n", ""); vm.editTitle(title) },
                            readOnly = !editable,
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                color = if (done) cs.onSurfaceVariant else cs.onSurface,
                                textDecoration = if (done) TextDecoration.LineThrough else null,
                            ),
                            cursorBrush = SolidColor(cs.primary),
                            modifier = Modifier.weight(1f).padding(top = 9.dp),
                        )
                    }
                    var notes by rememberSaveable(t.id) { mutableStateOf(t.notes) }
                    Box(Modifier.padding(start = 44.dp, top = 4.dp)) {
                        if (notes.isEmpty() && editable) Text("备注", style = MaterialTheme.typography.bodyMedium, color = cs.outline)
                        BasicTextField(
                            value = notes,
                            onValueChange = { notes = it; vm.editNotes(it) },
                            readOnly = !editable,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (state.checklist.isNotEmpty() || editable) Checklist(state.checklist, editable, vm)

                if (record) {
                    RecordCard(t, state.source, onOpenSource = { state.source?.let { onOpenTodo(it.id) } }, onUndo = { vm.uncomplete() })
                } else {
                    Schedule(t, today, editable, onDialog = { dialog = it }, vm = vm)
                    SectionCard {
                        val listName = state.lists.firstOrNull { it.id == t.listId }?.name
                        ListRow(
                            title = "清单", leading = { Icon(Icons.Outlined.FormatListBulleted, null, tint = cs.primary) },
                            trailing = { Text(listName ?: "收件箱", color = cs.onSurfaceVariant) },
                            onClick = if (editable) ({ dialog = "list" }) else null,
                        )
                        val sections = state.sections.filter { it.listId == t.listId }
                        if (sections.isNotEmpty()) {
                            RowDivider(58.dp)
                            ListRow(
                                title = "分区", leading = { Icon(Icons.Outlined.ViewAgenda, null, tint = cs.primary) },
                                trailing = { Text(sections.firstOrNull { it.id == t.sectionId }?.name ?: "无分区", color = cs.onSurfaceVariant) },
                                onClick = if (editable) ({ dialog = "section" }) else null,
                            )
                        }
                        RowDivider(58.dp)
                        ListRow(
                            title = "重要", leading = { Icon(Icons.Default.Flag, null, tint = cs.error) },
                            trailing = { Switch(t.important, { vm.setImportant(it) }, enabled = editable) },
                            chevron = false,
                        )
                    }
                    if (!ended) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (done) {
                                OutlinedButton(onClick = { vm.uncomplete() }, modifier = Modifier.weight(1f)) { Text("取消完成") }
                            } else {
                                Button(onClick = vm::complete, modifier = Modifier.weight(1f)) { Text(if (t.kind == TodoKind.REPEAT_SOURCE) "完成本期" else "完成") }
                                if (t.kind == TodoKind.REPEAT_SOURCE) OutlinedButton(onClick = vm::skip, modifier = Modifier.weight(1f)) { Text("跳过本期") }
                            }
                        }
                    } else {
                        Text("这条重复待办已经结束，只作为完成记录的来源保留。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Text("创建于 " + Dates.dateTime(t.createdAt), style = MaterialTheme.typography.bodySmall, color = cs.outline, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
        }
        DustHost(dust)
        GlassSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    when (dialog) {
        "plan" -> RescheduleDialog(
            today,
            allowSomeday = t.kind == TodoKind.ORDINARY,
            allowClear = t.kind == TodoKind.ORDINARY,
            onPick = { d, someday ->
                dialog = null
                if (someday) vm.setSomeday(true) else vm.setPlan(d, t.remindTime?.let(LocalTime::parse))
            },
            onDismiss = { dialog = null },
        )
        "remind" -> TimePickDialog(
            initial = t.remindTime?.let(LocalTime::parse),
            onDismiss = { dialog = null },
            onPick = { time ->
                dialog = null
                vm.setPlan(TodoViews.parse(t.planDate) ?: today, time)
                // The first reminder is the moment to ask for the notification permission.
                if (android.os.Build.VERSION.SDK_INT >= 33 && !com.assetboard.reminder.ReminderScheduler.hasNotificationPermission(context)) {
                    askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onClear = if (t.remindTime != null) ({ dialog = null; vm.setPlan(TodoViews.parse(t.planDate), null) }) else null,
        )
        "deadline" -> DatePickDialog(
            initial = TodoViews.parse(t.deadline) ?: today, maxDate = null,
            onDismiss = { dialog = null }, onPick = { dialog = null; vm.setDeadline(it) },
        )
        "repeat" -> RepeatDialog(
            current = RepeatRule.decodeOrNull(t.repeatRule),
            plan = TodoViews.parse(t.planDate)!!,
            occurrenceCount = t.occurrenceCount,
            onDismiss = { dialog = null },
            onSave = { vm.setRepeat(it) { dialog = null } },
        )
        "list" -> ListPickerDialog(
            title = "移动到", items = listOf<TodoList?>(null) + state.lists, label = { it?.name ?: "收件箱" },
            onPick = { dialog = null; vm.move(it?.id, null) }, onDismiss = { dialog = null },
        )
        "section" -> ListPickerDialog(
            title = "分区", items = listOf<TodoSection?>(null) + state.sections.filter { it.listId == t.listId }, label = { it?.name ?: "无分区" },
            onPick = { dialog = null; vm.move(t.listId, it?.id) }, onDismiss = { dialog = null },
        )
    }
    if (confirmRecord) {
        ConfirmDialog(
            title = "无法还原重复待办",
            text = "撤销只会删除这条完成记录，不会让它变回未完成。",
            confirmLabel = "删除记录",
            destructive = true,
            onConfirm = { vm.confirmRecord.value = false; vm.uncomplete(deleteOnly = true) },
            onDismiss = { vm.confirmRecord.value = false },
        )
    }
}

@Composable
private fun Schedule(t: Todo, today: LocalDate, editable: Boolean, onDialog: (String) -> Unit, vm: TodoDetailViewModel) {
    val cs = MaterialTheme.colorScheme
    val plan = TodoViews.parse(t.planDate)
    val deadline = TodoViews.parse(t.deadline)
    SectionCard {
        SectionHeader("安排")
        ListRow(
            title = "计划日",
            leading = { Icon(Icons.Outlined.CalendarMonth, null, tint = cs.primary) },
            trailing = { Text(when { t.someday -> "以后再说"; plan != null -> dayTitle(plan, today); else -> "未安排" }, color = cs.onSurfaceVariant) },
            onClick = if (editable) ({ onDialog("plan") }) else null,
        )
        RowDivider(58.dp)
        ListRow(
            title = "提醒",
            subtitle = if (t.someday) "「以后再说」的待办不提醒" else null,
            leading = { Icon(Icons.Outlined.Notifications, null, tint = cs.primary) },
            trailing = { Text(t.remindTime ?: "不提醒", color = cs.onSurfaceVariant) },
            onClick = if (editable && !t.someday) ({ onDialog("remind") }) else null,
        )
        if (t.remindTime != null) {
            RowDivider(58.dp)
            ListRow(
                title = "强提醒",
                subtitle = "到点全屏响铃，锁屏也会亮，最长 5 分钟",
                leading = { Icon(Icons.Outlined.Alarm, null, tint = cs.primary) },
                trailing = { Switch(t.strongRemind, { vm.setStrong(it) }, enabled = editable) },
                chevron = false,
            )
        }
        RowDivider(58.dp)
        ListRow(
            title = "截止日",
            subtitle = if (deadline != null && plan != null && deadline.isBefore(plan)) "截止日早于计划日" else null,
            leading = { Icon(Icons.Outlined.Event, null, tint = cs.primary) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(deadline?.let { dayTitle(it, today) } ?: "无", color = if (deadline != null && !deadline.isAfter(today)) cs.error else cs.onSurfaceVariant)
                    if (deadline != null && editable) IconButton(onClick = { vm.setDeadline(null) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "清除截止日", Modifier.size(16.dp)) }
                }
            },
            onClick = if (editable) ({ onDialog("deadline") }) else null,
        )
        RowDivider(58.dp)
        val rule = RepeatRule.decodeOrNull(t.repeatRule)
        val progress = rule?.count?.let { n -> "第 ${(t.occurrenceCount - rule.countBase + 1).coerceAtMost(n)} / $n 次" }
        ListRow(
            title = "重复",
            subtitle = when {
                t.kind == TodoKind.REPEAT_SOURCE && t.repeatEndedAt != null -> "已结束"
                plan == null && t.kind == TodoKind.ORDINARY -> "先设置计划日，才能重复"
                else -> progress
            },
            leading = { Icon(Icons.Outlined.Repeat, null, tint = cs.primary) },
            trailing = { Text(rule?.let(Recurrence::describe) ?: "不重复", color = cs.onSurfaceVariant) },
            onClick = if (editable && plan != null && t.completedAt == null) ({ onDialog("repeat") }) else null,
        )
        if (t.kind == TodoKind.ORDINARY && t.completedAt == null) {
            RowDivider(58.dp)
            ListRow(
                title = "以后再说",
                leading = { Icon(Icons.Outlined.Archive, null, tint = cs.primary) },
                trailing = { Switch(t.someday, { vm.setSomeday(it) }, enabled = editable) },
                chevron = false,
            )
        }
    }
}

@Composable
private fun Checklist(items: List<ChecklistItem>, editable: Boolean, vm: TodoDetailViewModel) {
    val cs = MaterialTheme.colorScheme
    val dust = LocalDust.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    SectionCard {
        SectionHeader("检查项" + if (items.isNotEmpty()) "  ${items.count { it.done }}/${items.size}" else "")
        items.forEach { item ->
            key(item.id) {
            Row(Modifier.fillMaxWidth().dissolvable(checklistKey(item.id)).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CheckCircle(item.done, false, onToggle = { if (editable) vm.toggleItem(item.id) })
                var text by remember(item.id) { mutableStateOf(item.text) }
                var focused by remember(item.id) { mutableStateOf(false) }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", "") },
                    readOnly = !editable,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = if (item.done) cs.onSurfaceVariant else cs.onSurface,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.editItem(item.id, text) }),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.weight(1f).onFocusChanged { f ->
                        if (focused && !f.isFocused && text != item.text && text.isNotBlank()) vm.editItem(item.id, text)
                        focused = f.isFocused
                    },
                )
                if (editable) {
                    IconButton(
                        onClick = {
                            // The item alone turns to dust, as a swiped todo does.
                            scope.launch {
                                dust?.dissolve(listOf(checklistKey(item.id)), cell = with(density) { 2.5.dp.toPx() })
                                vm.deleteItem(item.id)
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) { Icon(Icons.Default.Close, "删除检查项", Modifier.size(16.dp), tint = cs.outline) }
                }
            }
            }
        }
        if (editable) {
            var draft by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+", color = cs.primary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (draft.isEmpty()) Text("添加检查项", color = cs.outline, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it.replace("\n", "") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.addItem(draft); draft = "" }),
                        cursorBrush = SolidColor(cs.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordCard(t: Todo, source: Todo?, onOpenSource: () -> Unit, onUndo: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        Text("完成于 " + Dates.dateTime(t.completedAt!!), style = MaterialTheme.typography.titleSmall)
        t.planDate?.let { Text("本期计划日 $it", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                source == null -> "来源的重复待办已被永久删除。"
                else -> "来自重复待办「${source.title}」。"
            },
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onUndo) { Text("撤销这一期") }
            if (source != null && source.deletedAt == null) TextButton(onClick = onOpenSource) { Text("查看重复待办") }
        }
    }
}

// ---------------------------------------------------------------- repeat editor

@Composable
fun RepeatDialog(current: RepeatRule?, plan: LocalDate, occurrenceCount: Int, onDismiss: () -> Unit, onSave: (RepeatRule?) -> Unit) {
    val lunar = remember(plan) { com.nlf.calendar.Solar.fromYmd(plan.year, plan.monthValue, plan.dayOfMonth).lunar }
    var type by rememberSaveable { mutableStateOf(current?.type ?: RepeatType.DAILY) }
    var interval by rememberSaveable { mutableStateOf((current?.interval ?: 1).toString()) }
    var unit by rememberSaveable { mutableStateOf(current?.unit ?: IntervalUnit.DAY) }
    var weekdays by rememberSaveable { mutableStateOf(current?.weekdays?.takeIf { it.isNotEmpty() } ?: listOf(plan.dayOfWeek.value)) }
    var day by rememberSaveable { mutableStateOf((current?.day?.takeIf { current.type != RepeatType.LUNAR_YEARLY } ?: plan.dayOfMonth).toString()) }
    var month by rememberSaveable { mutableStateOf((current?.month?.takeIf { current.type == RepeatType.YEARLY } ?: plan.monthValue).toString()) }
    var lunarMonth by rememberSaveable { mutableStateOf(if (current?.type == RepeatType.LUNAR_YEARLY) current.month else kotlin.math.abs(lunar.month)) }
    var lunarDay by rememberSaveable { mutableStateOf(if (current?.type == RepeatType.LUNAR_YEARLY) current.day else lunar.day) }
    var end by rememberSaveable { mutableStateOf(when { current?.count != null -> "count"; current?.untilDate != null -> "until"; else -> "none" }) }
    var count by rememberSaveable { mutableStateOf((current?.count ?: 10).toString()) }
    var until by rememberSaveable { mutableStateOf(current?.untilDate ?: plan.plusMonths(1).toString()) }
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }

    fun build(): RepeatRule = RepeatRule(
        type = type,
        interval = interval.toIntOrNull() ?: 0,
        unit = unit,
        weekdays = if (type == RepeatType.WEEKLY) weekdays.sorted() else emptyList(),
        month = when (type) { RepeatType.YEARLY -> month.toIntOrNull() ?: 0; RepeatType.LUNAR_YEARLY -> lunarMonth; else -> 1 },
        day = when (type) { RepeatType.MONTHLY_DAY, RepeatType.YEARLY -> day.toIntOrNull() ?: 0; RepeatType.LUNAR_YEARLY -> lunarDay; else -> 1 },
        untilDate = until.takeIf { end == "until" },
        count = count.toIntOrNull()?.takeIf { end == "count" } ?: if (end == "count") 0 else null,
        countBase = current?.countBase ?: 0,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重复") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RepeatType.entries.forEach { rt -> FilterChip(selected = type == rt, onClick = { type = rt }, label = { Text(rt.label) }) }
                }
                when (type) {
                    RepeatType.DAILY -> NumberLine("每", interval, "天") { interval = it }
                    RepeatType.WEEKLY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..7).forEach { d ->
                            FilterChip(
                                selected = d in weekdays,
                                onClick = { weekdays = if (d in weekdays) weekdays - d else weekdays + d },
                                label = { Text(Recurrence.WEEK_NAMES[d - 1]) },
                            )
                        }
                    }
                    RepeatType.MONTHLY_DAY -> NumberLine("每月", day, "号（小月落到月末）") { day = it }
                    RepeatType.MONTHLY_LAST -> Text("每月的最后一天", style = MaterialTheme.typography.bodyMedium)
                    RepeatType.YEARLY -> Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberLine("每年", month, "月", Modifier.weight(1f)) { month = it }
                        NumberLine("", day, "日", Modifier.weight(1f)) { day = it }
                    }
                    RepeatType.WORKDAY -> Text("按法定节假日和调休判断，缺少某年数据时按周一到周五。", style = MaterialTheme.typography.bodyMedium)
                    RepeatType.AFTER_COMPLETION -> Column {
                        NumberLine("完成后", interval, "") { interval = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IntervalUnit.entries.forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.label) }) }
                        }
                    }
                    RepeatType.LUNAR_YEARLY -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("农历")
                        Box {
                            OutlinedButton(onClick = { menu = "m" }) { Text(ChineseCalendar.lunarMonthName(lunarMonth)) }
                            DropdownMenu(menu == "m", { menu = null }) {
                                (1..12).forEach { m -> DropdownMenuItem(text = { Text(ChineseCalendar.lunarMonthName(m)) }, onClick = { lunarMonth = m; menu = null }) }
                            }
                        }
                        Box {
                            OutlinedButton(onClick = { menu = "d" }) { Text(ChineseCalendar.lunarDayName(lunarDay)) }
                            DropdownMenu(menu == "d", { menu = null }) {
                                (1..30).forEach { d -> DropdownMenuItem(text = { Text(ChineseCalendar.lunarDayName(d)) }, onClick = { lunarDay = d; menu = null }) }
                            }
                        }
                    }
                }
                Text("结束", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = end == "none", onClick = { end = "none" }, label = { Text("不结束") })
                    FilterChip(selected = end == "until", onClick = { end = "until"; picking = true }, label = { Text(if (end == "until") "至 $until" else "截止某日") })
                    FilterChip(selected = end == "count", onClick = { end = "count" }, label = { Text("共 N 次") })
                }
                if (end == "count") {
                    NumberLine("共", count, "次（完成和跳过都算）") { count = it }
                    current?.let { Text("从设置重复时开始计数，目前已执行 ${Recurrence.executed(it, occurrenceCount)} 次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(build()) }) { Text("保存") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("不再重复", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
    if (picking) {
        DatePickDialog(initial = LocalDate.parse(until), minDate = plan, maxDate = null, onDismiss = { picking = false }, onPick = { until = it.toString(); picking = false })
    }
}

@Composable
private fun NumberLine(prefix: String, value: String, suffix: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (prefix.isNotEmpty()) Text(prefix, modifier = Modifier.padding(end = 8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { v -> onChange(v.filter(Char::isDigit).take(4)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(80.dp),
        )
        if (suffix.isNotEmpty()) Text(suffix, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
