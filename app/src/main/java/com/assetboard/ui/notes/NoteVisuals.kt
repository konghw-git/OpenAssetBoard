package com.assetboard.ui.notes

import com.assetboard.domain.AppClock
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
import com.assetboard.ui.components.GlassStyle
import com.assetboard.ui.components.softShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
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
fun whenLabel(at: Long, now: Long = AppClock.millis()): String =
    if (Dates.daysBetween(at, now) == 0L) Dates.time(at) else Dates.relative(at, now)

/** "10月12日", with the year only when it is not this one. */
fun dayLabel(date: LocalDate, today: LocalDate = AppClock.today()): String =
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
 * Where a note sits among its section's: the notes of a section read as one pane of glass, its
 * corners rounded only at the panel's top and bottom, the rows parted by hairlines. A note on
 * its own (a search result, one being dragged) is a whole card.
 */
enum class PanelPlace {
    ALONE, FIRST, MIDDLE, LAST;

    val top get() = this == ALONE || this == FIRST
    val bottom get() = this == ALONE || this == LAST

    fun shape(radius: Dp) = RoundedCornerShape(
        topStart = if (top) radius else 0.dp, topEnd = if (top) radius else 0.dp,
        bottomStart = if (bottom) radius else 0.dp, bottomEnd = if (bottom) radius else 0.dp,
    )

    companion object {
        fun of(index: Int, count: Int): PanelPlace = when {
            count <= 1 -> ALONE
            index == 0 -> FIRST
            index == count - 1 -> LAST
            else -> MIDDLE
        }
    }
}

/**
 * This row's slice of a glass panel shared with the rows above and below it. Each row draws the
 * panel's whole outline, reaching on past its own edges where the panel goes on, and keeps only
 * the part over itself: fills and side rims meet seamlessly, and the shadow, cut the same way,
 * runs down the panel's sides without doubling. The panel's lit top and its glow belong to the
 * first row; each later one draws a hairline across its top, short of both sides.
 */
fun Modifier.panelSlice(place: PanelPlace, style: GlassStyle, radius: Dp, tint: Color, divider: Color, elevation: Dp = 8.dp): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    val r = radius.toPx()
    val far = elevation.toPx() * 4 + r * 2
    val top = if (place.top) 0f else -r * 2
    val bottom = if (place.bottom) h else h + r * 2
    val panel = Path().apply { addRoundRect(RoundRect(0f, top, w, bottom, CornerRadius(r))) }
    clipRect(-far, if (place.top) -far else 0f, w + far, if (place.bottom) h + far else h) {
        softShadow(panel, style.shadow, elevation)
    }
    clipRect(-far, 0f, w + far, h) {
        drawPath(panel, style.fill)
        if (tint != Color.Unspecified) drawPath(panel, tint)
        clipPath(panel) {
            if (place.top) drawRect(Brush.verticalGradient(listOf(style.sheen, Color.Transparent), endY = minOf(h, 64.dp.toPx())))
        }
        drawPath(panel, style.edge, style = Stroke(2.dp.toPx()))
        drawPath(panel, style.rimShade.copy(alpha = style.rimShade.alpha * 0.7f), style = Stroke(1.2.dp.toPx()))
        if (place.top) {
            clipPath(panel) {
                drawPath(panel, Brush.verticalGradient(listOf(style.glow, Color.Transparent), startY = 0f, endY = minOf(h * 0.45f, 26.dp.toPx())), style = Stroke(3.dp.toPx()))
            }
        } else {
            val inset = 16.dp.toPx()
            drawLine(divider, Offset(inset, 0f), Offset(w - inset, 0f), strokeWidth = 1.dp.toPx())
        }
    }
}

/**
 * One note in a list: the title, the lines after it, and a quiet line of its topic and when it
 * last changed. In a section's panel ([place]) it is a row of the pane; alone, [lifted] while
 * being dragged, it is a card of its own. [selected] tints it and rings it in the accent.
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
    place: PanelPlace = PanelPlace.ALONE,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val topic = topics.firstOrNull { it.id == note.topicId }
    val body = preview ?: remember(note.body, cs.primary) { tagged(NoteText.preview(note.body), cs.primary) }
    val lift by animateFloatAsState(if (lifted) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "lift")
    val ring by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.7f) else Color.Transparent, tween(180), label = "noteRing")
    // A lifted note leaves the panel: it becomes a whole card while it is carried.
    val alone = place == PanelPlace.ALONE || lifted
    val shape = if (alone) NoteCardShape else place.shape(22.dp)
    val tint = if (selected) cs.primary.copy(alpha = 0.12f) else Color.Unspecified
    val wash by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.12f) else Color.Transparent, tween(180), label = "noteWash")
    Column(
        modifier
            .fillMaxWidth()
            .then(if (alone) Modifier.pressScale(source, 0.98f) else Modifier)
            .graphicsLayer { scaleX = 1f + 0.03f * lift; scaleY = scaleX }
            .then(
                if (alone) {
                    Modifier.glass(NoteCardShape, glass, tint, elevation = 10.dp + 18.dp * lift).border(1.5.dp, ring, shape)
                } else {
                    // Selected inside the panel: a rounded wash and ring set in from the row's edges,
                    // so the pane's own outline stays whole.
                    Modifier.panelSlice(place, glass, 22.dp, Color.Unspecified, cs.outlineVariant.copy(alpha = 0.7f)).drawBehind {
                        val inset = 4.dp.toPx()
                        val r = CornerRadius(18.dp.toPx())
                        val at = Offset(inset, inset)
                        val box = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                        drawRoundRect(wash, at, box, r)
                        drawRoundRect(ring, at, box, r, style = Stroke(1.5.dp.toPx()))
                    }
                }
            )
            .clip(shape)
            .combinedClickable(interactionSource = source, indication = ripple(), onClick = onClick, onLongClick = onLongClick)
            .padding(start = 18.dp, end = 16.dp, top = 13.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, letterSpacing = 0.1.sp),
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
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp, letterSpacing = 0.15.sp),
                color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(whenLabel(note.updatedAt), style = MaterialTheme.typography.labelMedium.merge(com.assetboard.ui.theme.NumberStyle), color = cs.onSurfaceVariant.copy(alpha = 0.75f))
            if (showTopic && topic != null) {
                val color = topicColor(topics, topic.id)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(5.dp))
                Text(topic.name, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            }
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
