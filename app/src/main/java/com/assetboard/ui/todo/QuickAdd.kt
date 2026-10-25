package com.assetboard.ui.todo

import com.assetboard.domain.AppClock
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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.assetboard.domain.todo.TodoDateParser
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
 * in a row. [onSave] reports back whether the todo was written; only then is the title cleared,
 * so a failed save keeps what was typed. [inline] is the in-page version: one row of choices,
 * and Enter on an empty title calls [onEmptyDone] to close it.
 */
@Composable
fun QuickAddForm(
    lists: List<TodoList>,
    defaults: QuickAddDefaults,
    onSave: (draft: TodoDraft, onResult: (saved: Boolean) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    onEmptyDone: () -> Unit = {},
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
    var saving by remember { mutableStateOf(false) }
    // A day or time written into the title (明天下午3点): read until the user picks one by hand
    // or says 不识别, which leaves the words as they are.
    var reading by rememberSaveable { mutableStateOf(true) }
    val found = remember(title, today, reading) { if (reading) TodoDateParser.parse(title, today, java.time.LocalTime.now(AppClock.clock)) else null }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    LaunchedEffect(added) { if (added != null) { delay(1_600); added = null } }
    // What the chips show and the save uses: the words read from the title over the chips' own.
    val planDate = found?.date ?: plan?.let(LocalDate::parse)
    val remindAt = found?.time?.let(::timeLabel) ?: remind
    val isSomeday = someday && found?.date == null
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    val cs = MaterialTheme.colorScheme

    /** A chip touched by hand: the title's words stay words from here on. */
    fun byHand() {
        if (found != null) {
            plan = found.date?.toString() ?: plan
            remind = found.time?.let(::timeLabel) ?: remind
            if (found.date != null) someday = false
        }
        reading = false
    }

    fun save() {
        if (title.isBlank() || saving) return
        val submitted = title
        val name = (found?.rest ?: submitted).trim()
        saving = true
        onSave(
            TodoDraft(
                title = name,
                listId = listId,
                planDate = planDate,
                remindTime = remindAt?.let(LocalTime::parse),
                someday = isSomeday,
                deadline = deadline?.let(LocalDate::parse),
                important = important,
            ),
        ) { saved ->
            saving = false
            if (saved) {
                added = name
                // Typing went on while saving: keep the new text.
                if (title == submitted) {
                    title = ""
                    reading = true
                }
                if (remindAt != null) askForNotifications(context, askNotifications)
            }
        }
    }

    val side = if (inline) 14.dp else 20.dp
    val outer = if (inline) modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp) else modifier.fillMaxWidth().navigationBarsPadding().padding(top = 18.dp, bottom = 14.dp)
    Column(outer, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(start = side, end = if (inline) 8.dp else side), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (title.isEmpty()) Text("准备做什么？", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal), color = cs.outline)
                val mark = SpanStyle(color = cs.primary, background = cs.primary.copy(alpha = 0.12f))
                BasicTextField(
                    value = title,
                    onValueChange = { title = it.replace("\n", "") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = cs.onSurface, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(cs.primary),
                    // The words read as a day or time, marked where they stand.
                    visualTransformation = found?.let { f ->
                        VisualTransformation { text ->
                            val shown = buildAnnotatedString {
                                append(text.text)
                                f.spans.filter { it.last < text.length }.forEach { addStyle(mark, it.first, it.last + 1) }
                            }
                            TransformedText(shown, OffsetMapping.Identity)
                        }
                    } ?: VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (title.isBlank()) onEmptyDone() else save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            val ready = title.isNotBlank() && !saving
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
        AnimatedVisibility(found != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val f = found
            Row(Modifier.padding(start = side, end = if (inline) 8.dp else side - 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = cs.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "识别为 " + listOfNotNull(f?.date?.let { TodoDayLabel(it, today) }, f?.time?.let(::timeLabel)).joinToString(" ") + "，标题存为「${f?.rest.orEmpty()}」",
                    style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "不识别", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { reading = false }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        AnimatedVisibility(added != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.padding(horizontal = side), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, null, tint = cs.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("已添加「${added.orEmpty()}」，可以接着写下一条", style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val dates: @Composable () -> Unit = {
            QuickDate.entries.forEach { q ->
                val d = q.resolve(today)
                Choice(q.label, selected = !isSomeday && planDate == d) {
                    byHand()
                    if (!someday && planDate == d) plan = null else { plan = d.toString(); someday = false }
                }
            }
            val custom = planDate != null && QuickDate.entries.none { it.resolve(today) == planDate }
            Choice(if (custom) TodoDayLabel(planDate!!, today) else "选日期", selected = custom, icon = Icons.Outlined.CalendarMonth) { byHand(); dialog = "plan" }
            Choice("以后再说", selected = isSomeday, icon = Icons.Outlined.Archive) {
                byHand()
                someday = !someday
                if (someday) { plan = null; remind = null }
            }
        }
        val more: @Composable () -> Unit = {
            Choice(deadline?.let { "截止 " + TodoDayLabel(LocalDate.parse(it), today) } ?: "截止", selected = deadline != null, icon = Icons.Outlined.Event) {
                if (deadline != null) deadline = null else dialog = "deadline"
            }
            // The time of day, which is also when the reminder goes off.
            Choice(remindAt ?: "几点", selected = remindAt != null, icon = Icons.Outlined.Schedule) {
                byHand()
                if (remind != null) remind = null else dialog = "remind"
            }
            Choice(lists.firstOrNull { it.id == listId }?.name ?: "清单", selected = listId != null, icon = Icons.Outlined.FormatListBulleted) { dialog = "list" }
            Choice("重要", selected = important, icon = Icons.Default.Flag, tint = cs.error) { important = !important }
        }
        val rows = if (inline) listOf<@Composable () -> Unit>({ dates(); more() }) else listOf(dates, more)
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = side - 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { row() }
        }
    }

    when (dialog) {
        "plan" -> DatePickDialog(initial = planDate ?: today, maxDate = null, onDismiss = { dialog = null }, onPick = { plan = it.toString(); someday = false; dialog = null })
        "deadline" -> DatePickDialog(initial = today, maxDate = null, onDismiss = { dialog = null }, onPick = { deadline = it.toString(); dialog = null })
        "remind" -> TimePickDialog(
            initial = null,
            title = "几点",
            onDismiss = { dialog = null },
            onPick = {
                remind = timeLabel(it)
                // A time hangs on the plan date; none yet means today.
                if (plan == null) { plan = today.toString(); someday = false }
                dialog = null
                askForNotifications(context, askNotifications)
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

/** The first time of day set is the moment to ask for the notification permission. */
private fun askForNotifications(context: android.content.Context, launcher: androidx.activity.result.ActivityResultLauncher<String>) {
    if (android.os.Build.VERSION.SDK_INT >= 33 && !com.assetboard.reminder.ReminderScheduler.hasNotificationPermission(context)) {
        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

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
