package com.assetboard.widget

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import com.assetboard.MainActivity
import com.assetboard.R
import com.assetboard.WidgetSheetActivity
import com.assetboard.data.Epoch
import com.assetboard.data.TodoData
import com.assetboard.data.UndoOutcome
import com.assetboard.domain.AppClock
import com.assetboard.domain.Countdowns
import com.assetboard.domain.LedgerException
import com.assetboard.domain.todo.DateBadge
import com.assetboard.domain.todo.TodoViews
import com.assetboard.data.Profile
import com.assetboard.domain.todo.ViewRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Today on the home screen. The circle completes (or, on a row finished today, reopens) right
 * there; the title opens a sheet over the home screen to rename, flag, move to tomorrow or
 * delete; + opens quick add. The rows follow the database live while the widget is on screen.
 */
class TodoWidget : GlanceAppWidget() {
    // Its layout only tells compact from not: two sizes, each with the whole list in the parcel.
    override val sizeMode = responsive(110 to 110, 220 to 110)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.container
        val initial = runCatching { container.todos.loadOnce() }.getOrNull()
        provideContent {
            val live by container.todos.data.collectAsState()
            val pending by PendingTicks.shown.collectAsState()
            // The token's profile and epoch are the ones current when the rows were drawn.
            TodoContent(live ?: initial, pending, container.profile.id, container.gate.epochNow())
        }
    }

    companion object {
        val IdKey = ActionParameters.Key<String>("id")
        val SeqKey = ActionParameters.Key<Long>("seq")
        val EpochKey = ActionParameters.Key<Long>("epoch")
        val ProfileKey = ActionParameters.Key<String>("profile")
        val DoneKey = ActionParameters.Key<Boolean>("done")

        suspend fun refresh(context: Context) {
            runCatching { TodoWidget().updateAll(context) }
        }
    }
}

@Composable
private fun TodoContent(data: TodoData?, pending: Map<String, Boolean>, profile: String, epoch: Long) {
    val context = LocalContext.current
    val size = LocalSize.current
    val compact = size.width < 220.dp
    val today = TodoViews.localDate(AppClock.millis())
    val rows = data?.let { TodoViews.today(it.todos, today) }.orEmpty()
        .map { r -> pending[r.todo.id]?.let { r.copy(done = it) } ?: r }
    val done = rows.count { it.done }
    val lists = data?.lists?.associate { it.id to it.name }.orEmpty()

    WidgetFrame(padding = if (compact) 12.dp else 14.dp) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(appAction(context, MainActivity.OPEN_TODO_TODAY))) {
                Row(verticalAlignment = Alignment.Bottom) {
                    WText("今天", if (compact) 16.sp else 18.sp, WColors.ink, bold = true)
                    if (rows.isNotEmpty()) {
                        Spacer(GlanceModifier.width(6.dp))
                        WText("${rows.size - done}", if (compact) 16.sp else 18.sp, WColors.primary, bold = true)
                    }
                }
                val sub = when {
                    rows.isEmpty() -> Countdowns.dateLabel(today, today)
                    done == rows.size -> "全部完成，干得漂亮"
                    compact -> "已完成 $done/${rows.size}"
                    else -> Countdowns.dateLabel(today, today) + " · 已完成 $done/${rows.size}"
                }
                WText(sub, 11.sp, WColors.muted)
            }
            RoundButton(R.drawable.ic_w_add, "记一条待办", R.drawable.widget_btn_green, sheetAction(context, WidgetSheetActivity.TODO_ADD))
        }
        if (rows.isNotEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            LinearProgressIndicator(
                progress = done.toFloat() / rows.size,
                modifier = GlanceModifier.fillMaxWidth().height(3.dp).cornerRadius(2.dp),
                color = WColors.primary,
                backgroundColor = WColors.primarySoft,
            )
            Spacer(GlanceModifier.height(4.dp))
            LazyColumn(GlanceModifier.fillMaxSize()) {
                // Every row travels to the launcher in one parcel; a day of more than this is a list for the app.
                items(rows.take(40), itemId = { it.todo.id.hashCode().toLong() and 0x7FFFFFFFL }) { row ->
                    TodoRow(row, today, lists, profile, epoch, compact)
                }
            }
        } else {
            EmptyState(
                "今天没有待办",
                "点这里或右上角 + 记一条",
                GlanceModifier.fillMaxSize(),
                onClick = sheetAction(context, WidgetSheetActivity.TODO_ADD),
            )
        }
    }
}

