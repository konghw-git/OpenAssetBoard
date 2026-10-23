package com.assetboard.ui.todo

import androidx.compose.foundation.gestures.scrollBy

import com.assetboard.ui.components.GlassSheet
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.LocalAmbient
import com.assetboard.ui.components.ReorderState
import com.assetboard.ui.components.CarryBadge
import com.assetboard.ui.components.foldedAway
import com.assetboard.ui.components.heightAbove
import com.assetboard.ui.components.rememberReorderState
import com.assetboard.ui.components.reorderable
import com.assetboard.ui.components.stackUnder
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.style.LineBreak
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Event
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.OutlinedFlag
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.assetboard.ui.components.Ambient
import com.assetboard.ui.components.GlassSnackbarHost
import com.assetboard.ui.components.PullToCreateBox
import com.assetboard.ui.components.rememberPullToCreate
import com.assetboard.ui.components.LocalDust
import com.assetboard.ui.components.dissolvable
import com.assetboard.ui.TabPageBottomInset
import com.assetboard.ui.components.SwipeAction
import com.assetboard.ui.components.SwipeActionsBox
import com.assetboard.ui.components.SwipeGroup
import com.assetboard.ui.components.ambient
import com.assetboard.ui.components.ambientSlice
import com.assetboard.ui.components.backdropSource
import com.assetboard.ui.components.rememberAmbient
import com.assetboard.ui.components.rememberBackdrop
import com.assetboard.ui.components.rememberSwipeGroup
import com.assetboard.ui.components.TabHeader
import com.assetboard.ui.components.HeaderAction
import com.assetboard.ui.components.SettingsAction
import com.assetboard.ui.components.ErrorDialog
import com.assetboard.ui.components.ListPickerDialog
import com.assetboard.ui.components.ListRow
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin
import com.assetboard.ui.components.verticalScrollWithIndicator
import com.assetboard.ui.components.scrollIndicator

/** "inbox" | "today" | "upcoming" | "someday" | "logbook" | "list:<id>" */
object ViewKey {
    fun of(view: TodoView) = view.name.lowercase()
    fun list(id: String) = "list:$id"
    fun view(key: String): TodoView? = TodoView.entries.firstOrNull { it.name.lowercase() == key }
    fun listId(key: String): String? = key.removePrefix("list:").takeIf { key.startsWith("list:") }
}

/** Each smart view's icon and the gradient its card fills with when selected. */
internal class ViewStyle(val icon: ImageVector, val start: Color, val end: Color)

internal val viewStyles = mapOf(
    TodoView.TODAY to ViewStyle(Icons.Outlined.WbSunny, Color(0xFF2F8A73), Color(0xFF1B5246)),
    TodoView.UPCOMING to ViewStyle(Icons.Outlined.CalendarMonth, Color(0xFF6289CF), Color(0xFF3D5C99)),
    TodoView.INBOX to ViewStyle(Icons.Outlined.Inbox, Color(0xFFE2A94A), Color(0xFFC07F25)),
    TodoView.SOMEDAY to ViewStyle(Icons.Outlined.Archive, Color(0xFF9A7FCB), Color(0xFF6C539E)),
    TodoView.LOGBOOK to ViewStyle(Icons.Outlined.TaskAlt, Color(0xFF7F8B98), Color(0xFF55606C)),
)

