package com.assetboard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Long-press-and-drag reordering for a LazyColumn. The screen keeps its own copy of the order:
 * [onMove] moves one key to another's place in it (returning false where the move is not allowed,
 * such as across sections), and [onDrop] saves it. The dragged row follows the finger; near
 * either edge the list scrolls under it. A row picked up and let go without moving calls [onHold]
 * instead, when there is one: the long press keeps its other meaning, such as selecting. [onStart]
 * hears the pick-up, before anything moves, and may fold other rows away to carry them along.
 *
 * The row's place on screen is worked out from the finger's travel and where the list lays its
 * slot out now, never kept as a running sum: rows folding away, an editor closing above, a
 * scroll or a move all leave it under the finger without any bookkeeping. Edge scrolling never
 * takes the slot itself out of view, because a lazy list drops what it does not show, and with
 * it the gesture.
 *
 * Where [onNest] is set, the row can also go into the one above it: slid right past [nestArm]
 * while held, it shows [nestInto] (the row above, when [canNest] allows it), and let go there it
 * calls [onNest] instead of [onDrop]. Only a sideways slide does that: moving up or down takes the
 * slide back, so a reorder stays a reorder however long it travels or rests.
 */
@Stable
class ReorderState internal constructor(
    val list: LazyListState,
    private val scope: CoroutineScope,
    private val edge: Float,
    private val slop: Float,
    private val haptic: HapticFeedback,
    private val nestArm: Float,
    private val nestMax: Float,
    private val maxSpeed: Float,
) {
    var dragging by mutableStateOf<Any?>(null)
        private set
    /** How far right the dragged row has slid, toward going into the row above; 0 without [onNest]. */
    var nestOffset by mutableFloatStateOf(0f)
        private set
    /** The row the dragged one goes into if let go now. */
    var nestInto by mutableStateOf<Any?>(null)
        private set
    /** The row just let go, gliding from where the finger left it into its slot. */
    var settling by mutableStateOf<Any?>(null)
        private set
    internal var onMove: (from: Any, to: Any) -> Boolean = { _, _ -> false }
    internal var onDrop: (Any) -> Unit = {}
    internal var onHold: ((Any) -> Unit)? = null
    internal var onStart: (Any) -> Unit = {}
    internal var canNest: (row: Any, into: Any) -> Boolean = { _, _ -> false }
    internal var onNest: ((row: Any, into: Any) -> Unit)? = null
    /** A header pinned to the top of the list: the top scroll zone starts under it. */
    internal var stickyKey: Any? = null
    private var scroller: Job? = null
    private var settler: Job? = null
    private val settle = Animatable(0f)
    /** The finger's travel since the pick-up. */
    private var travel by mutableFloatStateOf(0f)
    /** Where the row's slot was at the pick-up, and where it was last seen laid out. */
    private var startSlot = 0f
    private var lastSlot = 0f
    private var grabY = 0f
    private var travelled = 0f
    private var moved = false
    /** The layout a move was made on: the next move waits for the one that shows it. */
    private var movedOn: LazyListLayoutInfo? = null
    private var released: Any? = null
    private var releasedAt = 0L

    /** How far the dragged row sits from its slot in the layout. */
    val offset: Float
        get() {
            val key = dragging ?: return 0f
            val slot = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.toFloat() ?: lastSlot
            return startSlot + travel - slot
        }

    /** How far [key]'s row is drawn from its slot: following the finger, or settling after it. */
    fun translationOf(key: Any): Float = when (key) {
        dragging -> offset
        settling -> settle.value
        else -> 0f
    }

    /** Whether [key]'s row is held or still settling, and so drawn over its neighbours. */
    fun isLifted(key: Any): Boolean = dragging == key || settling == key

    /**
     * Whether a click on [key]'s row is only the finger lifting after a long press picked it up.
     * The row's own click sees that lift too, just before or just after [end]; it is not a tap.
     */
    fun ownsClick(key: Any): Boolean =
        dragging == key || (released == key && System.nanoTime() - releasedAt < CLICK_AFTER_RELEASE_NS)

    internal fun start(key: Any, point: Offset = Offset.Zero) {
        val me = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        settler?.cancel()
        settling = null
        startSlot = me.offset.toFloat()
        lastSlot = startSlot
        travel = 0f
        grabY = point.y
        travelled = 0f
        moved = false
        movedOn = null
        nestOffset = 0f
        nestInto = null
        dragging = key
        onStart(key)
        scroller?.cancel()
        scroller = scope.launch { autoScroll() }
    }

    internal fun drag(dx: Float, dy: Float) {
        if (dragging == null) return
        travel += dy
        travelled += abs(dx) + abs(dy)
        if (onNest != null) {
            // Up or down takes the slide back: only a sideways move goes into the row above.
            val back = if (abs(dy) > abs(dx)) abs(dy) * 0.6f else 0f
            nestOffset = (nestOffset + dx - back).coerceIn(0f, nestMax)
        }
        swapIfCrossed()
        updateNest()
    }

    internal fun end(cancelled: Boolean = false) {
        val key = dragging ?: return
        scroller?.cancel()
        // A cancelled drag releases the row; it must never turn into a merge.
        val into = nestInto.takeUnless { cancelled }
        val from = offset
        dragging = null
        travel = 0f
        nestOffset = 0f
        nestInto = null
        released = key
        releasedAt = System.nanoTime()
        haptic.performHapticFeedback(if (into != null) HapticFeedbackType.Confirm else HapticFeedbackType.GestureEnd)
        if (into == null && from != 0f) {
            settling = key
            settler = scope.launch {
                settle.snapTo(from)
                settle.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                settling = null
            }
        }
        val hold = onHold
        val nest = onNest
        when {
            hold != null && !moved && travelled < slop -> hold(key)
            into != null && nest != null -> nest(key, into)
            else -> onDrop(key)
        }
    }

    /** The right-slide arms the row above, with hysteresis so a finger on its threshold does not flicker. */
    private fun updateNest() {
        val key = dragging ?: return
        if (onNest == null) return
        val before = nestInto
        val above = above(key)
        nestInto = when {
            nestOffset >= nestArm -> above?.takeIf { canNest(key, it) }
            nestOffset < nestArm * 2 / 3 -> null
            else -> before?.takeIf { it == above }
        }
        if (nestInto != before) haptic.performHapticFeedback(if (nestInto != null) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
    }

    /** The key of the row right above [key]'s slot, if it is on screen. */
    fun above(key: Any): Any? {
        val visible = list.layoutInfo.visibleItemsInfo
        val me = visible.firstOrNull { it.key == key } ?: return null
        // Rows carried along stay in the lazy list with zero height to keep the gesture alive.
        return visible.lastOrNull { it.index < me.index && it.size > 0 }?.key
    }

    /**
     * Takes the place of the row above once the dragged row's top edge passes that row's middle,
     * or of the row below once its bottom edge does: by edges, so a tall row moves as readily as
     * a short one. One move per layout: the next waits until the list has laid this one out.
     */
    private fun swapIfCrossed() {
        val key = dragging ?: return
        val info = list.layoutInfo
        if (info === movedOn) return
        val visible = info.visibleItemsInfo
        val me = visible.firstOrNull { it.key == key } ?: return
        lastSlot = me.offset.toFloat()
        val top = startSlot + travel
        val bottom = top + me.size
        val above = visible.lastOrNull { it.index < me.index && it.size > 0 }?.takeIf { top < it.offset + it.size / 2f }
        val below = visible.firstOrNull { it.index > me.index && it.size > 0 }?.takeIf { bottom > it.offset + it.size / 2f }
        val target = above ?: below ?: return
        // Past a tall row near an edge the slot would land off screen, where the list drops it and
        // the gesture with it: wait for the edge scroll to bring the row in first.
        val slot = if (target === above) target.offset else target.offset + target.size - me.size
        if (slot >= info.viewportEndOffset - 1 || slot + me.size <= info.viewportStartOffset + 1) return
        val first = list.firstVisibleItemIndex
        val firstOffset = list.firstVisibleItemScrollOffset
        if (!onMove(key, target.key)) return
        movedOn = info
        moved = true
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        // A move at the top would otherwise make the list follow the row that left the top slot.
        if (me.index == first || target.index == first) list.requestScrollToItem(first, firstOffset)
    }

    /**
     * Scrolls while the finger is near an edge, faster the nearer it is and the longer it waits
     * there, but never so far that the dragged row's slot leaves the screen: the slot catches up
     * through moves, one per frame, and the scroll waits for it.
     */
    private suspend fun autoScroll() {
        var waited = 0
        while (scope.isActive) {
            withFrameNanos { }
            val key = dragging ?: return
            val info = list.layoutInfo
            val me = info.visibleItemsInfo.firstOrNull { it.key == key } ?: continue
            lastSlot = me.offset.toFloat()
            var top = info.viewportStartOffset.toFloat()
            stickyKey?.let { k ->
                info.visibleItemsInfo.firstOrNull { it.key == k && it.offset <= top + 1 }?.let { top = (it.offset + it.size).toFloat() }
            }
            val bottom = info.viewportEndOffset.toFloat()
            val finger = startSlot + travel + grabY
            val pull = when {
                finger < top + edge -> -(top + edge - finger) / edge
                finger > bottom - edge -> (finger - bottom + edge) / edge
                else -> 0f
            }.coerceIn(-1f, 1f)
            if (pull == 0f) {
                waited = 0
            } else {
                waited++
                var speed = pull * maxSpeed * (0.35f + 0.65f * (waited / 40f).coerceAtMost(1f))
                speed = if (speed < 0) maxOf(speed, -(info.viewportEndOffset - me.offset - 1f).coerceAtLeast(0f))
                else minOf(speed, (me.offset + me.size - info.viewportStartOffset - 1f).coerceAtLeast(0f))
                if (speed != 0f) list.scrollBy(speed)
            }
            swapIfCrossed()
            updateNest()
        }
    }
}

private const val CLICK_AFTER_RELEASE_NS = 300_000_000L

/**
 * The rows carried along with a held one, peeking out under it as a little stack: up to two
 * cards, each a little narrower and lower. [side] and [top] are the held card's inset in the
 * row, [corner] its corner radius.
 */
fun DrawScope.stackUnder(carried: Int, color: Color, side: Float, top: Float, corner: Float) {
    val n = minOf(carried, 2)
    for (k in n downTo 1) {
        val inset = side + 10.dp.toPx() * k
        drawRoundRect(
            color.copy(alpha = 0.10f + 0.06f * (n - k)),
            topLeft = Offset(inset, top + 5.dp.toPx() * k),
            size = Size(size.width - inset * 2, size.height - top * 2),
            cornerRadius = CornerRadius(corner),
        )
    }
}

/** How many rows are moving together, on the corner of the one held. */
@Composable
fun CarryBadge(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = cs.onPrimary,
        maxLines = 1,
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(cs.primary)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * A row carried along by another: still in the list, so the gesture holding the other goes on
 * (a list that loses a row mid-gesture cancels it), but of no height and not drawn.
 */
fun Modifier.foldedAway(): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, 0) {}
}

