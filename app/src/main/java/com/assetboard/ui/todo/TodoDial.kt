package com.assetboard.ui.todo

import com.assetboard.ui.components.glass
import com.assetboard.ui.components.LocalAmbient
import com.assetboard.ui.components.rim
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.runtime.key
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.assetboard.data.TodoData
import com.assetboard.domain.Habit
import com.assetboard.domain.todo.TodoView
import com.assetboard.domain.todo.TodoViews
import com.assetboard.ui.components.habitCharge
import com.assetboard.ui.components.iconVector
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.components.tint
import com.assetboard.ui.theme.AppTheme
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SPIN_MS = 120_000
private const val GAP_DEG = 2.4f

/**
 * The five smart views as one ring of curved segments, close together and turning slowly, with
 * the new-todo button in the middle. Each label turns with its segment, its foot toward the
 * middle like the numbers on a dial, so it never leaves the band; a tap picks the segment under it.
 */
@Composable
internal fun ViewRing(
    d: TodoData?,
    today: LocalDate,
    selected: String,
    doneToday: Int,
    totalToday: Int,
    composing: Boolean,
    onSelect: (String) -> Unit,
    onHub: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val outer = size / 2
    val band = outer * 0.46f
    val inner = outer - band
    val spin by rememberInfiniteTransition(label = "ring").animateFloat(0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "spin")
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 220f)) }
    val fills = viewOrder.map { v -> animateFloatAsState(if (selected == ViewKey.of(v)) 1f else 0f, tween(280), label = "fill").value }
    val base = cs.surfaceContainer
    val shade = Color.Black.copy(alpha = 0.06f)
    // On a page with colour behind it the wedges are glass too.
    val glassy = if (LocalAmbient.current != null) glass else null

    Box(
        modifier
            .size(size)
            .graphicsLayer { val k = 0.6f + 0.4f * grow.value; scaleX = k; scaleY = k; alpha = grow.value.coerceIn(0f, 1f); rotationZ = (1f - grow.value) * -40f },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(size)
                .pointerInput(size) {
                    detectTapGestures { p ->
                        val c = Offset(this.size.width / 2f, this.size.height / 2f)
                        val dist = (p - c).getDistance()
                        if (dist < inner.toPx() || dist > outer.toPx() + 6.dp.toPx()) return@detectTapGestures
                        val deg = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
                        val i = (((deg - (-126f + spin)) % 360f + 360f) % 360f / 72f).toInt().coerceIn(0, 4)
                        onSelect(ViewKey.of(viewOrder[i]))
                    }
                },
        ) {
            val c = center
            val r = inner.toPx()
            viewOrder.forEachIndexed { i, v ->
                val style = viewStyles.getValue(v)
                val start = -126f + 72f * i + spin + GAP_DEG / 2
                val pop = 4.dp.toPx() * fills[i]
                val path = sectorPath(c, r, outer.toPx() - 4.dp.toPx() + pop, start, 72f - GAP_DEG, 7.dp.toPx())
                translate(top = 1.5.dp.toPx()) { drawPath(path, shade) }
                if (glassy != null) {
                    drawPath(path, glassy.fill)
                    rim(path, glassy)
                } else {
                    drawPath(path, base)
                }
                if (fills[i] > 0f) {
                    drawPath(path, Brush.linearGradient(listOf(style.start, style.end), start = Offset(0f, 0f), end = Offset(this.size.width, this.size.height)), alpha = fills[i])
                }
            }
        }
        viewOrder.forEachIndexed { i, v ->
            SegmentLabel(
                v, d, today, fills[i],
                Modifier
                    .offset {
                        val mid = Math.toRadians((-90.0 + 72.0 * i + spin))
                        val rr = (inner + band / 2 - 2.dp).toPx()
                        IntOffset((rr * cos(mid)).roundToInt(), (rr * sin(mid)).roundToInt())
                    }
                    .graphicsLayer { rotationZ = 72f * i + spin }
                    .semantics {
                        contentDescription = v.label
                        role = Role.Tab
                        this.selected = selected == ViewKey.of(v)
                    },
            )
        }
        DialHub(doneToday, totalToday, composing, Modifier.size(inner * 2 - 6.dp), onHub)
    }
}