internal val viewOrder = listOf(TodoView.TODAY, TodoView.UPCOMING, TodoView.INBOX, TodoView.SOMEDAY, TodoView.LOGBOOK)

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
 * switched in place. The dial's hub opens the new-todo field right under it, in the page, high
 * enough that the keyboard never covers it; a todo opens as a bottom sheet over the page.
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
    // The todo being edited in place; switching views ends the edit, which saves it.
    var editingId by rememberSaveable(selected) { mutableStateOf<String?>(null) }
    // The checklist item the edit began on, when it began with a tap on one rather than the title.
    var editingItem by rememberSaveable(selected) { mutableStateOf<String?>(null) }
    // A todo just gathered from the selection: its edit starts with the title blank, to be named.
    var gatheredId by rememberSaveable(selected) { mutableStateOf<String?>(null) }
    LaunchedEffect(selection.isEmpty()) { if (selection.isNotEmpty()) editingId = null }
    var rescheduleFor by remember { mutableStateOf<List<String>?>(null) }
    var moveFor by remember { mutableStateOf<List<String>?>(null) }
    var mergeFor by remember { mutableStateOf<List<String>?>(null) }
    var listDialog by remember { mutableStateOf<String?>(null) }
    var sectionDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // (section id or "", name)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val backdrop = rememberBackdrop()
    val ambient = rememberAmbient()
    val swipe = rememberSwipeGroup()
    val dust = LocalDust.current
    // Scrolling closes a row left open by a swipe.
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) swipe.closeAll() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    ErrorDialog(error) { vm.error.value = null }
    TodoSnackbars(vm, snackbar)
    fun openComposer() {
        editingId = null
        composer = true
        // Bring the dial back so the field sits just under it.
        if (listState.firstVisibleItemIndex > DIAL_ITEM) scope.launch { listState.animateScrollToItem(DIAL_ITEM) }
    }
    fun closeComposer() {
        focusManager.clearFocus()
        keyboard?.hide()
        composer = false
    }

    LaunchedEffect(request) {
        when (request) {
            OpenRequest.QuickAdd -> openComposer()
            OpenRequest.TodoToday -> selected = ViewKey.of(TodoView.TODAY)
            is OpenRequest.Todo -> detailId = request.id
            else -> return@LaunchedEffect
        }
        onRequestHandled()
    }
    BackHandler(composer) { closeComposer() }
    BackHandler(selection.isNotEmpty()) { selection = emptySet() }

    val d = data
    val view = ViewKey.view(selected)
    val listId = ViewKey.listId(selected)
    val list = listId?.let { d?.listById?.get(it) }?.takeIf { it.archivedAt == null }
    // A deleted or archived list falls back to Today.
    LaunchedEffect(d, listId) { if (d != null && listId != null && list == null) selected = ViewKey.of(TodoView.TODAY) }
    val lines = remember(d, selected, today) { d?.let { buildLines(it, view, listId, today) }.orEmpty() }
    // The order on screen; a drag rearranges it here and saves its band on release.
    var order by remember(lines) { mutableStateOf(lines) }
    // Picking up one of several selected rows carries the others of its band along: they fold
    // away while it moves (taken out of the list, they would end the gesture) and land right
    // after it, in their order, when it is let go.
    var carried by remember(lines) { mutableStateOf(emptyList<Line>()) }
    val reorder = rememberReorderState(
        listState,
        onMove = { from, to ->
            val a = order.indexOfFirst { it.key == from }
            val b = order.indexOfFirst { it.key == to }
            val x = order.getOrNull(a) as? Line.Item
            val y = order.getOrNull(b) as? Line.Item
            if (x?.band == null || x.band != y?.band) {
                false
            } else {
                order = order.toMutableList().apply { add(b, removeAt(a)) }
                true
            }
        },
        onDrop = { key ->
            if (carried.isNotEmpty()) {
                val rest = order - carried.toSet()
                val at = rest.indexOfFirst { it.key == key }
                if (at >= 0) order = rest.toMutableList().apply { addAll(at + 1, carried) }
            }
            carried = emptyList()
            val band = (order.firstOrNull { it.key == key } as? Line.Item)?.band ?: return@rememberReorderState
            vm.reorder(order.filter { it is Line.Item && it.band == band }.map { it.key })
        },
        // Held without moving: the long press selects, as on rows that cannot move.
        onHold = { key ->
            carried = emptyList()
            if (order.any { it.key == key }) selection = selection.toggle(key as String)
        },
        onStart = start@{ key ->
            val me = order.firstOrNull { it.key == key } as? Line.Item
            if (me == null || key !in selection || selection.size < 2) return@start 0f
            val ride = order.filter { it is Line.Item && it.key != key && it.key in selection && it.band == me.band }
            if (ride.isEmpty()) return@start 0f
            carried = ride
            listState.heightAbove(key, ride.map { it.key })
        },
        // Rest over an open todo to merge into it; the right-slide shortcut still uses the one
        // above. Selected companions ride along, as with the selection bar's merge action.
        canNest = { key, into ->
            val goes = listOf(key) + carried.map { it.key }
            val target = (order.firstOrNull { it.key == into } as? Line.Item)?.row
            target != null && !target.done && target.todo.kind != TodoKind.COMPLETION && into !in goes &&
                goes.all { id -> (order.firstOrNull { it.key == id } as? Line.Item)?.row?.let { !it.done && it.todo.kind == TodoKind.ORDINARY } == true }
        },
        onNest = { key, into ->
            val goes = listOf(key as String) + carried.map { it.key }
            carried = emptyList()
            selection = emptySet()
            d?.todoById?.get(into as String)?.let { vm.mergeInto(it, goes) }
        },
    )
    // The lists in the order on screen; a drag rearranges them here and saves the order on release.
    val shownLists = remember(d) { d?.lists?.filter { it.archivedAt == null }.orEmpty() }
    val checklistDrag = rememberChecklistDragState(
        d?.checklist.orEmpty(),
        allowed = if (editingId != null || selection.isNotEmpty() || reorder.dragging != null) emptySet()
            else order.mapNotNull { (it as? Line.Item)?.row?.todo?.takeIf(TodoViews::isOpen)?.id }.toSet(),
        scroll = { listState.scrollBy(it) },
        onStart = { swipe.closeAll() },
        onMove = vm::moveChecklist,
    )
    var listOrder by remember(shownLists) { mutableStateOf(shownLists) }
    // The unfolded layout's side column, where the lists are then.
    val sideState = rememberLazyListState()
    val todayRows = remember(d, today) { d?.let { TodoViews.today(it.todos, today) }.orEmpty() }
    val doneToday = todayRows.count { it.done }
    val dialGone by remember { derivedStateOf { listState.firstVisibleItemIndex > DIAL_ITEM } }
    // Pulling the list down past its top is the hub's +: it opens the field under the dial. Not
    // while the field is already open (the list just stops), nor while selecting or dragging.
    val pull = rememberPullToCreate(enabled = !composer && selection.isEmpty() && reorder.dragging == null && checklistDrag.item == null) {
        swipe.closeAll()
        openComposer()
    }
    val pullTop = with(LocalDensity.current) { pull.pulled.toDp() }
    val checkedHabits by remember(today) { vm.habitsOn(today) }.collectAsStateWithLifecycle(emptySet())
    // Rest days are no longer taken, but one taken today before that still shows as one.
    val restingHabits by remember(today) { vm.habitsSkippedOn(today) }.collectAsStateWithLifecycle(emptySet())
    val habits by vm.activeHabits.collectAsStateWithLifecycle(emptyList())
    var customizing by rememberSaveable { mutableStateOf(false) }
    if (customizing) HabitEditorSheet(habits, vm, onDismiss = { customizing = false })
    val dial: @Composable (Modifier) -> Unit = { m ->
        ViewsAndHabits(
            d, today, selected, doneToday, todayRows.size, composer, habits, checkedHabits,
            onSelect = { selected = it },
            onHub = { if (composer) closeComposer() else openComposer() },
            onHabit = vm::toggleHabit,
            onCustomize = { customizing = true },
            modifier = m,
            skipped = restingHabits,
        )
    }
    val composerField: @Composable () -> Unit = {
        InlineComposer(
            visible = composer,
            lists = d?.lists?.filter { it.archivedAt == null }.orEmpty(),
            defaults = QuickAddDefaults(listId = list?.id, plan = if (view == TodoView.TODAY) today else null, someday = view == TodoView.SOMEDAY),
            onSave = vm::quickAdd,
            onClose = ::closeComposer,
        )
    }

    val ctx = ViewContext(
        data = d, view = view, list = list, today = today, lines = order,
        selection = selection,
        onSelection = { selection = it },
        onOpen = { editingId = null; detailId = it },
        editing = editingId,
        editingItem = editingItem,
        onEdit = { id ->
            swipe.closeAll()
            if (id != null && composer) closeComposer()
            editingId = id
            editingItem = null
            if (id != gatheredId) gatheredId = null
        },
        onEditItem = { todoId, itemId ->
            swipe.closeAll()
            if (composer) closeComposer()
            editingItem = itemId
            editingId = todoId
            if (todoId != gatheredId) gatheredId = null
        },
        gathered = gatheredId,
        onGather = { ids ->
            vm.gather(ids) { id ->
                selection = emptySet()
                if (composer) closeComposer()
                gatheredId = id
                editingId = id
            }
        },
        onUngather = { id ->
            // Ending the edit first: the rows below are movable again once the todo is gone.
            editingId = null
            gatheredId = null
            vm.ungather(id)
        },
        onMerge = { mergeFor = it },
        onDissolve = { ids -> vm.dissolve(ids); selection = emptySet() },
        carried = carried.mapTo(mutableSetOf()) { it.key },
        onReschedule = { rescheduleFor = it },
        onMove = { moveFor = it },
        onListMenu = { listDialog = it },
        onSection = { sectionDialog = it },
        onAdd = if (dialGone && !composer) ({ openComposer() }) else null,
        onDelete = { ids ->
            // On-screen cards turn to dust first; the rest just go.
            scope.launch {
                dust?.dissolve(ids.map(::todoKey))
                vm.delete(ids)
            }
        },
        swipe = swipe,
        reorder = reorder,
        ambient = ambient,
        vm = vm,
    )
    val cs = MaterialTheme.colorScheme

    // A Surface, so text outside a Scaffold still gets the theme's content color.
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize().ambient(ambient)) {
        CompositionLocalProvider(LocalAmbient provides ambient, LocalChecklistDrag provides checklistDrag) {
        BoxWithConstraints(Modifier.fillMaxSize().checklistDragHost(checklistDrag)) {
            val wide = maxWidth >= 600.dp
            val listReorder = rememberReorderState(
                if (wide) sideState else listState,
                onMove = { from, to ->
                    val a = listOrder.indexOfFirst { listKey(it.id) == from }
                    val b = listOrder.indexOfFirst { listKey(it.id) == to }
                    if (a < 0 || b < 0) {
                        false
                    } else {
                        listOrder = listOrder.toMutableList().apply { add(b, removeAt(a)) }
                        true
                    }
                },
                onDrop = { vm.reorderLists(listOrder.map { it.id }) },
                // Held without moving: nothing; a list has no selection.
                onHold = {},
            )
            val lists = ListsContext(
                d, listOrder, today, selected,
                onSelect = { selected = it },
                onCreate = { name -> vm.createList(name) { selected = ViewKey.list(it) } },
                swipe = swipe, reorder = listReorder, vm = vm,
            )
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.width(340.dp).fillMaxHeight().statusBarsPadding().scrollIndicator(sideState),
                        state = sideState,
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        // The gaps of the column it replaced, which spaced everything by 14dp.
                        item("title") { Box(Modifier.padding(bottom = 14.dp)) { HomeTitle(today, doneToday, todayRows.size, onSettings) } }
                        item("banners") { Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) { ReminderBanners(vm, onGuide) } }
                        item("views") { Box(Modifier.padding(bottom = 14.dp)) { dial(Modifier) } }
                        item("composer") { Box(Modifier.padding(bottom = 8.dp)) { composerField() } }
                        listCards(lists)
                    }
                    VerticalDivider(color = cs.outlineVariant)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                            PullToCreateBox(pull, PULL_HINT, PULL_ARMED, Modifier.fillMaxSize().statusBarsPadding(), icon = Icons.Default.Add) {
                                LazyColumn(Modifier.fillMaxSize().imePadding().scrollIndicator(listState).checklistViewport(checklistDrag), state = listState, contentPadding = PaddingValues(top = pullTop, bottom = 32.dp)) {
                                    viewContent(ctx)
                                }
                            }
                        }
                        GlassSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = TabPageBottomInset), backdrop)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                    PullToCreateBox(pull, PULL_HINT, PULL_ARMED, Modifier.fillMaxSize().statusBarsPadding(), icon = Icons.Default.Add) {
                    // Keep DIAL_ITEM in step with the item order here.
                    LazyColumn(Modifier.fillMaxSize().imePadding().scrollIndicator(listState).checklistViewport(checklistDrag), state = listState, contentPadding = PaddingValues(top = pullTop, bottom = 32.dp)) {
                        item("title") { HomeTitle(today, doneToday, todayRows.size, onSettings) }
                        item("banners") { Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) { ReminderBanners(vm, onGuide) } }
                        item("views") { dial(Modifier.padding(top = 2.dp, bottom = 6.dp)) }
                        item("composer") { composerField() }
                        listCards(lists)
                        viewContent(ctx)
                    }
                    }
                }
                // Over the keyboard, and clear of the floating tab bar: an undo offered while a todo
                // is edited in place stays in reach.
                GlassSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = TabPageBottomInset), backdrop)
            }
            ChecklistDragPreview(checklistDrag)
        }
    }
}

    detailId?.let { id ->
        TodoDetailSheet(id, onDismiss = { detailId = null }, onOpenTodo = { detailId = it })
    }
    rescheduleFor?.let { ids ->
        val anyRepeat = ids.any { id -> d?.todoById?.get(id)?.kind == TodoKind.REPEAT_SOURCE }
        val one = ids.singleOrNull()?.let { d?.todoById?.get(it) }
        RescheduleDialog(
            today, allowSomeday = !anyRepeat, allowClear = !anyRepeat,
            onPick = { date, someday, time -> vm.reschedule(ids, date, someday, time); rescheduleFor = null; selection = emptySet() },
            onDismiss = { rescheduleFor = null },
            current = one?.planDate?.let(TodoViews::parse),
            currentTime = one?.remindTime?.let(java.time.LocalTime::parse),
            batch = one == null,
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
    mergeFor?.let { ids ->
        // The open todos of this view, as it orders them, that are not among the ones going in.
        val targets = order.mapNotNull { (it as? Line.Item)?.takeIf { l -> !l.row.done && l.key !in ids }?.row?.todo }
            .filter { it.kind != TodoKind.COMPLETION }
        if (targets.isEmpty()) {
            ConfirmDialog(
                "没有可以并入的待办", "这个视图里除了选中的，没有别的未完成待办。可以先切到它所在的清单或视图再选。", "知道了",
                onConfirm = { mergeFor = null }, onDismiss = { mergeFor = null },
            )
        } else {
            ListPickerDialog(
                title = "并入哪条待办的检查项",
                items = targets,
                label = { it.title },
                subtitle = { t -> d?.checklistByTodo?.get(t.id)?.size?.takeIf { it > 0 }?.let { "已有 $it 个检查项" } },
                onPick = { t -> vm.mergeInto(t, ids); mergeFor = null; selection = emptySet() },
                onDismiss = { mergeFor = null },
            )
        }
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
private fun HomeTitle(today: LocalDate, done: Int, total: Int, onSettings: () -> Unit) {
    val progress = when {
        total == 0 -> "今天没有安排"
        done == total -> "今天的事都做完了"
        else -> "今天完成 $done/$total"
    }
    TabHeader("待办", dayTitle(today, today).removePrefix("今天 · ") + " · " + progress, statusBar = false) {
        SettingsAction(onSettings)
    }
}

// ---------------------------------------------------------------- view cards

/** The dial's index among the narrow layout's LazyColumn items. */
private const val DIAL_ITEM = 2
private const val PULL_HINT = "下拉新建待办"
private const val PULL_ARMED = "松手新建待办"

// ---------------------------------------------------------------- lists

/** What the lists' cards need from the page. */
private class ListsContext(
    val data: TodoData?,
    /** The lists not archived, in the order on screen: one being dragged is already in its new place. */
    val lists: List<TodoList>,
    val today: LocalDate,
    val selected: String,
    val onSelect: (String) -> Unit,
    val onCreate: (String) -> Unit,
    val swipe: SwipeGroup,
    val reorder: ReorderState,
    val vm: TodoViewModel,
)

/** A list card's key in the LazyColumn, for its swipe and its dust. */
private fun listKey(id: String) = "list-card:$id"

private val ListCardShape = RoundedCornerShape(18.dp)

/**
 * 清单, the same on the phone and unfolded: the header with 新建, then a card for each list. A
 * tap opens the list; a left swipe deletes it (to the trash, in dust, as every left swipe in the
 * app deletes) and a right swipe archives it, each with an undo; held, it is dragged to another place.
 */
private fun LazyListScope.listCards(c: ListsContext) {
    item("lists-header") {
        var adding by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp)) {
            ListsHeader(adding, Modifier.padding(start = 4.dp)) { adding = !adding }
            NewListField(adding, c.onCreate, onClose = { adding = false })
            if (c.lists.isEmpty() && !adding) {
                Text(
                    "把一个项目拆成清单，再用分区分阶段",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
                )
            }
        }
    }
    items(c.lists, key = { listKey(it.id) }) { l ->
        val key = listKey(l.id)
        val dragged = c.reorder.dragging == key
        val cs = MaterialTheme.colorScheme
        Box(
            Modifier
                .zIndex(if (dragged) 1f else 0f)
                .graphicsLayer { translationY = if (dragged) c.reorder.offset else 0f }
                .then(if (dragged) Modifier else Modifier.animateItem())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            SwipeActionsBox(
                key = key,
                group = c.swipe,
                shape = ListCardShape,
                enabled = c.reorder.dragging == null,
                actions = listOf(SwipeAction("归档", Icons.Outlined.Archive, cs.tertiary) { c.vm.archiveList(l.id, l.name) }),
                onDelete = { c.vm.deleteList(l.id) {} },
            ) {
                ListCard(
                    l, listColor(c.data, l.id) ?: cs.primary, TodoViews.countInList(l.id, c.data?.todos.orEmpty()),
                    selected = c.selected == ViewKey.list(l.id), today = c.today, lifted = dragged,
                    modifier = Modifier.reorderable(c.reorder, key),
                ) { if (!c.reorder.ownsClick(key)) c.onSelect(ViewKey.list(l.id)) }
            }
        }
    }
}

/**
 * One list: its icon, its name with the deadline's tag under it (the way a todo's reminder sits
 * under its title), and the open todos in it. Filled with its color while open.
 */
@Composable
private fun ListCard(l: TodoList, color: Color, count: Int, selected: Boolean, today: LocalDate, lifted: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) color else Color.Transparent, tween(220), label = "listCard")
    val lift by animateFloatAsState(if (lifted) 1f else 0f, spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "lift")
    val badge = TodoViews.parse(l.deadline)?.let { TodoViews.deadlineBadge(it, today) }
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = 1f + 0.03f * lift; scaleY = scaleX }
            .then(if (selected) Modifier.shadow(8.dp + 12.dp * lift, ListCardShape, spotColor = color) else Modifier.glass(ListCardShape, glass, elevation = 6.dp + 18.dp * lift))
            .clip(ListCardShape)
            .background(bg)
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (selected) Color.White.copy(alpha = 0.22f) else color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.FormatListBulleted, null, tint = if (selected) Color.White else color, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.name, style = MaterialTheme.typography.bodyLarge, color = if (selected) Color.White else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) {
                Box(Modifier.padding(top = 3.dp)) {
                    MetaChip(Icons.Outlined.Event, badge.text, if (selected) Color.White else badgeColor(badge.tone), bold = badge.tone == com.assetboard.domain.todo.DateBadge.Tone.ALERT)
                }
            }
        }
        if (count > 0) {
            Spacer(Modifier.width(8.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White.copy(alpha = 0.8f) else cs.onSurfaceVariant)
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

// ---------------------------------------------------------------- the new-todo field

/**
 * New todos without leaving the page: a card that unfolds under the dial, like the new-list
 * field. It adds to the view on screen and stays open for the next one; Enter on an empty title,
 * the hub or Back closes it. The view's defaults reset it when the view changes.
 */
@Composable
private fun InlineComposer(visible: Boolean, lists: List<TodoList>, defaults: QuickAddDefaults, onSave: (com.assetboard.data.TodoDraft, (Boolean) -> Unit) -> Unit, onClose: () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 500f)) + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val cs = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(22.dp)
        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
                .shadow(10.dp, shape, ambientColor = AppTheme.extra.heroStart.copy(alpha = 0.3f), spotColor = AppTheme.extra.heroStart.copy(alpha = 0.35f))
                .clip(shape)
                .background(cs.surfaceContainer)
                .border(1.5.dp, cs.primary.copy(alpha = 0.45f), shape),
        ) {
            key(defaults) {
                QuickAddForm(lists, defaults, onSave = onSave, inline = true, onEmptyDone = onClose)
            }
        }
    }
}

