package com.assetboard.ui.todo

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetboard.data.Todo
import com.assetboard.data.TodoData
import com.assetboard.data.TodoKind
import com.assetboard.data.TodoList
import com.assetboard.domain.Dates
import com.assetboard.domain.todo.TodoView
import com.assetboard.domain.todo.TodoViews
import com.assetboard.domain.todo.ViewRow
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.OpenRequest
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.TabHeader
import com.assetboard.ui.components.PrimaryActionButton
import com.assetboard.ui.components.HeaderAction
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.ListPickerDialog
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.theme.AppTheme
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin

/** "inbox" | "today" | "upcoming" | "someday" | "logbook" | "list:<id>" */
object ViewKey {
    fun of(view: TodoView) = view.name.lowercase()
    fun list(id: String) = "list:$id"
    fun view(key: String): TodoView? = TodoView.entries.firstOrNull { it.name.lowercase() == key }
    fun listId(key: String): String? = key.removePrefix("list:").takeIf { key.startsWith("list:") }
}

/** Each smart view's icon and the gradient its card fills with when selected. */
private class ViewStyle(val icon: ImageVector, val start: Color, val end: Color)

private val viewStyles = mapOf(
    TodoView.TODAY to ViewStyle(Icons.Outlined.WbSunny, Color(0xFF2F8A73), Color(0xFF1B5246)),
    TodoView.UPCOMING to ViewStyle(Icons.Outlined.CalendarMonth, Color(0xFF6289CF), Color(0xFF3D5C99)),
    TodoView.INBOX to ViewStyle(Icons.Outlined.Inbox, Color(0xFFE2A94A), Color(0xFFC07F25)),
    TodoView.SOMEDAY to ViewStyle(Icons.Outlined.Archive, Color(0xFF9A7FCB), Color(0xFF6C539E)),
    TodoView.LOGBOOK to ViewStyle(Icons.Outlined.TaskAlt, Color(0xFF7F8B98), Color(0xFF55606C)),
)

private val viewOrder = listOf(TodoView.TODAY, TodoView.UPCOMING, TodoView.INBOX, TodoView.SOMEDAY, TodoView.LOGBOOK)

@Composable
private fun todoViewModel(): TodoViewModel {
    val container = LocalContainer.current
    return viewModel(key = "todo") { TodoViewModel(container) }
}

/** A list's accent: the group palette, by its place among the lists. */
@Composable
private fun listColor(d: TodoData?, listId: String?): Color? {
    if (d == null || listId == null) return null
    val i = d.lists.filter { it.archivedAt == null }.indexOfFirst { it.id == listId }
    return if (i < 0) null else AppTheme.extra.group(i)
}

// ---------------------------------------------------------------- home

/**
 * The todo tab on one page: smart view cards and list pills on top, the chosen view below, all
 * switched in place. New todos come from the centered + as a sheet docked to the keyboard; a
 * todo opens as a bottom sheet over the page.
 */
