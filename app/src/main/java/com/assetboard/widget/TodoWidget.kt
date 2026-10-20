package com.assetboard.widget

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.assetboard.AssetBoardApp
import com.assetboard.MainActivity
import com.assetboard.QuickAddActivity
import com.assetboard.data.Epoch
import com.assetboard.domain.LedgerException
import com.assetboard.domain.todo.TodoViews
import com.assetboard.domain.todo.ViewRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Today view on the home screen: tap a circle to complete, the title to open, + to add. */
class TodoWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as AssetBoardApp).container
        val data = runCatching { container.todos.loadOnce() }.getOrNull()
        val today = TodoViews.localDate(System.currentTimeMillis())
        val rows = data?.let { TodoViews.today(it.todos, today) }.orEmpty()
        val open = rows.count { !it.done }
        // The token's epoch is bound here, when the widget is drawn.
        val epoch = container.gate.epochNow()
        val listNames = data?.lists?.associate { it.id to it.name }.orEmpty()
        provideContent {
            GlanceTheme { Content(rows, open, epoch, listNames) }
        }
    }

    companion object {
        val IdKey = ActionParameters.Key<String>("id")
        val SeqKey = ActionParameters.Key<Long>("seq")
        val EpochKey = ActionParameters.Key<Long>("epoch")
        val OpenKey = ActionParameters.Key<String>(MainActivity.EXTRA_OPEN)
        val TodoIdKey = ActionParameters.Key<String>(MainActivity.EXTRA_ID)

        suspend fun refresh(context: Context) {
            runCatching { TodoWidget().updateAll(context) }
        }
    }
}

@Composable
private fun Content(rows: List<ViewRow>, open: Int, epoch: Long, listNames: Map<String, String>) {
    val c = GlanceTheme.colors
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(c.widgetBackground).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "今天  $open",
                style = TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight().clickable(
                    actionStartActivity<MainActivity>(actionParametersOf(TodoWidget.OpenKey to MainActivity.OPEN_TODO_TODAY)),
                ),
            )
            Box(
                GlanceModifier.size(36.dp).cornerRadius(18.dp).background(c.primary).clickable(actionStartActivity<QuickAddActivity>()),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = TextStyle(color = c.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        if (rows.isEmpty()) {
            Text("今天没有待办", style = TextStyle(color = c.onSurfaceVariant, fontSize = 13.sp), modifier = GlanceModifier.padding(vertical = 8.dp))
        } else {
            LazyColumn {
                items(rows, itemId = { it.todo.id.hashCode().toLong() }) { row -> RowItem(row, epoch, listNames) }
            }
        }
    }
}

@Composable
private fun RowItem(row: ViewRow, epoch: Long, listNames: Map<String, String>) {
    val c = GlanceTheme.colors
    val t = row.todo
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        val circle = GlanceModifier.size(22.dp).cornerRadius(11.dp).background(if (row.done) c.primary else c.outline)
        Box(
            if (row.done) circle else circle.clickable(
                actionRunCallback<CompleteTodoAction>(
                    actionParametersOf(TodoWidget.IdKey to t.id, TodoWidget.SeqKey to t.stateSeq, TodoWidget.EpochKey to epoch),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (row.done) {
                Text("✓", style = TextStyle(color = c.onPrimary, fontSize = 13.sp))
            } else {
                Box(GlanceModifier.size(18.dp).cornerRadius(9.dp).background(c.widgetBackground)) {}
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        Column(
            GlanceModifier.defaultWeight().clickable(
                actionStartActivity<MainActivity>(actionParametersOf(TodoWidget.OpenKey to MainActivity.OPEN_TODO, TodoWidget.TodoIdKey to t.id)),
            ),
        ) {
            Text(
                t.title,
                maxLines = 1,
                style = TextStyle(
                    color = if (row.done) c.onSurfaceVariant else c.onSurface,
                    fontSize = 14.sp,
                    textDecoration = if (row.done) TextDecoration.LineThrough else null,
                    fontWeight = if (t.important && !row.done) FontWeight.Bold else null,
                ),
            )
            val sub = listOfNotNull(
                if (row.overdue) "已过期" else null,
                t.listId?.let { listNames[it] },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, maxLines = 1, style = TextStyle(color = if (row.overdue) c.error else c.onSurfaceVariant, fontSize = 11.sp))
        }
    }
}

/** The circle: completes with the token the widget was drawn with. */
class CompleteTodoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = (context.applicationContext as AssetBoardApp).container
        val id = parameters[TodoWidget.IdKey] ?: return
        val seq = parameters[TodoWidget.SeqKey] ?: return
        val epoch = parameters[TodoWidget.EpochKey] ?: return
        try {
            withContext(Epoch(epoch)) { container.todos.complete(id, seq) }
        } catch (e: LedgerException) {
            withContext(Dispatchers.Main) { Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show() }
        }
        TodoWidget.refresh(context)
    }
}

class TodoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodoWidget()
}
