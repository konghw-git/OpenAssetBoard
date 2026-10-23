package com.assetboard.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.ripple
import com.assetboard.ui.components.glass
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.assetboard.data.Note
import com.assetboard.data.NoteText
import com.assetboard.data.NoteTopic
import com.assetboard.domain.Dates
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.theme.AppTheme
import java.time.LocalDate

/** A topic's colour follows its place in the list, like the todo lists' do; 未归类 stays neutral. */
@Composable
fun topicColor(topics: List<NoteTopic>, topicId: String?): Color {
    val i = topics.indexOfFirst { it.id == topicId }
    return if (i < 0) MaterialTheme.colorScheme.onSurfaceVariant else AppTheme.extra.group(i)
}

/** Today's edits by the clock, older ones by the day. */
fun whenLabel(at: Long, now: Long = System.currentTimeMillis()): String =
    if (Dates.daysBetween(at, now) == 0L) Dates.time(at) else Dates.relative(at, now)

/** "10月12日", with the year only when it is not this one. */
fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String =
    if (date.year == today.year) "${date.monthValue}月${date.dayOfMonth}日" else "${date.year}年${date.monthValue}月${date.dayOfMonth}日"

/** `#标签` in [color], everything else as it is. */
fun tagged(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in NoteText.TAG.findAll(text)) {
        append(text.substring(at, m.range.first))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) { append(m.value) }
        at = m.range.last + 1
    }
    append(text.substring(at))
}

/**
 * The editor's look without touching the text: the title line larger and bold, `#标签` in the
 * accent colour. Offsets map one to one, so the cursor and selection are never disturbed.
 */
class NoteBodyLook(private val titleSize: TextUnit, private val tagColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val styled = buildAnnotatedString {
            append(s)
            // The first line with something on it is the title, as NoteText.title reads it.
            var start = 0
            while (start <= s.length) {
                val end = s.indexOf('\n', start).let { if (it < 0) s.length else it }
                if (s.substring(start, end).isNotBlank()) {
                    addStyle(SpanStyle(fontSize = titleSize, fontWeight = FontWeight.SemiBold), start, end)
                    break
                }
                start = end + 1
            }
            for (m in NoteText.TAG.findAll(s)) addStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Medium), m.range.first, m.range.last + 1)
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is NoteBodyLook && other.titleSize == titleSize && other.tagColor == tagColor
    override fun hashCode() = titleSize.hashCode() * 31 + tagColor.hashCode()
}

/** A small rounded label in a topic's colour. */
@Composable
fun TopicTag(name: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.11f)).padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
    }
}

val NoteCardShape = RoundedCornerShape(22.dp)

/**
 * One note in a list, on glass: title, the lines after it, its topic and when it last changed.
 * [lifted] while it is being dragged; [selected] tints it and rings it in the accent.
 */
@Composable
fun NoteCard(
    note: Note,
    topics: List<NoteTopic>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTopic: Boolean = true,
    preview: AnnotatedString? = null,
    lifted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val topic = topics.firstOrNull { it.id == note.topicId }
    val body = preview ?: remember(note.body, cs.primary) { tagged(NoteText.preview(note.body), cs.primary) }
    val lift by animateFloatAsState(if (lifted) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "lift")
    val ring by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.7f) else Color.Transparent, tween(180), label = "noteRing")
    Column(
        modifier
            .fillMaxWidth()
            .pressScale(source, 0.98f)
            .graphicsLayer { scaleX = 1f + 0.03f * lift; scaleY = scaleX }
            .glass(NoteCardShape, glass, if (selected) cs.primary.copy(alpha = 0.12f) else Color.Unspecified, elevation = 10.dp + 18.dp * lift)
            .border(1.5.dp, ring, NoteCardShape)
            .clip(NoteCardShape)
            .combinedClickable(interactionSource = source, indication = ripple(), onClick = onClick, onLongClick = onLongClick)
            .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (note.title == NoteText.UNTITLED) cs.outline else cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (note.pinned) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.PushPin, "已置顶", tint = cs.secondary, modifier = Modifier.size(15.dp))
            }
        }
        if (body.isNotEmpty()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showTopic && topic != null) {
                TopicTag(topic.name, topicColor(topics, topic.id))
                Spacer(Modifier.width(8.dp))
            }
            Text(whenLabel(note.updatedAt), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

/** A weekly review a note came from; tapping it opens that week. */
@Composable
fun WeekSourceChip(start: LocalDate, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(cs.secondary.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ViewWeek, null, tint = cs.secondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text("${com.assetboard.data.Weeks.shortTitle(start)}复盘", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
    }
}

/** A diary day a note came from; tapping it opens that day. */
@Composable
fun SourceChip(date: LocalDate, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(cs.tertiary.copy(alpha = 0.10f)).clickable(onClick = onClick).padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Event, null, tint = cs.tertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text("${dayLabel(date)}的日记", style = MaterialTheme.typography.labelMedium, color = cs.tertiary)
    }
}