@Composable
fun TodoHomeScreen(
    onSettings: () -> Unit,
    onGuide: () -> Unit,
    request: OpenRequest? = null,
    onRequestHandled: () -> Unit = {},
    initialView: String = ViewKey.of(TodoView.TODAY),
) {
    val vm = todoViewModel()
    val data by vm.data.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val confirm by vm.confirmRecord.collectAsStateWithLifecycle()
    val today = rememberToday()
    val snackbar = remember { SnackbarHostState() }
    var selected by rememberSaveable { mutableStateOf(initialView) }
    var composer by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var selection by remember(selected) { mutableStateOf(setOf<String>()) }
    var sorting by remember(selected) { mutableStateOf(false) }
    var rescheduleFor by remember { mutableStateOf<List<String>?>(null) }
    var moveFor by remember { mutableStateOf<List<String>?>(null) }
    var listDialog by remember { mutableStateOf<String?>(null) }
    var sectionDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // (section id or "", name)
    ErrorDialog(error) { vm.error.value = null }
    TodoSnackbars(vm, snackbar)

    LaunchedEffect(request) {
        when (request) {
            OpenRequest.QuickAdd -> composer = true
            OpenRequest.TodoToday -> selected = ViewKey.of(TodoView.TODAY)
            is OpenRequest.Todo -> detailId = request.id
            else -> return@LaunchedEffect
        }
        onRequestHandled()
    }
    BackHandler(selection.isNotEmpty()) { selection = emptySet() }
    BackHandler(sorting) { sorting = false }

    val d = data
    val view = ViewKey.view(selected)
    val listId = ViewKey.listId(selected)
    val list = listId?.let { d?.listById?.get(it) }?.takeIf { it.archivedAt == null }
    // A deleted or archived list falls back to Today.
    LaunchedEffect(d, listId) { if (d != null && listId != null && list == null) selected = ViewKey.of(TodoView.TODAY) }
    val lines = remember(d, selected, today) { d?.let { buildLines(it, view, listId, today) }.orEmpty() }

    val ctx = ViewContext(
        data = d, view = view, list = list, today = today, lines = lines,
        selection = selection,
        onSelection = { selection = it },
        onOpen = { detailId = it },
        onReschedule = { rescheduleFor = it },
        onMove = { moveFor = it },
        onSort = { sorting = !sorting },
        onListMenu = { listDialog = it },
        onSection = { sectionDialog = it },
        vm = vm,
    )
    val cs = MaterialTheme.colorScheme

    // A Surface, so text outside a Scaffold still gets the theme's content color.
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        HomeTitle(today, d, onSettings)
                        Box(Modifier.padding(horizontal = 16.dp)) { ReminderBanners(vm, onGuide) }
                        ViewDial(d, today, selected, onSelect = { selected = it })
                        ListCards(d, selected, onSelect = { selected = it }, onCreate = { name -> vm.createList(name) { selected = ViewKey.list(it) } })
                    }
                    VerticalDivider(color = cs.outlineVariant)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (sorting) {
                            SortMode(ctx, Modifier.statusBarsPadding())
                        } else {
                            LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 132.dp)) {
                                viewContent(ctx)
                            }
                        }
                        AddButton(visible = !composer && selection.isEmpty() && !sorting, Modifier.align(Alignment.BottomCenter)) { composer = true }
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
                    }
                }
            } else {
                if (sorting) {
                    SortMode(ctx, Modifier.statusBarsPadding())
                } else {
                    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 132.dp)) {
                        item("title") { HomeTitle(today, d, onSettings) }
                        item("banners") { Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { ReminderBanners(vm, onGuide) } }
                        item("views") { ViewDial(d, today, selected, onSelect = { selected = it }, modifier = Modifier.padding(vertical = 4.dp)) }
                        item("lists") { ListStrip(d, selected, onSelect = { selected = it }, onCreate = { name -> vm.createList(name) { selected = ViewKey.list(it) } }) }
                        viewContent(ctx)
                    }
                }
                AddButton(visible = !composer && selection.isEmpty() && !sorting, Modifier.align(Alignment.BottomCenter)) { composer = true }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
            }
            QuickComposer(
                visible = composer,
                lists = d?.lists?.filter { it.archivedAt == null }.orEmpty(),
                defaults = QuickAddDefaults(listId = list?.id, plan = if (view == TodoView.TODAY) today else null, someday = view == TodoView.SOMEDAY),
                onSave = { vm.quickAdd(it) },
                onDismiss = { composer = false },
            )
        }
    }

    detailId?.let { id ->
        TodoDetailSheet(id, onDismiss = { detailId = null }, onOpenTodo = { detailId = it })
    }
    rescheduleFor?.let { ids ->
        val anyRepeat = ids.any { id -> d?.todoById?.get(id)?.kind == TodoKind.REPEAT_SOURCE }
        RescheduleDialog(
            today, allowSomeday = !anyRepeat, allowClear = !anyRepeat,
            onPick = { date, someday -> vm.reschedule(ids, date, someday); rescheduleFor = null; selection = emptySet() },
            onDismiss = { rescheduleFor = null },
        )
    }
    moveFor?.let { ids ->
        ListPickerDialog(
            title = "移动到",
            items = listOf<TodoList?>(null) + d?.lists?.filter { it.archivedAt == null }.orEmpty(),
            label = { it?.name ?: "收件箱" },
            onPick = { vm.move(ids, it?.id); moveFor = null; selection = emptySet() },
            onDismiss = { moveFor = null },
        )
    }
    confirm?.let {
        ConfirmDialog(
            title = "无法还原重复待办",
            text = "这条重复待办之后已经改过、完成了下一期、在回收站里或已被永久删除。撤销只会删除这条完成记录，不会让它变回未完成。",
            confirmLabel = "删除记录",
            destructive = true,
            onConfirm = vm::confirmDeleteRecord,
            onDismiss = { vm.confirmRecord.value = null },
        )
    }
    sectionDialog?.let { (id, name) ->
        NameDialog(if (id.isEmpty()) "新建分区" else "重命名分区", name, onDismiss = { sectionDialog = null }) { n ->
            sectionDialog = null
            if (id.isEmpty()) list?.let { vm.createSection(it.id, n) } else vm.renameSection(id, n)
        }
    }
    if (list != null) {
        when (listDialog) {
            "rename" -> ListEditDialog(list, onDismiss = { listDialog = null }) { name, notes, deadline ->
                listDialog = null
                vm.updateList(list.id, name, notes, deadline)
            }
            "archive" -> ConfirmDialog(
                "归档「${list.name}」？", "清单会从上方消失，可以在「日志」里找回。", "归档",
                onConfirm = { listDialog = null; vm.setArchived(list.id, true) }, onDismiss = { listDialog = null },
            )
            "delete" -> ConfirmDialog(
                "删除「${list.name}」？", "清单、其中的分区和待办一起进回收站，30 天内可以恢复。已完成的重复记录留在日志里。", "删除",
                destructive = true,
                onConfirm = { listDialog = null; vm.deleteList(list.id) {} },
                onDismiss = { listDialog = null },
            )
        }
    }
}

