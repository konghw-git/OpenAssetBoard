package com.assetboard.ui.todo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.assetboard.data.ChecklistItem
import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.domain.todo.DateBadge
import com.assetboard.domain.todo.Recurrence
import com.assetboard.domain.todo.TodoViews
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.SwipeActionsBox
import com.assetboard.ui.components.SwipeGroup
import com.assetboard.ui.components.glass
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Today's date, refreshed when the screen resumes and at midnight. */
@Composable
fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                today = LocalDate.now()
                val untilMidnight = ChronoUnit.MILLIS.between(LocalDateTime.now(), today.plusDays(1).atStartOfDay())
                delay(untilMidnight.coerceAtLeast(1_000) + 500)
            }
        }
    }
    return today
}

/** The quick date choices shared by quick add, the swipe sheet and the detail page. */
enum class QuickDate(val label: String) {
    TODAY("今天"), TOMORROW("明天"), WEEKEND("本周末"), NEXT_MONDAY("下周一");

    fun resolve(today: LocalDate): LocalDate = when (this) {
        TODAY -> today
        TOMORROW -> today.plusDays(1)
        WEEKEND -> if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) today else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        NEXT_MONDAY -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    }
}

/** "10月21日 周三", or "明天 · 10月21日 周三" when near. */
fun dayTitle(d: LocalDate, today: LocalDate): String {
    val base = "${d.monthValue}月${d.dayOfMonth}日 ${Recurrence.weekName(d.dayOfWeek)}"
    val year = if (d.year != today.year) "${d.year}年" else ""
    return when (ChronoUnit.DAYS.between(today, d)) {
        0L -> "今天 · $base"
        1L -> "明天 · $base"
        else -> year + base
    }
}

@Composable
fun badgeColor(tone: DateBadge.Tone): Color = when (tone) {
    DateBadge.Tone.ALERT -> MaterialTheme.colorScheme.error
    DateBadge.Tone.WARN -> Color(0xFFC77700)
    DateBadge.Tone.PLAIN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The round check box. A repeat source shows a double ring; checking springs the fill in. */
@Composable
fun CheckCircle(checked: Boolean, important: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, repeat: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (checked) 1f else 0.92f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "check")
    val fill by animateColorAsState(if (checked) cs.primary else Color.Transparent, tween(220), label = "fill")
    val ring = if (important && !checked) cs.error else cs.outline
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(fill)
                .border(if (checked) 0.dp else 1.8.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(checked, enter = scaleIn(spring(dampingRatio = 0.4f)) + fadeIn(), exit = fadeOut()) {
                Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(15.dp))
            }
            if (!checked && repeat) Box(Modifier.size(12.dp).border(1.2.dp, ring.copy(alpha = 0.6f), CircleShape))
        }
    }
}

data class RowExtras(
    val listName: String? = null,
    val listColor: Color? = null,
    val checklistDone: Int = 0,
    val checklistTotal: Int = 0,
)

val TodoCardShape = RoundedCornerShape(18.dp)