@Composable
fun rememberReorderState(
    list: LazyListState,
    onMove: (from: Any, to: Any) -> Boolean,
    onDrop: (Any) -> Unit,
    onHold: ((Any) -> Unit)? = null,
    onStart: (Any) -> Unit = {},
    canNest: (row: Any, into: Any) -> Boolean = { _, _ -> false },
    onNest: ((row: Any, into: Any) -> Unit)? = null,
    stickyKey: Any? = null,
): ReorderState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val slop = with(density) { 10.dp.toPx() }
    val nestArm = with(density) { NestArm.toPx() }
    val nestMax = with(density) { NestMax.toPx() }
    val maxSpeed = with(density) { 9.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val state = remember(list) { ReorderState(list, scope, edge, slop, haptic, nestArm, nestMax, maxSpeed) }
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val hold by rememberUpdatedState(onHold)
    val start by rememberUpdatedState(onStart)
    val mayNest by rememberUpdatedState(canNest)
    val nest by rememberUpdatedState(onNest)
    state.onMove = { a, b -> move(a, b) }
    state.onDrop = { drop(it) }
    state.onHold = if (onHold == null) null else ({ hold?.invoke(it) })
    state.onStart = { start(it) }
    state.canNest = { a, b -> mayNest(a, b) }
    state.onNest = if (onNest == null) null else ({ a, b -> nest?.invoke(a, b) })
    state.stickyKey = stickyKey
    return state
}

/** How far right a held row slides before it goes into the row above, and the most it slides. */
private val NestArm = 56.dp
private val NestMax = 72.dp

/** Long press [key]'s row to pick it up and drag it. */
fun Modifier.reorderable(state: ReorderState, key: Any, enabled: Boolean = true): Modifier =
    if (!enabled) this else pointerInput(state, key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(key, it) },
            onDrag = { change, amount ->
                change.consume()
                state.drag(amount.x, amount.y)
            },
            onDragEnd = { state.end() },
            onDragCancel = { state.end(cancelled = true) },
        )
    }
