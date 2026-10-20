package com.assetboard.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
 * there is one: the long press keeps its other meaning, such as selecting.
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
    private var scroller: Job? = null
    private var travelled = 0f
    private var moved = false

    internal fun start(key: Any) {
        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        dragging = key
        offset = 0f
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

@Composable
fun rememberReorderState(
    list: LazyListState,
    onMove: (from: Any, to: Any) -> Boolean,
    onDrop: (Any) -> Unit,
    onHold: ((Any) -> Unit)? = null,
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
    state.onMove = { a, b -> move(a, b) }
    state.onDrop = { drop(it) }
    state.onHold = if (onHold == null) null else ({ hold?.invoke(it) })
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
