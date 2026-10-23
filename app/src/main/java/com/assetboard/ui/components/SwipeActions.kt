package com.assetboard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Keeps one row of a list open at a time; opening another closes the last. */
@Stable
class SwipeGroup {
    var open by mutableStateOf<Any?>(null)
        internal set

    fun closeAll() { open = null }
}

@Composable
fun rememberSwipeGroup(): SwipeGroup = remember { SwipeGroup() }

/**
 * Whether the rows around are at rest, none sliding open or shut. A field in a row waits for it
 * before it takes focus: focused while the row still slides, it asks the scrollers around to show
 * it where it is for the moment, off the side of the page, and the tabs turn toward it and back.
 */
val LocalSwipeAtRest = staticCompositionLocalOf<() -> Boolean> { { true } }

/** A button revealed by swiping right. */
class SwipeAction(val label: String, val icon: ImageVector, val color: Color, val onClick: () -> Unit)

private val ActionWidth = 66.dp
private val DeleteWidth = 74.dp
private val CompactDeleteWidth = 52.dp
private val Gap = 8.dp
private const val FLING = 900f

/**
 * One list row with the app's swipe gestures, the same for todos and notes:
 *
 * - left reveals 删除; swiping on past the threshold, or tapping it, turns the card to dust and
 *   calls [onDelete];
 * - right reveals [actions]; swiping on past the threshold runs [full], or the first of them
 *   when no [full] is given, the way a full swipe left deletes.
 *
 * An open row closes when another opens, when it is tapped, or when [group] closes all. A stroke
 * on a row is the row's; the tabs turn from anywhere else (see [rowDrag]). A row inside another (a checklist item in its todo) takes only the
 * directions it has something for; the rest go to the outer row. [compact] is for short rows:
 * the delete button shows its icon only. [deleteLabel], [deleteIcon] and [deleteColor] name what a
 * left swipe does when it is not quite a delete (an account with history is disabled instead, a
 * list archived).
 */