// ---------------------------------------------------------------- the chosen view

private sealed interface Line {
    val key: String
    data class Header(override val key: String, val title: String, val sectionId: String? = null) : Line
    /** Rows with the same non-null [band] may trade places by hand. */
    data class Item(val row: ViewRow, val sectionId: String? = null, val band: String? = null) : Line {
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
    /** The todo being edited in place, if any. */
    val editing: String?,
    /** Starts editing a todo in place, or (null) ends the edit, which saves it. */
    val onEdit: (String?) -> Unit,
    /** The checklist item the edit in place began on, if it began with a tap on one. */
    val editingItem: String?,
    /** Starts editing a todo in place from a tap on one of its checklist items, which takes the cursor. */
    val onEditItem: (String, String) -> Unit,
    /** The todo just made from the selection, whose edit starts with a blank title. */
    val gathered: String?,
    /** Makes the selected todos, in the order given, the checklist of a new todo and edits it in place. */
    val onGather: (List<String>) -> Unit,
    /** Takes the todo just gathered apart again, from its editor. */
    val onUngather: (String) -> Unit,
    /** Asks which todo the selected ones, in the order given, should join as checklist items. */
    val onMerge: (List<String>) -> Unit,
    /** Lets the checklists of the selected todos go as todos of their own. */
    val onDissolve: (List<String>) -> Unit,
    /** The selected rows riding along with the one being dragged, folded away meanwhile. */
    val carried: Set<String>,
    val onReschedule: (List<String>) -> Unit,
    val onMove: (List<String>) -> Unit,
    val onListMenu: (String) -> Unit,
    val onSection: (Pair<String, String>) -> Unit,
    /** Set while the dial is scrolled away: a + in the sticky header brings the field back. */
    val onAdd: (() -> Unit)?,
    val onDelete: (List<String>) -> Unit,
    val swipe: SwipeGroup,
    val reorder: ReorderState,
    val ambient: Ambient,
    val vm: TodoViewModel,
) {
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
            is Line.Header -> {
                // A section deleted goes in dust like a row; its todos stay, loose.
                val dust = LocalDust.current
                val scope = rememberCoroutineScope()
                HeaderLine(
                    line, Modifier.animateItem().dissolvable(line.key),
                    onRename = { id, name -> c.onSection(id to name) },
                    onDelete = { id -> scope.launch { dust?.dissolve(listOf(line.key)); c.vm.deleteSection(id) } },
                    onMove = c.vm::moveSection,
                )
            }
            is Line.Item -> {
                val t = line.row.todo
                val selecting = c.selection.isNotEmpty()
                val logbook = c.view == TodoView.LOGBOOK
                val editing = c.editing == t.id
                val checklistDrag = LocalChecklistDrag.current
                val checklistMoving = checklistDrag?.item != null
                val movable = line.band != null && c.editing == null && !checklistMoving
                val dragged = c.reorder.dragging == t.id
                val nesting = dragged && c.reorder.nestInto != null
                val stack = if (dragged) c.carried.size else 0
                val stackColor = MaterialTheme.colorScheme.primary
                Box(
                    Modifier
                        .then(if (t.id in c.carried) Modifier.foldedAway() else Modifier)
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragged) c.reorder.offset else 0f
                            // Slid right, toward going into the row above; a little smaller once it would.
                            translationX = if (dragged) c.reorder.nestOffset else 0f
                            val s = if (nesting) 0.94f else 1f
                            scaleX = s; scaleY = s
                        }
                        .then(if (dragged) Modifier else Modifier.animateItem())
                        // The rows carried along peek out under the one held, as a little stack.
                        .then(if (stack > 0) Modifier.drawBehind { stackUnder(stack, stackColor, side = 16.dp.toPx(), top = 3.dp.toPx(), corner = 18.dp.toPx()) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                ) {
                    SwipeActionsBox(
                        key = todoKey(t.id),
                        group = c.swipe,
                        shape = TodoCardShape,
                        enabled = !selecting && !logbook && !editing && c.reorder.dragging == null && !checklistMoving,
                        actions = todoActions(t, line.row.done, c),
                        full = todoFullSwipe(t, line.row.done, c),
                        onDelete = { c.vm.delete(listOf(t.id)) },
                    ) {
                        val items = d.checklistByTodo[t.id].orEmpty()
                        if (editing) {
                            TodoInlineEditor(
                                t, items,
                                onCommit = { title, lines -> c.vm.saveInline(t.id, title, lines) },
                                onDone = { if (c.editing == t.id) c.onEdit(null) },
                                blankTitle = c.gathered == t.id,
                                onUndo = if (c.gathered == t.id) ({ c.onUngather(t.id) }) else null,
                                focusItem = c.editingItem,
                            )
                            return@SwipeActionsBox
                        }
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
                            onToggle = {
                                when {
                                    c.reorder.ownsClick(t.id) -> {}
                                    selecting -> c.onSelection(c.selection.toggle(t.id))
                                    else -> c.vm.toggle(t)
                                }
                            },
                            // A tap edits in place; what cannot be edited (done, a record) opens its page.
                            onClick = {
                                when {
                                    // The lift after a long press that picked the row up (and selected it, held still).
                                    c.reorder.ownsClick(t.id) -> {}
                                    selecting -> c.onSelection(c.selection.toggle(t.id))
                                    logbook || line.row.done || t.kind == TodoKind.COMPLETION -> c.onOpen(t.id)
                                    else -> c.onEdit(t.id)
                                }
                            },
                            // A movable row is picked up by a long press; held still, that selects it.
                            onLongClick = if (logbook || line.row.done || movable) null else ({ c.onSelection(c.selection.toggle(t.id)) }),
                            headerModifier = Modifier.reorderable(c.reorder, t.id, enabled = movable),
                            lifted = dragged,
                            selected = t.id in c.selection || c.reorder.nestInto == t.id,
                            inUpcoming = c.view == TodoView.UPCOMING,
                            checklist = if (logbook) emptyList() else items.sortedBy { it.sortOrder },
                            onToggleItem = if (selecting) null else ({ id -> if (!c.reorder.ownsClick(t.id) && checklistDrag?.ownsClick(id) != true) c.vm.toggleChecklist(id) }),
                            onDeleteItem = if (selecting || c.reorder.dragging != null || checklistMoving) null else c.vm::deleteChecklistItem,
                            // The item's text, like the title, edits in place; the node beside it still ticks.
                            onEditItem = if (selecting || logbook || line.row.done || t.kind == TodoKind.COMPLETION) null
                                else ({ id -> if (!c.reorder.ownsClick(t.id) && checklistDrag?.ownsClick(id) != true) c.onEditItem(t.id, id) }),
                            swipe = c.swipe,
                        )
                    }
                    when {
                        // At the leading corner: the card has slid right, its trailing one off the screen.
                        nesting -> CarryBadge(if (stack > 0) "${stack + 1} 条并入检查项" else "并入检查项", Modifier.align(Alignment.TopStart).padding(start = 28.dp))
                        dragged && c.reorder.hovering != null -> CarryBadge("停留后并入检查项", Modifier.align(Alignment.TopStart).padding(start = 28.dp))
                        stack > 0 -> CarryBadge("${stack + 1} 条", Modifier.align(Alignment.TopEnd).padding(end = 6.dp))
                    }
                }
            }
        }
    }
}