@Composable
private fun HomeTitle(today: LocalDate, d: TodoData?, onSettings: () -> Unit) {
    val open = d?.let { TodoViews.count(TodoView.TODAY, it.todos, today) } ?: 0
    TabHeader(
        "待办",
        dayTitle(today, today).removePrefix("今天 · ") + if (open > 0) " · 今天还有 $open 件" else " · 今天的事都做完了",
        statusBar = false,
    ) {
        HeaderAction(Icons.Outlined.Settings, "设置", onSettings)
    }
}

// ---------------------------------------------------------------- view cards

/**
 * The five smart views as a ring of cards around a hub, sized to the width so all five fit
 * without scrolling. The hub shows the chosen view's count inside today's progress ring; the
 * cards fly out from the hub on first show.
 */
@Composable
private fun ViewDial(d: TodoData?, today: LocalDate, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val cardW = (maxWidth * 0.27f).coerceIn(84.dp, 108.dp)
        val cardH = cardW * 0.86f
        // The two upper side cards sit at ±cos 18° of the radius; keep them inside the width.
        val radius = ((maxWidth - cardW) / (2 * 0.951f)).coerceAtMost(150.dp)
        val hub = radius * 0.9f
        val height = radius * 1.809f + cardH
        val centerX = maxWidth / 2
        val centerY = radius + cardH / 2
        val fly = remember { Animatable(0f) }
        LaunchedEffect(Unit) { fly.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 260f)) }
        val ring = rememberInfiniteTransition(label = "dial")
        val spin by ring.animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "spin")
        val dash = MaterialTheme.colorScheme.outlineVariant
        Box(
            Modifier.fillMaxWidth().height(height).drawBehind {
                // A faint dashed orbit through the cards, turning slowly.
                val c = Offset(centerX.toPx(), centerY.toPx())
                rotate(spin, c) {
                    drawCircle(dash, radius.toPx() * fly.value, c, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 8.dp.toPx()))))
                }
            },
        ) {
            DialHub(d, today, selected, Modifier.offset(centerX - hub / 2, centerY - hub / 2).size(hub))
            viewOrder.forEachIndexed { i, v ->
                val angle = Math.toRadians(-90.0 + 72.0 * i)
                val r = radius * fly.value
                val x = centerX + r * cos(angle).toFloat() - cardW / 2
                val y = centerY + r * sin(angle).toFloat() - cardH / 2
                ViewCard(
                    v, d, today, selected == ViewKey.of(v),
                    Modifier.offset(x, y).size(cardW, cardH).graphicsLayer { val k = 0.4f + 0.6f * fly.value; scaleX = k; scaleY = k; alpha = fly.value.coerceIn(0f, 1f) },
                ) { onSelect(ViewKey.of(v)) }
            }
        }
    }
}