/** One todo as a card: check box, title, a line of small chips and the date badge. */
@Composable
fun TodoRow(
    todo: Todo,
    done: Boolean,
    today: LocalDate,
    extras: RowExtras,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    inUpcoming: Boolean = false,
    /** Shown under the title as small check points while the todo is open; empty hides them. */
    checklist: List<ChecklistItem> = emptyList(),
    /** Null makes the check points read-only (selecting, sorting). */
    onToggleItem: ((String) -> Unit)? = null,
    /** A check point swiped left turns to dust on its own and goes; null keeps them in place. */
    onDeleteItem: ((String) -> Unit)? = null,
    swipe: SwipeGroup? = null,
    /** Picked up and being dragged. */
    lifted: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val badge = if (done) null else TodoViews.trailing(todo, today, inUpcoming)
    val points = if (done) emptyList() else checklist
    val content by animateFloatAsState(if (done) 0.55f else 1f, tween(260), label = "done")
    val tint by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.16f) else Color.Transparent, tween(200), label = "tint")
    val lift by animateFloatAsState(if (lifted) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "lift")
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = 1f + 0.03f * lift; scaleY = scaleX }
            .glass(TodoCardShape, glass, tint, elevation = if (done) 0.dp else 10.dp + 18.dp * lift)
            .clip(TodoCardShape)
            .then(if (selected) Modifier.border(1.5.dp, cs.primary, TodoCardShape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 60.dp)
            .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = if (points.isEmpty()) 4.dp else 8.dp),
        // With check points under the title, the box and badge stay with the title line.
        verticalAlignment = if (points.isEmpty()) Alignment.CenterVertically else Alignment.Top,
    ) {
        CheckCircle(done, todo.important, onToggle, repeat = todo.kind == TodoKind.REPEAT_SOURCE)
        Column(Modifier.weight(1f).padding(vertical = 8.dp).graphicsLayer { alpha = content }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (todo.important && !done) {
                    Icon(Icons.Default.Flag, null, tint = cs.error, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    todo.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (done) cs.onSurfaceVariant else cs.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // The checklist's progress rides on the title line, where it costs no room.
                if (extras.checklistTotal > 0) {
                    Spacer(Modifier.width(8.dp))
                    ChecklistProgress(extras.checklistDone, extras.checklistTotal)
                }
            }
            val hasMeta = extras.listName != null || todo.notes.isNotBlank() ||
                todo.kind == TodoKind.REPEAT_SOURCE || (todo.remindTime != null && !done)
            if (hasMeta) {
                Row(
                    Modifier.padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (todo.remindTime != null && !done) {
                        MetaChip(if (todo.strongRemind) Icons.Outlined.Alarm else Icons.Outlined.Notifications, todo.remindTime, if (todo.strongRemind) cs.error else cs.primary)
                    }
                    if (todo.kind == TodoKind.REPEAT_SOURCE) MetaChip(Icons.Outlined.Repeat, null, cs.tertiary)
                    if (todo.notes.isNotBlank()) Icon(Icons.Outlined.StickyNote2, null, tint = cs.outline, modifier = Modifier.size(14.dp))
                    extras.listName?.let { name ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(extras.listColor ?: cs.outline))
                            Spacer(Modifier.width(4.dp))
                            Text(name, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (points.isNotEmpty()) InlineChecklist(points, onToggleItem, onDeleteItem, swipe)
        }
        if (badge != null) {
            val c = badgeColor(badge.tone)
            Text(
                badge.text,
                style = MaterialTheme.typography.labelMedium,
                color = c,
                fontWeight = if (badge.tone == DateBadge.Tone.ALERT) FontWeight.SemiBold else null,
                modifier = Modifier
                    .padding(start = 8.dp, top = if (points.isEmpty()) 0.dp else 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(c.copy(alpha = 0.10f))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        trailing?.invoke()
    }
}

/** A small ring filling as the checklist gets done, and "done/total" beside it. */
@Composable
private fun ChecklistProgress(done: Int, total: Int) {
    val cs = MaterialTheme.colorScheme
    val all = done == total
    val shown by animateFloatAsState(done.toFloat() / total, tween(400), label = "checklist")
    val color = if (all) cs.primary else cs.onSurfaceVariant
    val track = cs.outlineVariant
    val fill = cs.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(14.dp).drawBehind {
                val w = 2.dp.toPx()
                val arc = Size(size.width - w, size.height - w)
                drawArc(track, 0f, 360f, false, Offset(w / 2, w / 2), arc, style = Stroke(w))
                if (shown > 0f) drawArc(fill, -90f, 360f * shown, false, Offset(w / 2, w / 2), arc, style = Stroke(w, cap = StrokeCap.Round))
            },
        )
        Spacer(Modifier.width(4.dp))
        Text("$done/$total", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = if (all) FontWeight.SemiBold else null)
    }
}

/**
 * The todo's checklist under its title, every item on a strip of its own. Tapping one ticks it
 * in place; swiping it left deletes just that item, in dust of its own (a right swipe still
 * goes to the card). Adding and editing stay in the detail page.
 */
@Composable
private fun InlineChecklist(items: List<ChecklistItem>, onToggle: ((String) -> Unit)?, onDelete: ((String) -> Unit)?, swipe: SwipeGroup?) {
    Column(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            key(item.id) {
                if (onDelete == null) {
                    CheckPoint(item, onToggle)
                } else {
                    SwipeActionsBox(checklistKey(item.id), swipe, CheckPointShape, onDelete = { onDelete(item.id) }, compact = true) {
                        CheckPoint(item, onToggle)
                    }
                }
            }
        }
    }
}

/** The dust and swipe key of a checklist item, in the list and in the detail. */
internal fun checklistKey(id: String) = "check:$id"

private val CheckPointShape = RoundedCornerShape(10.dp)

@Composable
private fun CheckPoint(item: ChecklistItem, onToggle: ((String) -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (item.done) 1f else 0.9f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "point")
    val fill by animateColorAsState(if (item.done) cs.primary else Color.Transparent, tween(200), label = "pointFill")
    val text by animateColorAsState(if (item.done) cs.outline else cs.onSurface.copy(alpha = 0.88f), tween(200), label = "pointText")
    // A strip a shade off the card: light on glass, dimmer once done.
    val strip by animateColorAsState(cs.onSurface.copy(alpha = if (item.done) 0.03f else 0.055f), tween(200), label = "strip")
    val shape = CheckPointShape
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(strip)
            .then(if (onToggle != null) Modifier.clickable { onToggle(item.id) } else Modifier)
            .heightIn(min = 36.dp)
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(17.dp)
                .scale(scale)
                .clip(RoundedCornerShape(5.dp))
                .background(fill)
                .border(if (item.done) 0.dp else 1.4.dp, cs.outline, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedVisibility(item.done, enter = scaleIn(spring(dampingRatio = 0.4f)) + fadeIn(), exit = fadeOut()) {
                Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(12.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = text,
            textDecoration = if (item.done) TextDecoration.LineThrough else null,
        )
    }
}

/** A tinted icon with optional text: reminder time, repeat. */
@Composable
private fun MetaChip(icon: ImageVector, text: String?, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = if (text == null) 4.dp else 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        if (text != null) {
            Spacer(Modifier.width(3.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
fun TimePickDialog(initial: LocalTime?, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit, onClear: (() -> Unit)? = null) {
    val t = initial ?: LocalTime.of(9, 0)
    val state = rememberTimePickerState(t.hour, t.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提醒时刻") },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("确定") } },
        dismissButton = {
            Row {
                if (onClear != null) TextButton(onClick = onClear) { Text("不提醒", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * The reschedule sheet content: quick dates, a date picker, someday, and clearing the date.
 * [allowSomeday] is false for repeat sources.
 */
@Composable
fun RescheduleDialog(
    today: LocalDate,
    allowSomeday: Boolean,
    allowClear: Boolean,
    onPick: (date: LocalDate?, someday: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改到哪天") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                QuickDate.entries.forEach { q ->
                    FilterChip(selected = false, onClick = { onPick(q.resolve(today), false) }, label = { Text(q.label) })
                }
                FilterChip(selected = false, onClick = { picking = true }, label = { Text("选日期…") })
                if (allowSomeday) FilterChip(selected = false, onClick = { onPick(null, true) }, label = { Text("以后再说") })
                if (allowClear) FilterChip(selected = false, onClick = { onPick(null, false) }, label = { Text("清除日期") })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (picking) {
        DatePickDialog(initial = today, maxDate = null, onDismiss = { picking = false }, onPick = { picking = false; onPick(it, false) })
    }
}

/** Small pill used for counts on entries. */
@Composable
fun CountText(n: Int, alert: Boolean = false) {
    if (n <= 0) return
    Text(
        n.toString(),
        style = MaterialTheme.typography.labelLarge,
        color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