/** An annular sector from [start] through [sweep] degrees, its four corners rounded by [corner]. */
private fun sectorPath(c: Offset, r: Float, rr: Float, start: Float, sweep: Float, corner: Float): Path {
    fun pt(radius: Float, deg: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat())
    }
    val end = start + sweep
    val dOut = Math.toDegrees((corner / rr).toDouble()).toFloat()
    val dIn = Math.toDegrees((corner / r).toDouble()).toFloat()
    val outerRect = Rect(c, rr)
    val innerRect = Rect(c, r)
    return Path().apply {
        pt(rr - corner, start).let { moveTo(it.x, it.y) }
        pt(rr, start).let { k -> pt(rr, start + dOut).let { e -> quadraticTo(k.x, k.y, e.x, e.y) } }
        arcTo(outerRect, start + dOut, sweep - 2 * dOut, false)
        pt(rr, end).let { k -> pt(rr - corner, end).let { e -> quadraticTo(k.x, k.y, e.x, e.y) } }
        pt(r + corner, end).let { lineTo(it.x, it.y) }
        pt(r, end).let { k -> pt(r, end - dIn).let { e -> quadraticTo(k.x, k.y, e.x, e.y) } }
        arcTo(innerRect, end - dIn, -(sweep - 2 * dIn), false)
        pt(r, start).let { k -> pt(r + corner, start).let { e -> quadraticTo(k.x, k.y, e.x, e.y) } }
        close()
    }
}

/** Icon and count over the name, white once the segment is filled. */
@Composable
private fun SegmentLabel(v: TodoView, d: TodoData?, today: LocalDate, fill: Float, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val style = viewStyles.getValue(v)
    val n = d?.let { TodoViews.count(v, it.todos, today) } ?: 0
    val overdue = v == TodoView.TODAY && d != null && d.todos.any { TodoViews.isOpen(it) && TodoViews.isOverdue(it, today) }
    val fg = lerp(cs.onSurface, Color.White, fill)
    // Line boxes trimmed to the glyphs, so the two lines sit together in the middle of the band
    // instead of the icon riding its outer edge and the name its inner one.
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(style.icon, null, tint = lerp(style.end, Color.White, fill), modifier = Modifier.size(14.dp))
            if (v != TodoView.LOGBOOK) {
                Spacer(Modifier.width(3.dp))
                AnimatedContent(n, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "count") {
                    Text(it.toString(), fontSize = ringSp(13f), fontWeight = FontWeight.Bold, color = fg, style = TightLines)
                }
            }
            if (overdue) {
                Spacer(Modifier.width(2.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (fill > 0.5f) Color.White else cs.error))
            }
        }
        Text(ringLabel(v), fontSize = ringSp(11f), color = lerp(cs.onSurfaceVariant, Color.White.copy(alpha = 0.9f), fill), maxLines = 1, softWrap = false, style = TightLines)
    }
}