/** The dust and swipe key of a todo's card. */
private fun todoKey(id: String) = "todo:$id"

/**
 * What a right swipe reveals: the date and the flag. The full editor is the full swipe's
 * ([todoFullSwipe]), the way a full swipe left deletes. Completing is the check box's, a tap away already.
 */
@Composable
private fun todoActions(t: Todo, done: Boolean, c: ViewContext): List<SwipeAction> {
    val cs = MaterialTheme.colorScheme
    if (done) return listOf(SwipeAction("未完成", Icons.AutoMirrored.Outlined.Undo, cs.onSurfaceVariant) { c.vm.toggle(t) })
    return listOf(
        SwipeAction("改期", Icons.Outlined.CalendarMonth, cs.tertiary) { c.onReschedule(listOf(t.id)) },
        if (t.important) SwipeAction("取消重要", Icons.Outlined.OutlinedFlag, cs.secondary) { c.vm.setImportant(t, false) }
        else SwipeAction("重要", Icons.Default.Flag, Color(0xFFD08A2E)) { c.vm.setImportant(t, true) },
    )
}

/** A swipe right all the way opens the full editor; for a done row it is the one action there is. */
@Composable
private fun todoFullSwipe(t: Todo, done: Boolean, c: ViewContext): SwipeAction? =
    if (done) null else SwipeAction("编辑", Icons.Outlined.EditNote, MaterialTheme.colorScheme.primary) { c.onOpen(t.id) }

