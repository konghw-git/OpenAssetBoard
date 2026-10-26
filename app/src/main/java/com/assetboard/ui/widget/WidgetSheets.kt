package com.assetboard.ui.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.NextPlan
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.assetboard.AppContainer
import com.assetboard.MainActivity
import com.assetboard.WidgetSheetActivity
import com.assetboard.data.Countdown
import com.assetboard.data.NoteSession
import com.assetboard.data.NoteText
import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.data.UndoOutcome
import com.assetboard.domain.AppClock
import com.assetboard.domain.CountdownState
import com.assetboard.domain.Countdowns
import com.assetboard.domain.Dates
import com.assetboard.domain.LedgerException
import com.assetboard.domain.todo.DateBadge
import com.assetboard.domain.todo.TodoViews
import com.assetboard.ui.LocalContainer
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.FieldShape
import com.assetboard.ui.components.LocalAmbient
import com.assetboard.ui.components.ambient
import com.assetboard.ui.components.rememberAmbient
import com.assetboard.ui.components.IconTile
import com.assetboard.ui.components.Motion
import com.assetboard.ui.components.SelectField
import com.assetboard.ui.components.appear
import com.assetboard.ui.components.auroraSurface
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.glassFieldColors
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.todo.QuickAddDefaults
import com.assetboard.ui.todo.QuickAddForm
import com.assetboard.ui.todo.rememberToday
import com.assetboard.widget.CountdownWidgetReceiver
import com.assetboard.widget.NetWorthWidgetReceiver
import com.assetboard.widget.NoteWidgetReceiver
import com.assetboard.widget.TodoWidgetReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs

/** What a widget asked the sheet to show: one of [WidgetSheetActivity]'s modes, on [id]. */
data class SheetRequest(val mode: String, val id: String?)

/**
 * The sheet over the home screen. Countdowns go from the list into one and back inside the same
 * sheet, the content sliding sideways; everything else is one page that closes when done.
 */
@Composable
fun WidgetSheets(request: SheetRequest, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(request) }
    // Slides the sheet away, then ends the activity.
    val dismiss: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onClose() } }
    val fromList = request.mode == WidgetSheetActivity.COUNTDOWN_LIST
    SheetSurface(onDismissRequest = onClose, sheetState = sheet) {
        AnimatedContent(
            page,
            transitionSpec = {
                val forward = targetState.mode != WidgetSheetActivity.COUNTDOWN_LIST
                (slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(Motion.SHORT)))
                    .togetherWith(slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { w -> if (forward) -w / 3 else w / 3 } + fadeOut(tween(Motion.SHORT)))
                    .using(SizeTransform(clip = false))
            },
            label = "sheet-page",
        ) { r ->
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                when (r.mode) {
                    WidgetSheetActivity.TODO_ADD -> TodoAddPage()
                    WidgetSheetActivity.TODO_EDIT -> TodoEditPage(r.id.orEmpty(), dismiss)
                    WidgetSheetActivity.NOTE_NEW, WidgetSheetActivity.NOTE_EDIT -> NotePage(r.id, dismiss)
                    WidgetSheetActivity.COUNTDOWN_ADD, WidgetSheetActivity.COUNTDOWN_EDIT -> CountdownEditPage(
                        r.id,
                        onDone = { if (fromList) page = SheetRequest(WidgetSheetActivity.COUNTDOWN_LIST, null) else dismiss() },
                    )
                    WidgetSheetActivity.COUNTDOWN_LIST -> CountdownListPage(
                        onOpen = { id -> page = SheetRequest(WidgetSheetActivity.COUNTDOWN_EDIT, id) },
                        onAdd = { page = SheetRequest(WidgetSheetActivity.COUNTDOWN_ADD, null) },
                    )
                    WidgetSheetActivity.WIDGET_GALLERY -> WidgetGalleryPage()
                    else -> LaunchedEffect(Unit) { dismiss() }
                }
            }
        }
    }
}

/**
 * [GlassSheet], with its colour fields running on under the navigation bar: over a home screen
 * there is no page below to meet, so a plain strip there would show. The pages pad themselves.
 */
