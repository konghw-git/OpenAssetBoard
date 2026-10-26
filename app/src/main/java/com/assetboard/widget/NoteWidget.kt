package com.assetboard.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.LazyVerticalGrid
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
import androidx.glance.layout.width
import com.assetboard.MainActivity
import com.assetboard.R
import com.assetboard.WidgetSheetActivity
import com.assetboard.data.Note
import com.assetboard.data.NoteText
import com.assetboard.domain.Dates
import kotlinx.coroutines.flow.first

/**
 * The notes, pinned first, in the order the app shows them. Small, it is one sticky note; wide,
 * a list of panes, or two columns of them. Tapping one opens it for editing over the home screen.
 */
class NoteWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val notes = context.container.notes
        val initial = runCatching { notes.observeNotes().first() }.getOrDefault(emptyList())
        provideContent {
            val live by remember { notes.observeNotes() }.collectAsState(initial)
            NoteContent(live)
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { NoteWidget().updateAll(context) }
        }
    }
}

@Composable
private fun NoteContent(notes: List<Note>) {
    val context = LocalContext.current
    val size = LocalSize.current
    val small = size.width < 220.dp || size.height < 130.dp
    val add = sheetAction(context, WidgetSheetActivity.NOTE_NEW)

    if (small) {
        // One sticky note: the first in the list, as much of it as fits.
        val n = notes.firstOrNull()
        WidgetFrame(padding = 12.dp, onClick = n?.let { sheetAction(context, WidgetSheetActivity.NOTE_EDIT, it.id) } ?: add) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WText(if (n?.pinned == true) "置顶笔记" else "笔记", 11.sp, WColors.brass, bold = true, modifier = GlanceModifier.defaultWeight())
                RoundButton(R.drawable.ic_w_add, "写笔记", R.drawable.widget_btn_brass, add, size = 26.dp, iconSize = 15.dp)
            }
            Spacer(GlanceModifier.height(4.dp))
            if (n == null) {
                EmptyState("还没有笔记", "点这里写第一篇", GlanceModifier.fillMaxSize())
            } else {
                WText(NoteText.title(n.body), 15.sp, WColors.ink, bold = true, maxLines = 2)
                Spacer(GlanceModifier.height(2.dp))
                WText(NoteText.preview(n.body), 12.sp, WColors.muted, maxLines = 6, modifier = GlanceModifier.defaultWeight())
                WText(Dates.relative(n.updatedAt), 10.sp, WColors.faint)
            }
        }
        return
    }

    WidgetFrame {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    WText("笔记", 18.sp, WColors.ink, bold = true)
                    if (notes.isNotEmpty()) {
                        Spacer(GlanceModifier.width(6.dp))
                        WText("${notes.size}", 18.sp, WColors.brass, bold = true)
                    }
                }
                WText(if (notes.any { it.pinned }) "置顶的在前，点开就能改" else "点开就能改，不用进 App", 11.sp, WColors.muted)
            }
            RoundButton(R.drawable.ic_w_add, "写笔记", R.drawable.widget_btn_brass, add)
        }
        Spacer(GlanceModifier.height(8.dp))
        if (notes.isEmpty()) {
            EmptyState("还没有笔记", "点这里或右上角 + 写一篇", GlanceModifier.fillMaxSize(), onClick = add)
        } else if (size.width >= 380.dp) {
            LazyVerticalGrid(GridCells.Fixed(2), GlanceModifier.fillMaxSize()) {
                items(notes.take(MAX_SHOWN), itemId = { it.id.hashCode().toLong() and 0x7FFFFFFFL }) { n ->
                    Box(GlanceModifier.padding(end = 3.dp, start = 3.dp, bottom = 6.dp)) { NoteTile(n, lines = 3) }
                }
                if (notes.size > MAX_SHOWN) item { MoreNotes(notes.size) }
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(notes.take(MAX_SHOWN), itemId = { it.id.hashCode().toLong() and 0x7FFFFFFFL }) { n ->
                    Box(GlanceModifier.padding(bottom = 6.dp)) { NoteTile(n, lines = 2) }
                }
                if (notes.size > MAX_SHOWN) item { MoreNotes(notes.size) }
            }
        }
    }
}

/** Every row of a widget list travels to the launcher in one parcel, so a long list is cut short. */
private const val MAX_SHOWN = 24

@Composable
private fun MoreNotes(total: Int) {
    val context = LocalContext.current
    Box(GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(appAction(context, MainActivity.OPEN_NOTES)), contentAlignment = Alignment.Center) {
        WText("在 App 里看全部 $total 篇", 12.sp, WColors.brass, medium = true)
    }
}

@Composable
private fun NoteTile(n: Note, lines: Int) {
    val context = LocalContext.current
    Column(
        GlanceModifier.fillMaxWidth().cornerRadius(R.dimen.widget_inner_radius).background(ImageProvider(R.drawable.widget_tile))
            .clickable(sheetAction(context, WidgetSheetActivity.NOTE_EDIT, n.id))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (n.pinned) WIcon(R.drawable.ic_w_pin, WColors.brass, 12.dp, GlanceModifier.padding(end = 4.dp))
            WText(NoteText.title(n.body), 14.sp, WColors.ink, medium = true, modifier = GlanceModifier.defaultWeight())
            WText(Dates.relative(n.updatedAt), 10.sp, WColors.faint, modifier = GlanceModifier.padding(start = 6.dp))
        }
        val preview = NoteText.preview(n.body)
        if (preview.isNotEmpty()) WText(preview, 12.sp, WColors.muted, maxLines = lines)
    }
}

class NoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoteWidget()
}