/** The middle of the dial: the chosen view's count, ringed by how much of today is done. */
@Composable
private fun DialHub(d: TodoData?, today: LocalDate, selected: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val view = ViewKey.view(selected)
    val style = view?.let { viewStyles.getValue(it) }
    val listColor = listColor(d, ViewKey.listId(selected))
    val start by animateColorAsState(style?.start ?: listColor ?: cs.primary, tween(320), label = "hubStart")
    val end by animateColorAsState(style?.end ?: listColor ?: cs.primary, tween(320), label = "hubEnd")
    val todayRows = d?.let { TodoViews.today(it.todos, it.lists, today) }.orEmpty()
    val doneToday = todayRows.count { it.done }
    val progress = if (todayRows.isEmpty()) 0f else doneToday.toFloat() / todayRows.size
    val shown by animateFloatAsState(progress, tween(700), label = "progress")
    val count = when {
        d == null -> 0
        view == TodoView.LOGBOOK -> TodoViews.logbook(d.todos).count { TodoViews.localDate(it.completedAt!!) == today }
        view != null -> TodoViews.count(view, d.todos, today)
        else -> ViewKey.listId(selected)?.let { TodoViews.countInList(it, d.todos) } ?: 0
    }
    val label = when (view) {
        TodoView.LOGBOOK -> "今天完成"
        null -> d?.listById?.get(ViewKey.listId(selected))?.name ?: ""
        else -> view.label
    }
    val track = cs.outlineVariant
    Box(
        modifier
            .drawBehind {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                if (shown > 0f) drawArc(Brush.sweepGradient(listOf(start, end, start)), -90f, 360f * shown, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            .padding(10.dp)
            .shadow(10.dp, CircleShape, spotColor = end)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(start, end))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(count, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "hubCount") {
                Text(it.toString(), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            if (todayRows.isNotEmpty()) {
                Text("今日完成 $doneToday/${todayRows.size}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
private fun ViewCard(v: TodoView, d: TodoData?, today: LocalDate, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val style = viewStyles.getValue(v)
    val cs = MaterialTheme.colorScheme
    val n = d?.let { TodoViews.count(v, it.todos, today) } ?: 0
    val overdue = v == TodoView.TODAY && d != null && d.todos.any { TodoViews.isOpen(it) && TodoViews.isOverdue(it, today) }
    val fill by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "fill")
    val fg by animateColorAsState(if (selected) Color.White else cs.onSurface, tween(260), label = "fg")
    val sub by animateColorAsState(if (selected) Color.White.copy(alpha = 0.82f) else cs.onSurfaceVariant, tween(260), label = "sub")
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .pressScale(source)
            .shadow(if (selected) 12.dp else 3.dp, shape, ambientColor = style.end.copy(alpha = 0.4f), spotColor = if (selected) style.end else Color.Black.copy(alpha = 0.12f))
            .clip(shape)
            .background(cs.surfaceContainer)
            .background(Brush.linearGradient(listOf(style.start.copy(alpha = fill), style.end.copy(alpha = fill))))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(12.dp),
    ) {
        Box {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (selected) Color.White.copy(alpha = 0.22f) else style.start.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(style.icon, null, tint = if (selected) Color.White else style.end, modifier = Modifier.size(18.dp))
            }
            // Something overdue: a badge on the icon.
            if (overdue) {
                Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(if (selected) Color.White else cs.surfaceContainer).padding(2.dp).clip(CircleShape).background(cs.error))
            }
        }
        if (v != TodoView.LOGBOOK) {
            AnimatedContent(
                targetState = n,
                transitionSpec = { (slideInVertically { if (targetState > initialState) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (targetState > initialState) -it else it } + fadeOut()) },
                modifier = Modifier.align(Alignment.TopEnd),
                label = "count",
            ) { count ->
                Text(count.toString(), style = MaterialTheme.typography.titleLarge, color = fg, fontWeight = FontWeight.Bold)
            }
        }
        Text(v.label, style = MaterialTheme.typography.titleSmall, color = sub, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart))
    }
}

// ---------------------------------------------------------------- lists

@Composable
private fun ListStrip(d: TodoData?, selected: String, onSelect: (String) -> Unit, onCreate: (String) -> Unit) {
    val lists = d?.lists?.filter { it.archivedAt == null }.orEmpty()
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 6.dp)) {
        ListsHeader(adding, Modifier.padding(start = 20.dp, end = 16.dp)) { adding = !adding }
        NewListField(adding, onCreate, onClose = { adding = false }, Modifier.padding(horizontal = 16.dp))
        if (lists.isEmpty() && !adding) {
            Text(
                "把一个项目拆成清单，再用分区分阶段",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 6.dp),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(lists, key = { _, l -> l.id }) { i, l ->
                ListPill(l, AppTheme.extra.group(i), TodoViews.countInList(l.id, d!!.todos), selected == ViewKey.list(l.id), Modifier.animateItem()) { onSelect(ViewKey.list(l.id)) }
            }
        }
    }
}

/** "清单" with the new-list button at its right end, where it stays in reach however many lists there are. */
@Composable
private fun ListsHeader(adding: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (adding) 45f else 0f, spring(dampingRatio = 0.6f), label = "plus")
    Row(modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("清单", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(cs.primary.copy(alpha = if (adding) 0.16f else 0.08f))
                .clickable(onClick = onToggle)
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Add, null, tint = cs.primary, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn })
            Spacer(Modifier.width(2.dp))
            Text(if (adding) "取消" else "新建", style = MaterialTheme.typography.labelLarge, color = cs.primary)
        }
    }
}

/**
 * The new list's name, full width under the header: focused when it opens, Enter or 创建 makes
 * the list and opens it, an empty field closes on leaving it.
 */
@Composable
private fun NewListField(visible: Boolean, onCreate: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val cs = MaterialTheme.colorScheme
        var name by rememberSaveable { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        var hadFocus by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        fun create() {
            if (name.isBlank()) return
            onCreate(name.trim())
            name = ""
            onClose()
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(cs.surfaceContainer)
                .border(1.5.dp, cs.primary.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FormatListBulleted, null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (name.isEmpty()) Text("新清单的名字，比如「搬家」", style = MaterialTheme.typography.bodyLarge, color = cs.outline, maxLines = 1)
                BasicTextField(
                    name, { name = it.replace("\n", "") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isBlank()) onClose() else create() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { f ->
                        if (hadFocus && !f.isFocused && name.isBlank()) onClose()
                        if (f.isFocused) hadFocus = true
                    },
                )
            }
            val ready = name.isNotBlank()
            TextButton(onClick = ::create, enabled = ready) { Text("创建") }
        }
    }
}