@Composable
private fun SheetSurface(onDismissRequest: () -> Unit, sheetState: SheetState, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val a = rememberAmbient()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = cs.background,
        tonalElevation = 0.dp,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // The sheet is a window of its own; with three-button navigation the system greys the bar over it.
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.isNavigationBarContrastEnforced = false }
        CompositionLocalProvider(LocalAmbient provides a) {
            Column(Modifier.fillMaxWidth().ambient(a)) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(cs.onSurface.copy(alpha = 0.18f)))
                }
                content()
            }
        }
    }
}

// ------------------------------------------------------------ writing from the sheet

/**
 * Writes started from the sheet: on the app scope with the epoch bound when the sheet opened, so
 * one started as the sheet goes away still lands. A refused write says why in a toast; the
 * sheet may be gone by then.
 */
private class SheetWriter(val container: AppContainer, val context: Context) {
    val scope: CoroutineScope = container.appScope + container.gate.bound()

    fun write(onDone: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(context, (e as? LedgerException)?.message ?: "没有保存成功，请再试一次")
            }
        }
    }
}

@Composable
private fun rememberWriter(): SheetWriter {
    val container = LocalContainer.current
    val context = LocalContext.current.applicationContext
    return remember { SheetWriter(container, context) }
}

private suspend fun toast(context: Context, text: String) = withContext(Dispatchers.Main) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

private fun openApp(context: Context, open: String, id: String? = null) {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, open)
            .apply { if (id != null) putExtra(MainActivity.EXTRA_ID, id) },
    )
}

// ------------------------------------------------------------ shared pieces

@Composable
private fun PageHeader(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** A round icon button on glass, springing under the thumb. */
@Composable
private fun GlassIcon(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface, on: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(40.dp)
            .pressScale(source, 0.9f)
            .glass(CircleShape, com.assetboard.ui.components.glass, tint = if (on) accent.copy(alpha = 0.16f) else Color.Unspecified, elevation = 4.dp)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (on) accent else tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * One of a row of big actions: a tinted disc with the icon over a label, equal widths, landing
 * one after another as the sheet opens.
 */
@Composable
private fun RowScope.ActionTile(index: Int, icon: ImageVector, label: String, color: Color, onClick: () -> Unit, filled: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .weight(1f)
            .appear(index)
            .pressScale(source, 0.92f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val bg by animateColorAsState(if (filled) color else color.copy(alpha = 0.14f), tween(Motion.SHORT), label = "tile")
        Box(Modifier.size(46.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (filled) Color.White else color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** The gradient pill that finishes a page. */
@Composable
private fun DoneButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val extra = AppTheme.extra
    val source = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(if (enabled) 1f else 0.94f, Motion.bouncy(), label = "done")
    Box(
        Modifier
            .scale(scale)
            .pressScale(source, 0.94f)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) Brush.linearGradient(listOf(extra.heroEnd, extra.heroStart)) else SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest))
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = if (enabled) extra.onHero else MaterialTheme.colorScheme.outline)
    }
}

/** A pill that switches on and off. */
@Composable
private fun Toggle(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (on) color else cs.onSurface.copy(alpha = 0.06f), tween(Motion.SHORT), label = "toggle")
    val fg = if (on) Color.White else cs.onSurface
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) fg else cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

// ------------------------------------------------------------ todos