/** The view's title and actions; becomes the selection bar while selecting. Sticks to the top. */
@Composable
private fun ViewHeader(c: ViewContext) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val accent = c.view?.let { viewStyles.getValue(it).start } ?: listColor(c.data, c.list?.id) ?: cs.primary
    Box(Modifier.fillMaxWidth().ambientSlice(c.ambient).padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 8.dp)) {
        AnimatedContent(c.selection.isNotEmpty(), transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "header") { selecting ->
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp)).background(cs.primaryContainer).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The selection in the order the view shows it.
                    val picked = c.lines.mapNotNull { (it as? Line.Item)?.key?.takeIf(c.selection::contains) }
                    val withChecklist = picked.filter { c.data?.checklistByTodo?.get(it).orEmpty().isNotEmpty() }
                    var more by remember { mutableStateOf(false) }
                    IconButton(onClick = { c.onSelection(emptySet()) }) { Icon(Icons.Default.Close, "取消选择") }
                    Text("已选 ${c.selection.size} 条", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { c.onReschedule(c.selection.toList()) }) { Icon(Icons.Outlined.EventRepeat, "改期") }
                    IconButton(onClick = { c.onMove(c.selection.toList()) }) { Icon(Icons.Outlined.DriveFileMove, "移动") }
                    IconButton(onClick = { c.onDelete(c.selection.toList()); c.onSelection(emptySet()) }) { Icon(Icons.Outlined.Delete, "删除", tint = cs.error) }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
                        DropdownMenu(more, { more = false }, shape = RoundedCornerShape(18.dp)) {
                            DropdownMenuItem(
                                text = { Text("作为检查项创建新待办") },
                                leadingIcon = { Icon(Icons.Outlined.Checklist, null) },
                                onClick = { more = false; c.onGather(picked) },
                            )
                            DropdownMenuItem(
                                text = { Text("并入已有待办的检查项") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                                onClick = { more = false; c.onMerge(picked) },
                            )
                            if (withChecklist.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("解散检查项") },
                                    leadingIcon = { Icon(Icons.Outlined.CallSplit, null) },
                                    onClick = { more = false; c.onDissolve(withChecklist) },
                                )
                            }
                        }
                    }
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
                    AnimatedVisibility(c.onAdd != null, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                        Box(
                            Modifier.padding(end = 4.dp).size(36.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(AppTheme.extra.heroEnd, AppTheme.extra.heroStart)))
                                .clickable { c.onAdd?.invoke() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Add, "新待办", tint = AppTheme.extra.onHero, modifier = Modifier.size(22.dp)) }
                    }
                    if (c.list != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "清单菜单", tint = cs.onSurfaceVariant) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("新建分区") }, onClick = { menu = false; c.onSection("" to "") })
                                DropdownMenuItem(text = { Text("编辑清单") }, onClick = { menu = false; c.onListMenu("rename") })
                                DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; c.vm.moveList(c.list.id, -1) })
                                DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; c.vm.moveList(c.list.id, 1) })
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