@Composable
fun SwipeActionsBox(
    key: Any,
    group: SwipeGroup?,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    actions: List<SwipeAction> = emptyList(),
    /** What a full swipe right does when it is not the first of [actions]; its button spreads over them as the swipe arms. */
    full: SwipeAction? = null,
    onDelete: (() -> Unit)? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    deleteLabel: String = "删除",
    deleteIcon: ImageVector = Icons.Outlined.Delete,
    deleteColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val dust = LocalDust.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val delete by rememberUpdatedState(onDelete)
    val first by rememberUpdatedState(full ?: actions.firstOrNull())

    val gap = with(density) { Gap.toPx() }
    val startOpen = if (actions.isEmpty()) 0f else with(density) { (ActionWidth + Gap).toPx() } * actions.size + gap
    val endOpen = if (onDelete == null) 0f else with(density) { ((if (compact) CompactDeleteWidth else DeleteWidth) + Gap).toPx() } + gap
    // Arming right comes sooner than delete does: the actions already take most of the way, so a short push on past them.
    val startFull = maxOf(startOpen + with(density) { 36.dp.toPx() }, width * 0.48f)
    val endFull = maxOf(endOpen + with(density) { 64.dp.toPx() }, width * 0.52f)
    val x = offset.value
    val armedStart = x > startFull && actions.isNotEmpty()
    val armedEnd = -x > endFull && onDelete != null
    val hidden = dust?.isHidden(key) == true
    val outerAtRest = LocalSwipeAtRest.current
    val atRest = remember(outerAtRest) { { outerAtRest() && !offset.isRunning && abs(offset.value) < 0.5f } }

    // A tick as a full swipe arms or disarms.
    LaunchedEffect(armedStart, armedEnd) { if (dragging && (armedStart || armedEnd)) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    // Another row opened, or the list asked everything to close.
    LaunchedEffect(group?.open) { if (group != null && group.open != key && !dragging && offset.value != 0f) offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }

    fun settle(to: Float) = scope.launch {
        if (to != 0f) group?.open = key else if (group?.open == key) group.open = null
        offset.animateTo(to, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
    }

    fun runDelete() = scope.launch {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        dust?.dissolve(listOf(key), direction = -1f, cell = with(density) { 3.dp.toPx() })
        if (group?.open == key) group.open = null
        delete?.invoke()
        offset.snapTo(0f)
    }

    fun runFirst() {
        val a = first ?: return
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        settle(0f)
        a.onClick()
    }

    fun dragBy(d: Float) {
        val next = offset.value + d
        val clamped = when {
            next > 0 && actions.isEmpty() -> 0f
            next < 0 && onDelete == null -> 0f
            // Past the full threshold it follows the finger more slowly.
            next > startFull -> startFull + (next - startFull) * 0.5f
            next < -endFull -> -endFull + (next + endFull) * 0.5f
            else -> next
        }.coerceIn(-width, width)
        scope.launch { offset.snapTo(clamped) }
    }

    fun stopped(v: Float) {
        dragging = false
        val now = offset.value
        when {
            now < 0 && (-now > endFull || (v < -FLING * 3 && -now > endOpen)) && onDelete != null -> runDelete()
            now < 0 && (-now > endOpen * 0.5f || v < -FLING) && v < FLING -> settle(-endOpen)
            now > 0 && now > startFull && actions.isNotEmpty() -> runFirst()
            now > 0 && (now > startOpen * 0.5f || v > FLING) && v > -FLING -> settle(startOpen)
            else -> settle(0f)
        }
    }

    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat() }
            .graphicsLayer { alpha = if (hidden) 0f else 1f },
    ) {
        if (x > 0.5f) {
            StartActions(actions, full, x, startOpen, armedStart, shape, Modifier.matchParentSize()) { a ->
                settle(0f)
                a.onClick()
            }
        } else if (x < -0.5f) {
            DeleteAction(-x, endOpen, armedEnd, shape, compact, deleteLabel, deleteIcon, deleteColor ?: MaterialTheme.colorScheme.error, Modifier.matchParentSize()) { runDelete() }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .rowDrag(
                    enabled = enabled && (actions.isNotEmpty() || onDelete != null),
                    open = { offset.value != 0f },
                    claims = { dx -> if (dx > 0) actions.isNotEmpty() else onDelete != null },
                    onStart = {
                        dragging = true
                        if (group != null && group.open != key) group.open = key
                    },
                    onDelta = ::dragBy,
                    onStop = ::stopped,
                )
                .dissolvable(key),
        ) {
            CompositionLocalProvider(LocalSwipeAtRest provides atRest) { content() }
            // While open, a tap on the card closes it instead of opening the item.
            if (abs(x) > 0.5f && !dragging) {
                Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { settle(0f) } })
            }
        }
    }
}

@Composable
private fun StartActions(actions: List<SwipeAction>, full: SwipeAction?, x: Float, open: Float, armed: Boolean, shape: RoundedCornerShape, modifier: Modifier, onClick: (SwipeAction) -> Unit) {
    val density = LocalDensity.current
    val progress = (x / open).coerceIn(0f, 1f)
    val gap = with(density) { Gap.toPx() }
    val fill by animateFloatAsState(if (armed) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "armed")
    val base = with(density) { ActionWidth.toPx() }
    // Armed, the first button stretches over the whole revealed space and the rest fade; with a
    // [full] action of its own, that one's button spreads over all of them instead.
    val stretch = full == null
    val firstWidth = if (stretch) base + (x - gap * 2 - base) * fill else base
    Box(modifier) {
        Row(Modifier.matchParentSize().padding(start = Gap), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
            actions.forEachIndexed { i, a ->
                val w = if (i == 0) firstWidth.coerceAtLeast(base) else base
                ActionButton(
                    a.label, a.icon, a.color, shape,
                    Modifier
                        .width(with(density) { w.toDp() })
                        .fillMaxHeight()
                        .graphicsLayer {
                            val s = 0.7f + 0.3f * progress
                            scaleX = s; scaleY = s
                            alpha = if (i == 0 && stretch) progress else progress * (1f - fill)
                        },
                ) { onClick(a) }
            }
        }
        if (full != null && fill > 0.01f) {
            ActionButton(
                full.label, full.icon, full.color, shape,
                Modifier
                    .padding(start = Gap)
                    .width(with(density) { (x - gap * 2).coerceAtLeast(base).toDp() })
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = fill
                        scaleY = 0.92f + 0.08f * fill
                    },
                iconScale = 0.9f + 0.2f * fill,
            ) { onClick(full) }
        }
    }
}