@Composable
private fun ListPill(l: TodoList, color: Color, count: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) color else cs.surfaceContainer, tween(220), label = "pill")
    val fg = if (selected) Color.White else cs.onSurface
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(44.dp)
            .pressScale(source)
            .shadow(if (selected) 8.dp else 2.dp, RoundedCornerShape(50), spotColor = if (selected) color else Color.Black.copy(alpha = 0.1f))
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (selected) Color.White.copy(alpha = 0.22f) else color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.FormatListBulleted, null, tint = if (selected) Color.White else color, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(l.name, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = if (selected) Color.White.copy(alpha = 0.8f) else cs.onSurfaceVariant)
        }
    }
}

/** The wide layout's lists: one long card each. */
@Composable
private fun ListCards(d: TodoData?, selected: String, onSelect: (String) -> Unit, onCreate: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val lists = d?.lists?.filter { it.archivedAt == null }.orEmpty()
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListsHeader(adding, Modifier.padding(start = 4.dp)) { adding = !adding }
        NewListField(adding, onCreate, onClose = { adding = false })
        lists.forEachIndexed { i, l ->
            val color = AppTheme.extra.group(i)
            val on = selected == ViewKey.list(l.id)
            val bg by animateColorAsState(if (on) color else cs.surfaceContainer, tween(220), label = "card")
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(bg).clickable { onSelect(ViewKey.list(l.id)) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (on) Color.White.copy(alpha = 0.22f) else color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.FormatListBulleted, null, tint = if (on) Color.White else color, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(l.name, style = MaterialTheme.typography.bodyLarge, color = if (on) Color.White else cs.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                CountText(TodoViews.countInList(l.id, d!!.todos))
            }
        }
    }
}

// ---------------------------------------------------------------- the + button and the composer

@Composable
private fun AddButton(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible,
        modifier = modifier.padding(bottom = 28.dp),
        enter = scaleIn(spring(dampingRatio = 0.55f)) + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        PrimaryActionButton(Icons.Rounded.Add, "新待办", onClick)
    }
}

/**
 * The quick-add sheet without leaving the page: slides up over a light scrim and rides on top of
 * the keyboard. Tapping outside or Back closes it.
 */
@Composable
private fun QuickComposer(visible: Boolean, lists: List<TodoList>, defaults: QuickAddDefaults, onSave: (com.assetboard.data.TodoDraft) -> Unit, onDismiss: () -> Unit) {
    BackHandler(visible) { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = 500f)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 16.dp,
                modifier = Modifier.fillMaxWidth().imePadding().clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.outlineVariant))
                    QuickAddForm(lists, defaults, onSave = onSave)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the chosen view

private sealed interface Line {
    val key: String
    data class Header(override val key: String, val title: String, val sectionId: String? = null) : Line
    data class Item(val row: ViewRow, val sectionId: String? = null) : Line {
        override val key get() = row.todo.id
    }
}

/** Everything the view section needs from the page. */
private class ViewContext(
    val data: TodoData?,
    val view: TodoView?,
    val list: TodoList?,
    val today: LocalDate,
    val lines: List<Line>,
    val selection: Set<String>,
    val onSelection: (Set<String>) -> Unit,
    val onOpen: (String) -> Unit,
    val onReschedule: (List<String>) -> Unit,
    val onMove: (List<String>) -> Unit,
    val onSort: () -> Unit,
    val onListMenu: (String) -> Unit,
    val onSection: (Pair<String, String>) -> Unit,
    val vm: TodoViewModel,
) {
    val manual get() = view == TodoView.INBOX || list != null
    val title get() = view?.label ?: list?.name.orEmpty()
}