private val TightLines = TextStyle(
    lineHeight = 1.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Two characters, the same weight as the others; the full name stays in the content description. */
private fun ringLabel(v: TodoView): String = if (v == TodoView.SOMEDAY) "以后" else v.label

/** Text inside the ring's fixed geometry follows the system font size only up to 1.15×. */
@Composable
private fun ringSp(value: Float): TextUnit {
    val scale = LocalDensity.current.fontScale
    return (value * minOf(scale, MAX_RING_FONT_SCALE) / scale).sp
}

private const val MAX_RING_FONT_SCALE = 1.15f

/**
 * The middle of the ring: the new-todo button, in the same gradient as the other tabs' main
 * actions. Its outer ring is today's progress; the + turns into × while the field is open.
 */
@Composable
internal fun DialHub(done: Int, total: Int, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val shown by animateFloatAsState(if (total == 0) 0f else done.toFloat() / total, tween(700), label = "progress")
    val turn by animateFloatAsState(if (open) 45f else 0f, spring(dampingRatio = 0.6f), label = "turn")
    val source = remember { MutableInteractionSource() }
    val track = cs.outlineVariant
    val accent = cs.primary
    Box(
        modifier
            .pressScale(source, 0.9f)
            .drawBehind {
                val stroke = 4.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                if (shown > 0f) drawArc(Brush.sweepGradient(listOf(accent.copy(alpha = 0.6f), accent, accent.copy(alpha = 0.6f))), -90f, 360f * shown, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            .padding(8.dp)
            .shadow(12.dp, CircleShape, ambientColor = extra.heroStart, spotColor = extra.heroStart)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(extra.heroEnd, extra.heroStart)))
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), endY = size.height * 0.55f))
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Add, if (open) "收起新待办" else "新待办", tint = extra.onHero, modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = turn })
            AnimatedContent(if (open) "收起" else "新待办", transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "hubLabel") {
                Text(it, style = MaterialTheme.typography.labelSmall, color = extra.onHero.copy(alpha = 0.9f), maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- the habit battery

/**
 * The daily habits as the cells of an upright battery. Each tap charges a cell; holding one floats
 * its rubric beside the battery until the finger lifts. A rest day taken before rest days were
 * retired ([skipped]) still takes its cell out of the count. The charge color climbs from amber to
 * green as more are done, and a full battery lights its cap. The 每日 pill in the corner opens
 * [onCustomize]. Fewer habits keep their cells at a comfortable height instead of stretching them
 * over the whole body.
 */
@Composable
internal fun HabitBattery(
    habits: List<Habit>,
    checked: Set<String>,
    onToggle: (Habit) -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier = Modifier,
    skipped: Set<String> = emptySet(),
) {
    val cs = MaterialTheme.colorScheme
    val level = habits.count { it.key in checked }
    val total = habits.count { it.key !in skipped || it.key in checked }
    val full = total > 0 && level == total
    val charge by animateColorAsState(habitCharge(level, total), tween(400), label = "charge")
    val flow = rememberInfiniteTransition(label = "battery")
    val shimmer by flow.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(2_200, easing = LinearEasing)), label = "shimmer")
    val glow by flow.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1_100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val edge = if (full) charge.copy(alpha = glow) else cs.outlineVariant
    val body = RoundedCornerShape(18.dp)
    Column(modifier) {
        // The count and the name sit above the cap's height, clear of the body and its shadow.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(level > 0, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Icon(Icons.Rounded.Bolt, null, tint = charge, modifier = Modifier.size(14.dp))
            }
            Text("$level/$total", fontSize = ringSp(12f), fontWeight = FontWeight.SemiBold, color = if (level > 0) charge else cs.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            CustomizePill(if (full) "满格" else "每日", if (full) charge else cs.onSurfaceVariant, onCustomize)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.align(Alignment.CenterHorizontally).width(34.dp).height(7.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .background(if (full) charge else cs.outlineVariant),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (full) Modifier.shadow(10.dp, body, ambientColor = charge, spotColor = charge).background(cs.surfaceContainer, body) else Modifier.glass(body, glass, elevation = 8.dp))
                .clip(body)
                .border(2.dp, edge, body)
                .padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            habits.forEach { h ->
                key(h.key) {
                    val on = h.key in checked
                    HabitCell(
                        h, on, rest = !on && h.key in skipped, charge, shimmer, Modifier.weight(1f, fill = false).fillMaxWidth().height(MaxCell),
                    ) { onToggle(h) }
                }
            }
        }
    }
}

/** The tallest a battery cell gets, when there are only a few. */
private val MaxCell = 40.dp

/** 每日 (or 满格) with a tune mark, as a small pill: tapping it opens the habit editor. */
@Composable
private fun CustomizePill(text: String, color: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(source, 0.92f)
            .clip(RoundedCornerShape(50))
            .background(cs.onSurface.copy(alpha = 0.06f))
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(50))
            .clickable(interactionSource = source, indication = null, onClickLabel = "自定义每日打卡", onClick = onClick)
            .padding(start = 8.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = ringSp(12f), color = color, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Rounded.Tune, null, tint = color, modifier = Modifier.size(12.dp))
    }
}

/**
 * One habit's cell: charged when done, hatched and marked 休 on a rest day ([rest]). A tap checks
 * it; held, its rubric floats beside it ([RubricPeek]) until the finger lifts or slides away.
 */