@Composable
private fun TodoRow(row: ViewRow, today: LocalDate, lists: Map<String, String>, profile: String, epoch: Long, compact: Boolean) {
    val context = LocalContext.current
    val t = row.todo
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        // A target bigger than the circle: thumbs on a home screen are not precise.
        Box(
            GlanceModifier.size(34.dp).cornerRadius(17.dp).clickable(
                actionRunCallback<ToggleTodoAction>(
                    actionParametersOf(
                        TodoWidget.IdKey to t.id,
                        TodoWidget.SeqKey to t.stateSeq,
                        TodoWidget.EpochKey to epoch,
                        TodoWidget.ProfileKey to profile,
                        TodoWidget.DoneKey to row.done,
                    ),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            val circle = when {
                row.done -> R.drawable.ic_w_done
                t.important || row.overdue -> R.drawable.ic_w_ring_alert
                else -> R.drawable.ic_w_ring
            }
            androidx.glance.Image(ImageProvider(circle), if (row.done) "已完成，点一下撤销" else "完成", GlanceModifier.size(22.dp))
        }
        Spacer(GlanceModifier.width(4.dp))
        Column(
            GlanceModifier.defaultWeight().clickable(sheetAction(context, WidgetSheetActivity.TODO_EDIT, t.id)),
        ) {
            WText(t.title, 14.sp, if (row.done) WColors.faint else WColors.ink, medium = !row.done, strike = row.done)
            if (!row.done && !compact) {
                val badge = TodoViews.trailing(t, today)
                val meta = listOfNotNull(t.remindTime, t.listId?.let { lists[it] }).joinToString(" · ")
                if (badge != null || meta.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (badge != null) {
                            WText(
                                badge.text, 11.sp,
                                when (badge.tone) {
                                    DateBadge.Tone.ALERT -> WColors.alert
                                    DateBadge.Tone.WARN -> WColors.warn
                                    DateBadge.Tone.PLAIN -> WColors.muted
                                },
                                medium = badge.tone != DateBadge.Tone.PLAIN,
                            )
                            if (meta.isNotEmpty()) WText("  ·  ", 11.sp, WColors.faint)
                        }
                        if (meta.isNotEmpty()) WText(meta, 11.sp, WColors.muted)
                    }
                }
            }
        }
        if (t.important && !row.done) WIcon(R.drawable.ic_w_flag, WColors.alert, 14.dp, GlanceModifier.padding(start = 4.dp))
    }
}

/**
 * The circle. Drawn in its new state at once ([PendingTicks]), then written with the token the
 * row was drawn with: a row that changed since, or data restored since, is refused rather than
 * acted on blind.
 */
class ToggleTodoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = context.container
        val id = parameters[TodoWidget.IdKey] ?: return
        val seq = parameters[TodoWidget.SeqKey] ?: return
        val epoch = parameters[TodoWidget.EpochKey] ?: return
        // A widget placed by a build from before profiles has no profile key; it belongs to the
        // original local database, which is still the same profile.
        val profile = parameters[TodoWidget.ProfileKey] ?: Profile.LOCAL_ID
        if (profile != container.profile.id) return
        val wasDone = parameters[TodoWidget.DoneKey] ?: false
        PendingTicks.put(id, !wasDone)
        runCatching { TodoWidget().update(context, glanceId) }
        val problem = try {
            withContext(Epoch(epoch)) {
                if (wasDone) {
                    if (container.todos.uncomplete(id) == UndoOutcome.NEEDS_CONFIRM) "这条重复待办已在别处改过，请在 App 里撤销" else null
                } else {
                    container.todos.complete(id, seq)
                    null
                }
            }
        } catch (e: LedgerException) {
            e.message
        } finally {
            PendingTicks.remove(id)
        }
        if (problem != null) withContext(Dispatchers.Main) { Toast.makeText(context, problem, Toast.LENGTH_SHORT).show() }
        runCatching { TodoWidget().update(context, glanceId) }
    }
}

class TodoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodoWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetDay.schedule(context)
    }
}
