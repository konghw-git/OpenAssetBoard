package com.assetboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-press-and-drag reordering for a LazyColumn. The screen keeps its own copy of the order:
 * [onMove] swaps two keys in it (returning false where the move is not allowed, such as across
 * sections), and [onDrop] saves it. The dragged row follows the finger; near either edge the
 * list scrolls under it. A row picked up and let go without moving calls [onHold] instead, when
 * there is one: the long press keeps its other meaning, such as selecting. [onStart] hears the
 * pick-up, before anything moves, and may fold other rows away to carry them along; it answers
 * how far that moved the row's slot up, so the row stays under the finger.
 */
@Stable
class ReorderState internal constructor(
    val list: LazyListState,
    private val scope: CoroutineScope,
    private val edge: Float,
    private val slop: Float,
    private val haptic: HapticFeedback,
) {
    var dragging by mutableStateOf<Any?>(null)
        private set
    /** How far the dragged row sits from its slot in the layout. */
    var offset by mutableFloatStateOf(0f)
        private set
    internal var onMove: (from: Any, to: Any) -> Boolean = { _, _ -> false }
    internal var onDrop: (Any) -> Unit = {}
    internal var onHold: ((Any) -> Unit)? = null
    internal var onStart: (Any) -> Float = { 0f }
    private var scroller: Job? = null
    private var travelled = 0f
    private var moved = false
    private var released: Any? = null
    private var releasedAt = 0L

    /**
     * Whether a click on [key]'s row is only the finger lifting after a long press picked it up.
     * The row's own click sees that lift too, just before or just after [end]; it is not a tap.
     */
    fun ownsClick(key: Any): Boolean =
        dragging == key || (released == key && System.nanoTime() - releasedAt < CLICK_AFTER_RELEASE_NS)

    internal fun start(key: Any) {
        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        dragging = key
        offset = onStart(key)
        travelled = 0f
        moved = false
        scroller?.cancel()
        scroller = scope.launch { autoScroll() }
    }

    internal fun drag(dy: Float) {
        offset += dy
        travelled += kotlin.math.abs(dy)
        swapIfCrossed()
    }

    internal fun end() {
        val key = dragging ?: return
        scroller?.cancel()
        dragging = null
        offset = 0f
        released = key
        releasedAt = System.nanoTime()
        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
        val hold = onHold
        if (hold != null && !moved && travelled < slop) hold(key) else onDrop(key)
    }

    /**
     * Trades places with the row above once the dragged row's top edge passes that row's middle,
     * or with the row below once its bottom edge does: by edges, so a tall row moves as readily
     * as a short one.
     */
    private fun swapIfCrossed() {
        val key = dragging ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        val me = visible.firstOrNull { it.key == key } ?: return
        val top = me.offset + offset
        val bottom = top + me.size
        val above = visible.firstOrNull { it.index == me.index - 1 }?.takeIf { top < it.offset + it.size / 2f }
        val below = visible.firstOrNull { it.index == me.index + 1 }?.takeIf { bottom > it.offset + it.size / 2f }
        val target = above ?: below ?: return
        val down = target === below
        val first = list.firstVisibleItemIndex
        val firstOffset = list.firstVisibleItemScrollOffset
        if (!onMove(key, target.key)) return
        moved = true
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        offset -= if (down) (target.offset + target.size - (me.offset + me.size)).toFloat() else (target.offset - me.offset).toFloat()
        // A move at the top would otherwise make the list follow the row that left the top slot.
        if (me.index == first || target.index == first) list.requestScrollToItem(first, firstOffset)
    }

    private suspend fun autoScroll() {
        while (scope.isActive) {
            withFrameNanos { }
            val key = dragging ?: return
            val info = list.layoutInfo
            val me = info.visibleItemsInfo.firstOrNull { it.key == key } ?: continue
            val top = me.offset + offset
            val bottom = top + me.size
            val start = info.viewportStartOffset + edge
            val end = info.viewportEndOffset - edge
            val speed = when {
                top < start -> -((start - top) / edge).coerceAtMost(1f) * 22f
                bottom > end -> ((bottom - end) / edge).coerceAtMost(1f) * 22f
                else -> 0f
            }
            if (speed != 0f) {
                val moved = list.scrollBy(speed)
                offset += moved
                swapIfCrossed()
            }
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
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(cs.primary)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * How far [key]'s slot moves up when the rows keyed [gone] fold away ([foldedAway]): the heights
 * of the ones above it on screen. What [rememberReorderState]'s onStart answers when it folds
 * rows away to carry them.
 */
fun LazyListState.heightAbove(key: Any, gone: Collection<Any>): Float {
    val info = layoutInfo
    val me = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
    return info.visibleItemsInfo.filter { it.index < me.index && it.key in gone }.sumOf { it.size }.toFloat()
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
    onStart: (Any) -> Float = { 0f },
): ReorderState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    val slop = with(density) { 10.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val state = remember(list) { ReorderState(list, scope, edge, slop, haptic) }
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val hold by rememberUpdatedState(onHold)
    val start by rememberUpdatedState(onStart)
    state.onMove = { a, b -> move(a, b) }
    state.onDrop = { drop(it) }
    state.onHold = if (onHold == null) null else ({ hold?.invoke(it) })
    state.onStart = { start(it) }
    return state
}

/** Long press [key]'s row to pick it up and drag it. */
fun Modifier.reorderable(state: ReorderState, key: Any, enabled: Boolean = true): Modifier =
    if (!enabled) this else pointerInput(state, key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(key) },
            onDrag = { change, amount ->
                change.consume()
                state.drag(amount.y)
            },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
        )
    }
