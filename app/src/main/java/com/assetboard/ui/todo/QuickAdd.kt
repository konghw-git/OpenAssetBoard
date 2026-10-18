package com.assetboard.ui.todo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetboard.data.TodoDraft
import com.assetboard.data.TodoList
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.ListPickerDialog
import com.assetboard.ui.theme.AppTheme
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

/** Where quick add starts: a list view preselects its list, Today preselects today. */
data class QuickAddDefaults(val listId: String? = null, val plan: LocalDate? = null, val someday: Boolean = false)

/**
 * The quick-add form: title focused on open, date shortcuts, then deadline, reminder, list and
 * the important flag. Enter saves and clears the title, keeping the choices, for entering several
 * in a row.
 */
@Composable
fun QuickAddForm(
    lists: List<TodoList>,
    defaults: QuickAddDefaults,
    onSave: (TodoDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = rememberToday()
    var title by rememberSaveable { mutableStateOf("") }
    var plan by rememberSaveable { mutableStateOf(defaults.plan?.toString()) }
    var someday by rememberSaveable { mutableStateOf(defaults.someday) }
    var deadline by rememberSaveable { mutableStateOf<String?>(null) }
    var remind by rememberSaveable { mutableStateOf<String?>(null) }
    var listId by rememberSaveable { mutableStateOf(defaults.listId) }
    var important by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    LaunchedEffect(added) { if (added != null) { delay(1_600); added = null } }
    val planDate = plan?.let(LocalDate::parse)
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    val cs = MaterialTheme.colorScheme

    fun save() {
        if (title.isBlank()) return
        onSave(
            TodoDraft(
                title = title.trim(),
                listId = listId,
                planDate = planDate,
                remindTime = remind?.let(LocalTime::parse),
                someday = someday,
                deadline = deadline?.let(LocalDate::parse),
                important = important,
            ),
        )
        added = title.trim()
        title = ""
    }

    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(top = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (title.isEmpty()) Text("准备做什么？", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal), color = cs.outline)
                BasicTextField(
                    value = title,
                    onValueChange = { title = it.replace("\n", "") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = cs.onSurface, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            val ready = title.isNotBlank()
            val scale by animateFloatAsState(if (ready) 1f else 0.86f, spring(dampingRatio = 0.5f), label = "send")
            Box(
                Modifier
                    .padding(start = 10.dp)
                    .size(42.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(if (ready) Brush.linearGradient(listOf(AppTheme.extra.heroEnd, AppTheme.extra.heroStart)) else SolidColor(cs.surfaceContainerHighest))
                    .clickable(enabled = ready, onClick = ::save),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ArrowUpward, "保存", tint = if (ready) AppTheme.extra.onHero else cs.outline, modifier = Modifier.size(22.dp))
            }
        }
        AnimatedVisibility(added != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, null, tint = cs.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("已添加「${added.orEmpty()}」，可以接着写下一条", style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuickDate.entries.forEach { q ->
                val d = q.resolve(today)
                Choice(q.label, selected = !someday && planDate == d) {
                    if (!someday && planDate == d) plan = null else { plan = d.toString(); someday = false }
                }
            }
            val custom = planDate != null && QuickDate.entries.none { it.resolve(today) == planDate }
            Choice(if (custom) TodoDayLabel(planDate!!, today) else "选日期", selected = custom, icon = Icons.Outlined.CalendarMonth) { dialog = "plan" }
            Choice("以后再说", selected = someday, icon = Icons.Outlined.Archive) {
                someday = !someday
                if (someday) { plan = null; remind = null }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Choice(deadline?.let { "截止 " + TodoDayLabel(LocalDate.parse(it), today) } ?: "截止", selected = deadline != null, icon = Icons.Outlined.Event) {
                if (deadline != null) deadline = null else dialog = "deadline"
            }
            Choice(remind?.let { "提醒 $it" } ?: "提醒", selected = remind != null, icon = Icons.Outlined.Alarm) {
                if (remind != null) remind = null else dialog = "remind"
            }
            Choice(lists.firstOrNull { it.id == listId }?.name ?: "清单", selected = listId != null, icon = Icons.Outlined.FormatListBulleted) { dialog = "list" }
            Choice("重要", selected = important, icon = Icons.Default.Flag, tint = cs.error) { important = !important }
        }
    }

    when (dialog) {
        "plan" -> DatePickDialog(initial = planDate ?: today, maxDate = null, onDismiss = { dialog = null }, onPick = { plan = it.toString(); someday = false; dialog = null })
        "deadline" -> DatePickDialog(initial = today, maxDate = null, onDismiss = { dialog = null }, onPick = { deadline = it.toString(); dialog = null })
        "remind" -> TimePickDialog(
            initial = null,
            onDismiss = { dialog = null },
            onPick = {
                remind = it.toString()
                // A reminder hangs on the plan date; none yet means today.
                if (plan == null) { plan = today.toString(); someday = false }
                dialog = null
                if (android.os.Build.VERSION.SDK_INT >= 33 && !com.assetboard.reminder.ReminderScheduler.hasNotificationPermission(context)) {
                    askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        "list" -> ListPickerDialog(
            title = "放进清单",
            items = listOf<TodoList?>(null) + lists,
            label = { it?.name ?: "收件箱（不放清单）" },
            onPick = { listId = it?.id; dialog = null },
            onDismiss = { dialog = null },
        )
    }
}

fun TodoDayLabel(d: LocalDate, today: LocalDate): String = com.assetboard.domain.todo.TodoViews.shortDate(d, today)

/** A pill choice: tonal when off, filled when on. */
@Composable
private fun Choice(label: String, selected: Boolean, icon: ImageVector? = null, tint: Color? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accent = tint ?: cs.primary
    val bg by animateColorAsState(if (selected) accent else cs.surfaceContainerHigh, tween(180), label = "choice")
    val fg = if (selected) (if (tint != null) cs.onError else cs.onPrimary) else cs.onSurface
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) fg else (tint ?: cs.onSurfaceVariant), modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}