@Composable
private fun DeleteAction(x: Float, open: Float, armed: Boolean, shape: RoundedCornerShape, compact: Boolean, label: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val density = LocalDensity.current
    val progress = (x / open).coerceIn(0f, 1f)
    val gap = with(density) { Gap.toPx() }
    val base = with(density) { (if (compact) CompactDeleteWidth else DeleteWidth).toPx() }
    val pop by animateFloatAsState(if (armed) 1.12f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "pop")
    // The button fills the space the card left, so a long swipe reads as one red stretch.
    val w = (x - gap * 2).coerceAtLeast(base * progress).coerceAtLeast(1f)
    Box(modifier.padding(end = Gap), contentAlignment = Alignment.CenterEnd) {
        ActionButton(
            label, icon, color, shape,
            Modifier
                .width(with(density) { w.toDp() })
                .fillMaxHeight()
                .graphicsLayer {
                    alpha = progress
                    val s = 0.7f + 0.3f * progress
                    scaleY = s
                },
            iconScale = pop,
            iconOnly = compact,
            onClick = onClick,
        )
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, color: Color, shape: RoundedCornerShape, modifier: Modifier, iconScale: Float = 1f, iconOnly: Boolean = false, onClick: () -> Unit) {
    val on = Color.White
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.92f), color)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, tint = on, modifier = Modifier.size(if (iconOnly) 18.dp else 21.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
            if (!iconOnly) Text(label, color = on, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/**
 * A row's sideways drag, shared with the scrollers around it. Nothing is taken until the stroke
 * leaves touch slop; then the row takes it when it [claims] the direction, or when the row is
 * [open] already and the stroke closes it. Otherwise the stroke is left alone: a direction the
 * row has nothing for goes to an outer row, a vertical stroke to the list.
 *
 * Once taken, every event of the stroke is consumed until the finger lifts, the still and the
 * purely vertical ones too. The pager and the list wait for a child to let go of a gesture and
 * pick it up with all the distance moved since the touch; a single event left over would turn
 * the tabs under a row that is moving, and the two would fight.
 */
private fun Modifier.rowDrag(
    enabled: Boolean,
    open: () -> Boolean,
    claims: (Float) -> Boolean,
    onStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onStop: (Float) -> Unit,
): Modifier = composed {
    // The node stays while disabled (a drag to reorder turns rows off mid-gesture); removing it
    // would disturb the gesture under way.
    val on by rememberUpdatedState(enabled)
    val isOpen by rememberUpdatedState(open)
    val wants by rememberUpdatedState(claims)
    val start by rememberUpdatedState(onStart)
    val delta by rememberUpdatedState(onDelta)
    val stop by rememberUpdatedState(onStop)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!on) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val tracker = VelocityTracker().apply { addPointerInputChange(down) }
            var moved = Offset.Zero
            while (true) {
                val event = awaitPointerEvent()
                val c = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                // Lifted, or a row inside this one took it.
                if (!c.pressed || c.isConsumed) return@awaitEachGesture
                tracker.addPointerInputChange(c)
                moved += c.positionChange()
                if (abs(moved.y) > slop && abs(moved.y) >= abs(moved.x)) return@awaitEachGesture
                if (abs(moved.x) > slop) {
                    if (!isOpen() && !wants(moved.x)) return@awaitEachGesture
                    c.consume()
                    start()
                    delta(moved.x - sign(moved.x) * slop)
                    break
                }
                // The list or the pager may have taken this move after us.
                awaitPointerEvent(PointerEventPass.Final)
                if (c.isConsumed) return@awaitEachGesture
            }
            var lifted = false
            while (true) {
                val event = awaitPointerEvent()
                // Every pointer of the stroke, so a second finger cannot hand it to the pager.
                event.changes.forEach { it.consume() }
                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) {
                    lifted = true
                    break
                }
                tracker.addPointerInputChange(c)
                val dx = c.position.x - c.previousPosition.x
                if (dx != 0f) delta(dx)
            }
            stop(if (lifted) tracker.calculateVelocity().x else 0f)
        }
    }
}