@Composable
private fun HabitCell(h: Habit, on: Boolean, rest: Boolean, charge: Color, shimmer: Float, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var peek by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onClick)
    // The cell fills left to right when charged, and drains back when unchecked.
    val level by animateFloatAsState(if (on) 1f else 0f, tween(if (on) 520 else 260, easing = FastOutSlowInEasing), label = "cell")
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    val empty = cs.surfaceContainerHighest.copy(alpha = 0.6f)
    val hatch = cs.outline.copy(alpha = 0.16f)
    Row(
        modifier
            .pressScale(source, 0.95f)
            .clip(shape)
            .drawBehind {
                drawRect(empty)
                if (rest) {
                    // Thin diagonal strokes: a day off, not an empty cell.
                    val step = 7.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(hatch, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
                        x += step
                    }
                }
                if (level > 0f) {
                    val w = size.width * level
                    drawRect(Brush.horizontalGradient(listOf(charge.copy(alpha = 0.85f), charge), endX = w), size = Size(w, size.height))
                    // A light band running across the charge, like a phone charging.
                    if (level >= 1f) {
                        val x = size.width * shimmer
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent), startX = x - size.width * 0.18f, endX = x + size.width * 0.18f))
                    }
                }
            }
            .pointerInput(h.key) {
                detectTapGestures(
                    onPress = { at ->
                        val press = PressInteraction.Press(at)
                        source.emit(press)
                        val lifted = tryAwaitRelease()
                        source.emit(if (lifted) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                        peek = false
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        peek = true
                    },
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tap()
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Checkbox
                stateDescription = when {
                    on -> "已完成"
                    rest -> "今天休息"
                    else -> "未完成"
                }
                h.conditions.takeIf { it.isNotEmpty() }?.let { contentDescription = h.label + "，打卡标准：" + it.joinToString("；") }
                onClick { tap(); true }
            }
            .padding(start = 8.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = lerp(if (rest) cs.outline else cs.onSurface, Color.White, level)
        Icon(h.iconVector, null, tint = lerp(if (rest) cs.outline else h.tint, Color.White, level), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        // Lines as tall as the text, so the name sits in the middle of the cell however short the
        // cell is: with the body's line height and font padding, a short cell (the unfolded
        // layout's) trimmed it unevenly above and below.
        val tight = LocalTextStyle.current.merge(TightLines)
        Text(h.label, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = fg, maxLines = 1, overflow = TextOverflow.Clip, style = tight, modifier = Modifier.weight(1f))
        if (rest) {
            Text("休", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.outline, style = tight, modifier = Modifier.width(17.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            return@Row
        }
        Box(
            Modifier.size(17.dp).clip(CircleShape)
                .background(if (on) Color.White else Color.Transparent)
                .border(1.5.dp, if (on) Color.White else cs.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedVisibility(on, enter = scaleIn(spring(dampingRatio = 0.45f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                Icon(Icons.Rounded.Check, null, tint = charge, modifier = Modifier.size(13.dp))
            }
        }
    }
    if (peek) RubricPeek(h, on)
}

/**
 * A habit's rubric, floating to the left of its cell over the ring while the cell is held: a
 * frosted, see-through card in the habit's colour, gone the moment the finger lifts. It only
 * shows; nothing in it is ticked.
 */
@Composable
private fun RubricPeek(h: Habit, done: Boolean) {
    val cs = MaterialTheme.colorScheme
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val position = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val left = anchorBounds.left - popupContentSize.width - gap
                val x = if (left >= 0) left else (windowSize.width - popupContentSize.width) / 2
                val y = (anchorBounds.center.y - popupContentSize.height / 2).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        }
    }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 520f)) }
    val conditions = h.conditions
    val shape = RoundedCornerShape(20.dp)
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        // Room around the card for its shadow, which the popup's window would otherwise cut.
        Box(Modifier.padding(12.dp)) {
            Column(
                Modifier
                    .widthIn(min = 168.dp, max = 236.dp)
                    .graphicsLayer {
                        val k = 0.88f + 0.12f * shown.value
                        scaleX = k; scaleY = k
                        alpha = shown.value
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                    }
                    .shadow(18.dp, shape, ambientColor = h.tint.copy(alpha = 0.5f), spotColor = h.tint.copy(alpha = 0.6f))
                    .clip(shape)
                    // See-through at the edges of reading: dense enough that the ring under it never muddles the text.
                    .background(Brush.linearGradient(listOf(cs.surfaceContainerHigh.copy(alpha = 0.94f), cs.surfaceContainer.copy(alpha = 0.88f))))
                    .background(Brush.linearGradient(listOf(h.tint.copy(alpha = 0.16f), Color.Transparent)))
                    .border(1.dp, Brush.linearGradient(listOf(h.tint.copy(alpha = 0.55f), Color.White.copy(alpha = 0.18f))), shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(h.tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(h.iconVector, null, tint = h.tint, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(h.label, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (done) "今天已打卡" else "打卡标准", style = MaterialTheme.typography.labelSmall, color = if (done) h.tint else cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (conditions.isEmpty()) {
                    Text("还没有设打卡标准，点「每日」可以添加", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        conditions.forEach { c ->
                            Row {
                                Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(h.tint))
                                Spacer(Modifier.width(8.dp))
                                Text(c, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The ring and the battery side by side, the ring sized to leave the battery room for its labels. */
@Composable
internal fun ViewsAndHabits(
    d: TodoData?,
    today: LocalDate,
    selected: String,
    doneToday: Int,
    totalToday: Int,
    composing: Boolean,
    habits: List<Habit>,
    checked: Set<String>,
    onSelect: (String) -> Unit,
    onHub: () -> Unit,
    onHabit: (Habit) -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier = Modifier,
    skipped: Set<String> = emptySet(),
) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val ring = (maxWidth * 0.58f).coerceAtMost(236.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ViewRing(d, today, selected, doneToday, totalToday, composing, onSelect, onHub, ring)
            HabitBattery(habits, checked, onHabit, onCustomize, Modifier.weight(1f).height(ring).padding(vertical = 2.dp), skipped)
        }
    }
}