private fun LazyListScope.viewContent(c: ViewContext) {
    val d = c.data ?: return
    stickyHeader("view-header") { ViewHeader(c) }
    val listId = c.list?.id
    if (c.list != null && (c.list.notes.isNotBlank() || c.list.deadline != null)) {
        item("list-notes-$listId") { ListNotes(c.list, c.today) }
    }
    if (c.view == TodoView.LOGBOOK) archivedLists(d, c.vm)
    val empty = c.lines.none { it is Line.Item } && !(c.view == TodoView.LOGBOOK && d.lists.any { it.archivedAt != null })
    if (empty) item("empty-${c.view}-$listId") { EmptyView(c.view, Modifier.animateItem()) }
    items(c.lines, key = { it.key }) { line ->
        when (line) {
            is Line.Header -> HeaderLine(line, Modifier.animateItem(), onRename = { id, name -> c.onSection(id to name) }, onDelete = c.vm::deleteSection, onMove = c.vm::moveSection)
            is Line.Item -> {
                val t = line.row.todo
                val selecting = c.selection.isNotEmpty()
                val logbook = c.view == TodoView.LOGBOOK
                Box(Modifier.animateItem().padding(horizontal = 16.dp, vertical = 5.dp)) {
                    SwipeRow(
                        enabled = !line.row.done && !selecting && !logbook,
                        onComplete = { c.vm.complete(t) },
                        onReschedule = { c.onReschedule(listOf(t.id)) },
                    ) {
                        val items = d.checklistByTodo[t.id].orEmpty()
                        val showList = listId == null && c.view != TodoView.INBOX
                        val extras = RowExtras(
                            listName = if (showList) t.listId?.let { d.listById[it]?.name } else null,
                            listColor = if (showList) listColor(d, t.listId) else null,
                            checklistDone = items.count { it.done },
                            checklistTotal = items.size,
                        ).let {
                            if (logbook) it.copy(listName = listOfNotNull(Dates.time(t.completedAt!!), t.listId?.let { id -> d.listById[id]?.name }).joinToString(" · ")) else it
                        }
                        TodoRow(
                            todo = t,
                            done = line.row.done || logbook,
                            today = c.today,
                            extras = extras,
                            onToggle = { if (selecting) c.onSelection(c.selection.toggle(t.id)) else c.vm.toggle(t) },
                            onClick = { if (selecting) c.onSelection(c.selection.toggle(t.id)) else c.onOpen(t.id) },
                            onLongClick = if (logbook || line.row.done) null else ({ c.onSelection(c.selection.toggle(t.id)) }),
                            selected = t.id in c.selection,
                            inUpcoming = c.view == TodoView.UPCOMING,
                        )
                    }
                }
            }
        }
    }
}

/** The view's title and actions; becomes the selection bar while selecting. Sticks to the top. */
@Composable
private fun ViewHeader(c: ViewContext) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val accent = c.view?.let { viewStyles.getValue(it).start } ?: listColor(c.data, c.list?.id) ?: cs.primary
    Box(Modifier.fillMaxWidth().background(cs.background).padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 8.dp)) {
        AnimatedContent(c.selection.isNotEmpty(), transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "header") { selecting ->
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp)).background(cs.primaryContainer).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { c.onSelection(emptySet()) }) { Icon(Icons.Default.Close, "取消选择") }
                    Text("已选 ${c.selection.size} 条", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { c.onReschedule(c.selection.toList()) }) { Icon(Icons.Outlined.EventRepeat, "改期") }
                    IconButton(onClick = { c.onMove(c.selection.toList()) }) { Icon(Icons.Outlined.DriveFileMove, "移动") }
                    IconButton(onClick = { c.vm.delete(c.selection.toList()); c.onSelection(emptySet()) }) { Icon(Icons.Outlined.Delete, "删除", tint = cs.error) }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(50)).background(accent))
                    Spacer(Modifier.width(10.dp))
                    AnimatedContent(c.title, transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith fadeOut() }, label = "title", modifier = Modifier.weight(1f)) { title ->
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            val n = c.lines.count { it is Line.Item && !it.row.done }
                            if (n > 0 && c.view != TodoView.LOGBOOK) {
                                Text("  $n 项", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                            }
                        }
                    }
                    if (c.manual) {
                        IconButton(onClick = c.onSort) { Icon(Icons.Outlined.SwapVert, "排序", tint = cs.onSurfaceVariant) }
                    }
                    if (c.list != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "清单菜单", tint = cs.onSurfaceVariant) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("新建分区") }, onClick = { menu = false; c.onSection("" to "") })
                                DropdownMenuItem(text = { Text("编辑清单") }, onClick = { menu = false; c.onListMenu("rename") })
                                DropdownMenuItem(text = { Text("左移") }, onClick = { menu = false; c.vm.moveList(c.list.id, -1) })
                                DropdownMenuItem(text = { Text("右移") }, onClick = { menu = false; c.vm.moveList(c.list.id, 1) })
                                DropdownMenuItem(text = { Text("归档（已完成）") }, onClick = { menu = false; c.onListMenu("archive") })
                                DropdownMenuItem(text = { Text("删除清单", color = cs.error) }, onClick = { menu = false; c.onListMenu("delete") })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Sort mode for the inbox and lists: a page of its own until 完成. */
@Composable
private fun SortMode(c: ViewContext, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleLarge)
                Text("按住右侧把手拖动排序", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            TextButton(onClick = c.onSort) { Text("完成") }
        }
        val d = c.data ?: return@Column
        SortableList(c.lines, c.today, extras = { t ->
            val items = d.checklistByTodo[t.id].orEmpty()
            RowExtras(checklistDone = items.count { it.done }, checklistTotal = items.size)
        }, onCommit = c.vm::reorder)
    }
}