/** Quick add, as in the app: Enter saves and keeps the sheet open for the next one. */
@Composable
private fun TodoAddPage() {
    val container = LocalContainer.current
    val writer = rememberWriter()
    val lists = container.todos.data.collectAsState().value?.lists?.filter { it.archivedAt == null }.orEmpty()
    QuickAddForm(lists, QuickAddDefaults(plan = AppClock.today()), onSave = { draft, onResult ->
        writer.scope.launch {
            val ok = try {
                container.todos.create(draft)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(writer.context, (e as? LedgerException)?.message ?: "添加失败，内容已保留")
                false
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    })
}

/**
 * One todo: the title edited in place (saved when the sheet goes), its checklist ticked here,
 * and the four things done most from a home screen.
 */
@Composable
private fun TodoEditPage(id: String, dismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val writer = rememberWriter()
    val data by container.todos.data.collectAsState()
    val today = rememberToday()
    val todo = data?.todos?.firstOrNull { it.id == id }
    if (data != null && todo == null) {
        // Deleted or finished elsewhere since the widget was drawn.
        LaunchedEffect(Unit) {
            toast(context, "这条待办已不在了")
            dismiss()
        }
        return
    }
    if (todo == null) {
        Spacer(Modifier.size(160.dp))
        return
    }
    val cs = MaterialTheme.colorScheme
    val done = !TodoViews.isOpen(todo)
    var title by rememberSaveable(id) { mutableStateOf(todo.title) }
    val latestTitle by rememberUpdatedState(title)
    // The title is written when the sheet goes, however it goes.
    DisposableEffect(id) {
        onDispose {
            val t = latestTitle.trim()
            if (t.isNotEmpty() && t != todo.title && todo.kind != TodoKind.COMPLETION) writer.write { container.todos.setTitle(id, t) }
        }
    }
    // The tick plays before the sheet leaves.
    val tick = remember { Animatable(if (done) 1f else 0f) }
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp), verticalAlignment = Alignment.Top) {
        TickCircle(tick.value, alert = todo.important || TodoViews.isOverdue(todo, today), modifier = Modifier.padding(top = 3.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicTextField(
                value = title,
                onValueChange = { title = it.replace("\n", "") },
                enabled = todo.kind != TodoKind.COMPLETION,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = if (done) cs.onSurfaceVariant else cs.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                ),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { dismiss() }),
                modifier = Modifier.fillMaxWidth(),
            )
            val badge = TodoViews.trailing(todo, today)
            val meta = listOfNotNull(todo.remindTime, todo.listId?.let { data?.listById?.get(it)?.name }, if (todo.repeatRule != null) "重复" else null)
            if (badge != null || meta.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (badge != null) {
                        val c = when (badge.tone) {
                            DateBadge.Tone.ALERT -> cs.error
                            DateBadge.Tone.WARN -> AppTheme.extra.group(2)
                            DateBadge.Tone.PLAIN -> cs.onSurfaceVariant
                        }
                        Text(badge.text, style = MaterialTheme.typography.labelMedium, color = c)
                        if (meta.isNotEmpty()) Text("  ·  ", style = MaterialTheme.typography.labelMedium, color = cs.outline)
                    }
                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
            if (todo.notes.isNotBlank()) {
                Text(todo.notes, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    val items = data?.checklist?.filter { it.todoId == id }?.sortedBy { it.sortOrder }.orEmpty()
    if (items.isNotEmpty()) {
        Column(Modifier.padding(start = 52.dp, end = 18.dp, top = 8.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            for (item in items) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !done) { writer.write { container.todos.toggleChecklist(item.id) } }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val c by animateColorAsState(if (item.done) cs.primary else cs.outline, label = "item")
                    Box(Modifier.size(18.dp).clip(CircleShape).background(if (item.done) c else Color.Transparent).ring(c), contentAlignment = Alignment.Center) {
                        if (item.done) Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        item.text, style = MaterialTheme.typography.bodyMedium,
                        color = if (item.done) cs.onSurfaceVariant else cs.onSurface,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
    Spacer(Modifier.size(14.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        val green = cs.primary
        if (done) {
            ActionTile(0, Icons.Outlined.Undo, "撤销完成", green, onClick = {
                writer.write(onDone = dismiss) {
                    if (container.todos.uncomplete(id) == UndoOutcome.NEEDS_CONFIRM) throw LedgerException("这条重复待办已在别处改过，请在 App 里撤销")
                }
            })
        } else {
            ActionTile(0, Icons.Default.Check, "完成", green, filled = true, onClick = {
                scope.launch {
                    tick.animateTo(1f, Motion.bouncy())
                    writer.write(onDone = { scope.launch { delay(220); dismiss() } }) { container.todos.complete(id, todo.stateSeq) }
                }
            })
        }
        if (!done) {
            ActionTile(1, Icons.Outlined.NextPlan, "推到明天", AppTheme.extra.group(1), onClick = {
                writer.write(onDone = dismiss) { container.todos.reschedule(listOf(id), today.plusDays(1), someday = false) }
            })
            ActionTile(2, if (todo.important) Icons.Default.Flag else Icons.Outlined.Flag, if (todo.important) "取消重要" else "重要", cs.error, filled = todo.important, onClick = {
                writer.write { container.todos.setImportant(id, !todo.important) }
            })
        }
        ActionTile(3, Icons.Outlined.DeleteOutline, "删除", cs.onSurfaceVariant, onClick = {
            writer.write(onDone = dismiss) {
                container.todos.delete(id)
                toast(writer.context, "已移到回收站，30 天内可在设置里找回")
            }
        })
    }
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable {
                dismiss()
                openApp(context, MainActivity.OPEN_TODO, id)
            }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("详情与提醒", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        DoneButton("好了", enabled = title.isNotBlank(), onClick = dismiss)
    }
}

/** The todo's circle, filling as [p] goes from 0 to 1. */
@Composable
private fun TickCircle(p: Float, alert: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val ring = if (alert) cs.error else cs.outline
    Box(
        modifier.size(26.dp).graphicsLayer { val s = 1f + 0.18f * kotlin.math.sin(p * Math.PI).toFloat(); scaleX = s; scaleY = s }
            .clip(CircleShape).background(cs.primary.copy(alpha = p)).ring(if (p > 0.5f) cs.primary else ring),
        contentAlignment = Alignment.Center,
    ) {
        if (p > 0.05f) Icon(Icons.Default.Check, null, tint = cs.onPrimary.copy(alpha = p), modifier = Modifier.size((16 * p).dp))
    }
}

/** A thin round border, for the circles. */
private fun Modifier.ring(color: Color): Modifier = border(1.8.dp, color, CircleShape)

// ------------------------------------------------------------ notes

/**
 * A note written straight on the sheet, saved as it is typed, exactly as on its page in the app
 * (the same session: a conflict goes into a draft, a restore collects what is unsaved).
 */
@Composable
private fun NotePage(id: String?, dismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val writer = rememberWriter()
    val cs = MaterialTheme.colorScheme
    var session by remember { mutableStateOf<NoteSession?>(null) }
    var noteId by remember { mutableStateOf(id) }
    var pinned by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val n = id?.let { container.notes.note(it) }
        if (id != null && (n == null || n.deletedAt != null)) {
            toast(context, "这篇笔记已被删除")
            dismiss()
            return@LaunchedEffect
        }
        pinned = n?.pinned == true
        session = NoteSession(container.notes, writer.scope, onCreated = { created -> noteId = created }).also {
            it.open(n)
            launch { it.messages.collect { m -> toast(context, m) } }
        }
    }
    val s = session
    // Saved however the sheet goes: swiped away, the home button, or done.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { s?.let { writer.scope.launch { it.saveNow() } } }
    DisposableEffect(s) {
        onDispose { s?.let { writer.scope.launch { it.saveBeforeLeaving(); it.close() } } }
    }
    val savedAt = s?.savedAt?.collectAsState()?.value
    PageHeader(
        if (id == null) "新笔记" else "笔记",
        subtitle = savedAt?.let { "已保存 " + Dates.time(it) } ?: if (id == null) "写下就会自动保存" else "改动会自动保存",
    ) {
        if (noteId != null) {
            GlassIcon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (pinned) "取消置顶" else "置顶", on = pinned, onClick = {
                val target = noteId ?: return@GlassIcon
                pinned = !pinned
                writer.write { container.notes.setPinned(target, pinned) }
            })
            GlassIcon(Icons.Outlined.DeleteOutline, "删除", onClick = {
                val sess = s ?: return@GlassIcon
                writer.write(onDone = dismiss) {
                    sess.saveNow()
                    sess.noteId?.let { container.notes.delete(it) }
                    sess.close()
                    toast(writer.context, "笔记已移到回收站")
                }
            })
            GlassIcon(Icons.AutoMirrored.Outlined.OpenInNew, "在 App 里打开", onClick = {
                val target = noteId ?: return@GlassIcon
                writer.scope.launch {
                    s?.saveBeforeLeaving()
                    withContext(Dispatchers.Main) {
                        dismiss()
                        openApp(context, MainActivity.OPEN_NOTE, target)
                    }
                }
            })
        }
    }
    if (s == null) {
        Spacer(Modifier.size(220.dp))
        return
    }
    val text by s.text.collectAsState()
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // The session can change the text by itself (an append from the diary); follow it.
    LaunchedEffect(text) { if (text != field.text) field = field.copy(text = text, selection = TextRange(text.length)) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (id == null) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .glass(RoundedCornerShape(22.dp), com.assetboard.ui.components.glass, elevation = 6.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (field.text.isEmpty()) {
            Text("第一行是标题，接着写正文…", style = MaterialTheme.typography.bodyLarge, color = cs.outline)
        }
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                s.onInput(it.text)
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface, fontSize = 17.sp, lineHeight = 26.sp),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 380.dp).focusRequester(focus),
        )
    }
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        val words = field.text.count { !it.isWhitespace() }
        Text(if (words == 0) "" else "$words 字", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        DoneButton("完成", onClick = {
            writer.scope.launch {
                s.saveBeforeLeaving()
                withContext(Dispatchers.Main) { dismiss() }
            }
        })
    }
}

// ------------------------------------------------------------ countdowns

/** A countdown's form, with a live preview card on top that counts as the date changes. */
@Composable
private fun CountdownEditPage(id: String?, onDone: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val writer = rememberWriter()
    val today = rememberToday()
    var loaded by remember { mutableStateOf(id == null) }
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(today.plusDays(30).toString()) }
    var yearly by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        val c = container.countdowns.get(id)
        if (c == null) {
            toast(context, "这个倒数日已被删除")
            onDone()
            return@LaunchedEffect
        }
        title = c.title
        date = c.date
        yearly = c.yearly
        pinned = c.pinned
        loaded = true
    }
    if (!loaded) {
        Spacer(Modifier.size(300.dp))
        return
    }
    val preview = Countdowns.state(Countdown("preview", title.ifBlank { "这一天" }, date, yearly, pinned, 0, 0), today)
    PageHeader(if (id == null) "新倒数日" else "编辑倒数日")
    if (preview != null) PreviewCard(preview, today)
    Spacer(Modifier.size(14.dp))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (id == null) runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = title,
        onValueChange = { title = it.replace("\n", "").take(Countdowns.MAX_TITLE) },
        label = { Text("名称") },
        placeholder = { Text("生日、纪念日、考试、出发…") },
        singleLine = true,
        shape = FieldShape,
        colors = glassFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).focusRequester(focus),
    )
    Spacer(Modifier.size(10.dp))
    SelectField(
        label = if (yearly) "第一次的日子" else "日期",
        value = LocalDate.parse(date).let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日 " + Countdowns.dateLabel(it, it).substringAfter(' ') },
        onClick = { picking = true },
        modifier = Modifier.padding(horizontal = 18.dp),
        icon = { Icon(Icons.Outlined.CalendarMonth, null) },
        supportingText = if (yearly) "每年这一天再数一次，标题下会写第几年" else null,
    )
    Spacer(Modifier.size(10.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Toggle("每年重复", Icons.Outlined.EventRepeat, yearly, onClick = { yearly = !yearly })
        Toggle("小组件上大字显示", Icons.Outlined.PushPin, pinned, onClick = { pinned = !pinned }, color = AppTheme.extra.aurora.accent)
    }
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 18.dp, top = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (id != null) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable { confirmDelete = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.weight(1f))
        DoneButton("保存", enabled = title.isNotBlank(), onClick = {
            writer.write(onDone = onDone) { container.countdowns.save(id, title, LocalDate.parse(date), yearly, pinned) }
        })
    }
    if (picking) {
        DatePickDialog(initial = LocalDate.parse(date), maxDate = null, onDismiss = { picking = false }, onPick = { date = it.toString(); picking = false })
    }
    if (confirmDelete && id != null) {
        com.assetboard.ui.components.ConfirmDialog(
            title = "删除「$title」？",
            text = "倒数日删除后不进回收站。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                writer.write(onDone = onDone) { container.countdowns.delete(id) }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** The countdown as the widget will show it, on the app's warm glass; the number rolls when it changes. */
@Composable
private fun PreviewCard(s: CountdownState, today: LocalDate) {
    val a = AppTheme.extra.aurora
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).auroraSurface(RoundedCornerShape(26.dp), a, elevation = 12.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (s.isToday) s.countdown.title else "${s.countdown.title} ${s.lead}",
            style = MaterialTheme.typography.titleSmall, color = a.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        AnimatedContent(
            s.days,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { h -> if (up) h else -h } + fadeIn())
                    .togetherWith(slideOutVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { h -> if (up) -h else h } + fadeOut())
                    .using(SizeTransform(clip = true))
            },
            label = "days",
        ) { days ->
            Row(verticalAlignment = Alignment.Bottom) {
                if (days == 0L) {
                    Text("就是今天", style = MaterialTheme.typography.displaySmall, color = a.accent)
                } else {
                    Text(abs(days).toString(), style = MaterialTheme.typography.displayMedium, color = if (days < 0) a.inkMuted else a.figure.last())
                    Text(" 天", style = MaterialTheme.typography.titleMedium, color = a.inkMuted, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        }
        Text(Countdowns.subtitle(s, today), style = MaterialTheme.typography.labelMedium, color = a.inkMuted)
    }
}

/** Every countdown, nearest first: tap one to change it, + for a new one. */
@Composable
private fun CountdownListPage(onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val container = LocalContainer.current
    val items by remember { container.countdowns.observe() }.collectAsState(null)
    val today = rememberToday()
    val states = items?.let { Countdowns.ordered(it, today) }
    PageHeader("倒数日", subtitle = states?.let { if (it.isEmpty()) "记下重要的日子" else "共 ${it.size} 个，点一个修改" }) {
        GlassIcon(Icons.Outlined.Add, "添加", onClick = onAdd, on = true)
    }
    when {
        states == null -> Spacer(Modifier.size(200.dp))
        states.isEmpty() -> Column(
            Modifier.fillMaxWidth().padding(vertical = 36.dp).clickable(onClick = onAdd),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("⏳", fontSize = 40.sp)
            Spacer(Modifier.size(8.dp))
            Text("还没有倒数日", style = MaterialTheme.typography.titleMedium)
            Text("生日、纪念日、考试、出发，点这里添加", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(states, key = { _, s -> s.countdown.id }) { i, s -> CountdownListRow(i, s, today) { onOpen(s.countdown.id) } }
            item { Spacer(Modifier.size(12.dp)) }
        }
    }
}

@Composable
private fun CountdownListRow(index: Int, s: CountdownState, today: LocalDate, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val a = AppTheme.extra.aurora
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).appear(index).pressScale(source)
            .glass(RoundedCornerShape(20.dp), com.assetboard.ui.components.glass, elevation = 4.dp)
            .clip(RoundedCornerShape(20.dp)).clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.countdown.pinned) Icon(Icons.Filled.PushPin, "大字显示", tint = a.accent, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                Text(s.countdown.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Countdowns.subtitle(s, today), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        when {
            s.isToday -> Text("今天", style = MaterialTheme.typography.titleMedium, color = a.accent)
            s.passed -> Text("已 ${-s.days} 天", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            else -> Row(verticalAlignment = Alignment.Bottom) {
                Text("${s.days}", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = a.accent)
                Text(" 天", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

// ------------------------------------------------------------ adding widgets from the app

private data class WidgetKind(val name: String, val text: String, val receiver: Class<*>, val color: Color)

/** The four widgets, each with a button that asks the launcher to place it. */
@Composable
private fun WidgetGalleryPage() {
    val context = LocalContext.current
    val palette = AppTheme.extra
    val manager = remember { AppWidgetManager.getInstance(context) }
    val canPin = remember { manager.isRequestPinAppWidgetSupported }
    val kinds = listOf(
        WidgetKind("今天待办", "点圆圈完成，点标题修改或删除，+ 记一条", TodoWidgetReceiver::class.java, palette.group(0)),
        WidgetKind("笔记", "置顶和最近的笔记，点开就在桌面上改", NoteWidgetReceiver::class.java, palette.group(2)),
        WidgetKind("倒数日", "离重要的日子还有几天，生日每年重复", CountdownWidgetReceiver::class.java, palette.group(3)),
        WidgetKind("资产看板", "净资产、变化与走势，一键盘点、隐藏金额", NetWorthWidgetReceiver::class.java, palette.aurora.accent),
    )
    PageHeader("桌面小组件", subtitle = if (canPin) "点「添加」放到桌面，之后可以拖动边角调大小" else "长按桌面空白处 → 小组件 → 日盈")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEachIndexed { i, k ->
            Row(
                Modifier.fillMaxWidth().appear(i).glass(RoundedCornerShape(20.dp), com.assetboard.ui.components.glass, elevation = 4.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(Icons.Outlined.Widgets, k.color)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(k.name, style = MaterialTheme.typography.titleSmall)
                    Text(k.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                if (canPin) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "添加",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable { runCatching { manager.requestPinAppWidget(ComponentName(context, k.receiver), null, null) } }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
    Spacer(Modifier.size(18.dp))
}