/** A todo over the page, near full height; the same content as the detail page. */
@Composable
fun TodoDetailSheet(todoId: String, onDismiss: () -> Unit, onOpenTodo: (String) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    GlassSheet(onDismissRequest = onDismiss, sheetState = state) {
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
            Banner("精确闹钟权限已关闭，提醒最多可能晚 10 分钟", "去开启") {
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
            Banner("想让提醒准时响？按引导检查 vivo 后台设置", "开始", warning = false) { onGuide() }
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
            // Balanced lines: a wrap never leaves one character alone on the second line.
            Text(text, style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Heading), color = cs.onSurface, modifier = Modifier.weight(1f))
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
    view == TodoView.TODAY -> TodoViews.today(d.todos, today).map { Line.Item(it, band = TodoViews.todayBand(it, today)) }
    view == TodoView.UPCOMING -> TodoViews.upcoming(d.todos, today).flatMap { (date, rows) ->
        listOf(Line.Header("h-$date", dayTitle(date, today))) + rows.map { Line.Item(it, band = if (it.done) null else "$date|${it.todo.important}") }
    }
    view == TodoView.INBOX -> TodoViews.inbox(d.todos, today).map { Line.Item(it, band = if (it.done) null else "inbox") }
    view == TodoView.SOMEDAY -> TodoViews.someday(d.todos, today).map { Line.Item(it, band = if (it.done) null else "someday") }
    view == TodoView.LOGBOOK -> TodoViews.logbook(d.todos).groupBy { TodoViews.localDate(it.completedAt!!) }.flatMap { (date, rows) ->
        listOf(Line.Header("h-$date", dayTitle(date, today))) + rows.map { Line.Item(ViewRow(it, done = true, overdue = false)) }
    }
    listId != null -> {
        val rows = TodoViews.list(listId, d.todos, today)
        val sections = d.sections.filter { it.listId == listId }.sortedBy { it.sortOrder }
        val loose = rows.filter { it.todo.sectionId == null || sections.none { s -> s.id == it.todo.sectionId } }
        fun band(r: ViewRow, section: String?) = if (r.done) null else "section|$section"
        loose.map { Line.Item(it, band = band(it, null)) } + sections.flatMap { s ->
            listOf(Line.Header("s-${s.id}", s.name, s.id)) + rows.filter { it.todo.sectionId == s.id }.map { Line.Item(it, s.id, band(it, s.id)) }
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