/** A todo over the page, near full height; the same content as the detail page. */
@Composable
fun TodoDetailSheet(todoId: String, onDismiss: () -> Unit, onOpenTodo: (String) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.outlineVariant)) },
    ) {
        TodoDetailContent(todoId, onClose = onDismiss, onOpenTodo = onOpenTodo, inSheet = true)
    }
}

/** Missing permissions and missed reminders: always visible while they apply. */
@Composable
fun ReminderBanners(vm: TodoViewModel, onGuide: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val missed by vm.missed.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    var notificationsOn by remember { mutableStateOf(true) }
    var exactOn by remember { mutableStateOf(true) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            notificationsOn = container.reminders.notificationsEnabled()
            exactOn = container.reminders.exactAllowed()
            container.reminders.refreshMissed()
        }
    }
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        notificationsOn = container.reminders.notificationsEnabled()
    }
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!notificationsOn) {
            val off = missed.count { it.notificationsOff }
            Banner(
                if (off > 0) "通知已关闭，期间错过 $off 条提醒" else "通知已关闭，提醒无法送达",
                "去开启",
            ) {
                if (Build.VERSION.SDK_INT >= 33 && !com.assetboard.reminder.ReminderScheduler.hasNotificationPermission(context)) {
                    askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
        }
        if (!exactOn && Build.VERSION.SDK_INT >= 31) {
            Banner("精确闹钟权限已关闭，提醒可能推迟最多 10 分钟", "去开启") {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
            }
        }
        if (missed.isNotEmpty()) {
            Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.NotificationsOff, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("错过的提醒（${missed.size}）", style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::clearMissed) { Text("知道了") }
                    }
                    missed.takeLast(5).reversed().forEach {
                        Text(
                            "${Dates.dateTime(it.at)}  ${it.title}" + if (it.notificationsOff) "（通知已关闭）" else "",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }
        val guideSeen by container.settings.reminderGuideSeen.collectAsStateWithLifecycle()
        if (!guideSeen && (data?.todos?.any { it.remindTime != null } == true)) {
            Banner("想让提醒准时响？按引导检查一遍 vivo 的后台设置", "开始", warning = false) { onGuide() }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, warning: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = if (warning) cs.error else cs.primary
    Surface(color = tint.copy(alpha = 0.08f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action, color = tint) }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

private fun buildLines(d: TodoData, view: TodoView?, listId: String?, today: LocalDate): List<Line> = when {
    view == TodoView.TODAY -> TodoViews.today(d.todos, d.lists, today).map { Line.Item(it) }
    view == TodoView.UPCOMING -> TodoViews.upcoming(d.todos, today).flatMap { (date, rows) ->
        listOf(Line.Header("h-$date", dayTitle(date, today))) + rows.map { Line.Item(it) }
    }
    view == TodoView.INBOX -> TodoViews.inbox(d.todos, today).map { Line.Item(it) }
    view == TodoView.SOMEDAY -> TodoViews.someday(d.todos, today).map { Line.Item(it) }
    view == TodoView.LOGBOOK -> TodoViews.logbook(d.todos).groupBy { TodoViews.localDate(it.completedAt!!) }.flatMap { (date, rows) ->
        listOf(Line.Header("h-$date", dayTitle(date, today))) + rows.map { Line.Item(ViewRow(it, done = true, overdue = false)) }
    }
    listId != null -> {
        val rows = TodoViews.list(listId, d.todos, today)
        val sections = d.sections.filter { it.listId == listId }.sortedBy { it.sortOrder }
        val loose = rows.filter { it.todo.sectionId == null || sections.none { s -> s.id == it.todo.sectionId } }
        loose.map { Line.Item(it) } + sections.flatMap { s ->
            listOf(Line.Header("s-${s.id}", s.name, s.id)) + rows.filter { it.todo.sectionId == s.id }.map { Line.Item(it, s.id) }
        }
    }
    else -> emptyList()
}

@Composable
private fun HeaderLine(line: Line.Header, modifier: Modifier, onRename: (String, String) -> Unit, onDelete: (String) -> Unit, onMove: (String, Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(line.title, style = MaterialTheme.typography.titleSmall, color = if (line.sectionId != null) cs.primary else cs.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(cs.outlineVariant))
        if (line.sectionId != null) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "分区菜单", Modifier.size(18.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename(line.sectionId, line.title) })
                    DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; onMove(line.sectionId, -1) })
                    DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; onMove(line.sectionId, 1) })
                    DropdownMenuItem(text = { Text("删除分区", color = cs.error) }, onClick = { menu = false; onDelete(line.sectionId) })
                }
            }
        }
    }
}

