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
 * list scrolls under it.
 */
@Stable
class ReorderState internal constructor(
    val list: LazyListState,
    private val scope: CoroutineScope,
    private val edge: Float,
    private val haptic: HapticFeedback,
) {
    var dragging by mutableStateOf<Any?>(null)
        private set
    /** How far the dragged row sits from its slot in the layout. */
    var offset by mutableFloatStateOf(0f)
        private set
    internal var onMove: (from: Any, to: Any) -> Boolean = { _, _ -> false }
    internal var onDrop: (Any) -> Unit = {}
    private var scroller: Job? = null

    internal fun start(key: Any) {
        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        dragging = key
        offset = 0f
        scroller?.cancel()
        scroller = scope.launch { autoScroll() }
    }

    internal fun drag(dy: Float) {
        offset += dy
        swapIfCrossed()
    }

    internal fun end() {
        val key = dragging ?: return
        scroller?.cancel()
        dragging = null
        offset = 0f
        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
        onDrop(key)
    }

    private fun swapIfCrossed() {
        val key = dragging ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        val me = visible.firstOrNull { it.key == key } ?: return
        val center = me.offset + offset + me.size / 2f
        val target = visible.firstOrNull { it.key != key && center > it.offset && center < it.offset + it.size } ?: return
        // Only once the dragged row's middle is past the target's middle, so rows do not flicker.
        val down = target.index > me.index
        val mid = target.offset + target.size / 2f
        if (down && center < mid || !down && center > mid) return
        val first = list.firstVisibleItemIndex
        val firstOffset = list.firstVisibleItemScrollOffset
        if (!onMove(key, target.key)) return
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
fun rememberReorderState(list: LazyListState, onMove: (from: Any, to: Any) -> Boolean, onDrop: (Any) -> Unit): ReorderState {
    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val state = remember(list) { ReorderState(list, scope, edge, haptic) }
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    state.onMove = { a, b -> move(a, b) }
    state.onDrop = { drop(it) }
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