@Composable
private fun ListNotes(list: TodoList, today: LocalDate) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer).padding(horizontal = 16.dp, vertical = 12.dp)) {
        list.deadline?.let { Text("截止 " + dayTitle(LocalDate.parse(it), today), style = MaterialTheme.typography.labelLarge, color = cs.error) }
        if (list.notes.isNotBlank()) Text(list.notes, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

private fun LazyListScope.archivedLists(d: TodoData, vm: TodoViewModel) {
    val archived = d.lists.filter { it.archivedAt != null }
    if (archived.isEmpty()) return
    item("archived-header") {
        Text("已归档的清单", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
    }
    items(archived, key = { "archived-" + it.id }) { l ->
        ListRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
            title = l.name,
            subtitle = "归档于 " + Dates.date(l.archivedAt!!),
            leading = { Icon(Icons.Outlined.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailing = { TextButton(onClick = { vm.setArchived(l.id, false) }) { Text("取消归档") } },
            chevron = false,
        )
    }
}

@Composable
private fun EmptyView(view: TodoView?, modifier: Modifier = Modifier) {
    val text = when (view) {
        TodoView.TODAY -> "今天没有要做的事"
        TodoView.INBOX -> "收件箱是空的。想到什么先记在这里，之后再安排。"
        TodoView.UPCOMING -> "没有安排在以后的待办"
        TodoView.SOMEDAY -> "没有「以后再说」的事"
        TodoView.LOGBOOK -> "完成的待办会按时间记在这里"
        null -> "这个清单还是空的"
    }
    val style = view?.let { viewStyles.getValue(it) }
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background((style?.start ?: cs.primary).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(style?.icon ?: Icons.Outlined.FormatListBulleted, null, tint = style?.end ?: cs.primary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * Sort mode for the inbox and lists: drag a row by its handle; it only moves among rows of the
 * same section. The new order is saved when the drag ends.
 */
@Composable
private fun SortableList(lines: List<Line>, today: LocalDate, extras: (Todo) -> RowExtras, onCommit: (List<String>) -> Unit) {
    var order by remember(lines) { mutableStateOf(lines.filter { it !is Line.Item || !it.row.done }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val state = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
        items(order, key = { it.key }) { line ->
            when (line) {
                is Line.Header -> Text(line.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 4.dp))
                is Line.Item -> {
                    val id = line.key
                    val isDragged = dragging == id
                    Box(
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 5.dp)
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragged) offset else 0f; shadowElevation = if (isDragged) 24f else 0f; scaleX = if (isDragged) 1.02f else 1f; scaleY = scaleX; shape = TodoCardShape; clip = false }
                            .then(if (isDragged) Modifier else Modifier.animateItem()),
                    ) {
                        TodoRow(
                            todo = line.row.todo, done = false, today = today, extras = extras(line.row.todo),
                            onToggle = {}, onClick = {},
                            trailing = {
                                Icon(
                                    Icons.Default.DragHandle, "拖动排序",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp).size(28.dp).pointerInput(id) {
                                        detectDragGestures(
                                            onDragStart = { dragging = id; offset = 0f },
                                            onDragEnd = {
                                                dragging = null
                                                offset = 0f
                                                onCommit(order.filterIsInstance<Line.Item>().map { it.key })
                                            },
                                            onDragCancel = { dragging = null; offset = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                offset += amount.y
                                                val visible = state.layoutInfo.visibleItemsInfo
                                                val me = visible.firstOrNull { it.key == id } ?: return@detectDragGestures
                                                val center = me.offset + offset + me.size / 2
                                                val target = visible.firstOrNull { it.key != id && center.toInt() in it.offset until it.offset + it.size }
                                                    ?: return@detectDragGestures
                                                val from = order.indexOfFirst { it.key == id }
                                                val to = order.indexOfFirst { it.key == target.key }
                                                val other = order[to]
                                                if (other is Line.Item && other.sectionId == line.sectionId) {
                                                    order = order.toMutableList().apply { add(to, removeAt(from)) }
                                                    offset -= (target.offset - me.offset)
                                                }
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListEditDialog(list: TodoList, onDismiss: () -> Unit, onSave: (String, String, LocalDate?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(list.name) }
    var notes by rememberSaveable { mutableStateOf(list.notes) }
    var deadline by rememberSaveable { mutableStateOf(list.deadline) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑清单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("备注") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(deadline?.let { "截止 $it" } ?: "没有截止日", modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = true }) { Text("选择") }
                    if (deadline != null) TextButton(onClick = { deadline = null }) { Text("清除") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, notes, deadline?.let(LocalDate::parse)) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (picking) {
        com.assetboard.ui.components.DatePickDialog(
            initial = deadline?.let(LocalDate::parse) ?: LocalDate.now(), maxDate = null,
            onDismiss = { picking = false }, onPick = { deadline = it.toString(); picking = false },
        )
    }
}
