package com.assetboard.ui.todo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.round
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import com.assetboard.data.ChecklistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class ChecklistDrop(val todoId: String, val beforeId: String?)
internal val LocalChecklistDrag = staticCompositionLocalOf<ChecklistDragState?> { null }

/** Root-owned gesture: the item may scroll out of the lazy list without cancelling the drag. */
@Stable
internal class ChecklistDragState(private val scope: CoroutineScope, private val haptic: HapticFeedback, private val edge: Float) {
    var item by mutableStateOf<ChecklistItem?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var drop by mutableStateOf<ChecklistDrop?>(null)
        private set
    var originalBounds = Rect.Zero
        private set
    var grab = Offset.Zero
        private set
    var hostOrigin = Offset.Zero
    var viewport: Rect? = null
    val rows = mutableMapOf<String, Rect>()
    val handles = mutableMapOf<String, Rect>()
    val cards = mutableMapOf<String, Rect>()
    var items: Map<String, ChecklistItem> = emptyMap()
    var allowed: Set<String> = emptySet()
    var onMove: (String, String, String?) -> Unit = { _, _, _ -> }
    var onStart: () -> Unit = {}
    var scroll: suspend (Float) -> Unit = {}
    private var scroller: Job? = null
    private var releasedAt = 0L
    private var released: String? = null

    fun ownsClick(id: String) = item?.id == id || (released == id && System.nanoTime() - releasedAt < 300_000_000L)

    fun sourceAt(point: Offset): ChecklistItem? = handles.entries.firstOrNull { (id, rect) ->
        rect.contains(point) && items[id]?.todoId in allowed
    }?.key?.let(items::get)

    fun start(source: ChecklistItem, point: Offset) {
        originalBounds = rows[source.id] ?: return
        item = source
        pointer = point
        grab = point - originalBounds.topLeft
        onStart()
        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        updateTarget()
        scroller = scope.launch {
            while (isActive && item != null) {
                withFrameNanos { }
                val bounds = viewport ?: continue
                val speed = when {
                    pointer.x !in bounds.left..bounds.right -> 0f
                    pointer.y < bounds.top + edge -> -((bounds.top + edge - pointer.y) / edge).coerceIn(0f, 1f) * 18f
                    pointer.y > bounds.bottom - edge -> ((pointer.y - bounds.bottom + edge) / edge).coerceIn(0f, 1f) * 18f
                    else -> 0f
                }
                if (speed != 0f) scroll(speed)
                updateTarget()
            }
        }
    }

    fun move(point: Offset) { pointer = point; updateTarget() }

    private fun updateTarget() {
        val held = item ?: return
        val owner = cards.entries.firstOrNull { (id, rect) -> id in allowed && rect.contains(pointer) }?.key
        val next = owner?.let {
            val before = rows.entries.filter { (id, _) -> id != held.id && items[id]?.todoId == owner }
                .sortedBy { it.value.top }.firstOrNull { pointer.y < it.value.center.y }?.key
            ChecklistDrop(owner, before)
        }
        if (next != drop && next != null) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        drop = next
    }

    fun finish(cancelled: Boolean) {
        val held = item ?: return
        val target = drop
        scroller?.cancel()
        released = held.id
        releasedAt = System.nanoTime()
        item = null
        drop = null
        if (!cancelled && target != null && held.todoId in allowed && target.todoId in allowed) {
            onMove(held.id, target.todoId, target.beforeId)
        }
    }
}

@Composable
internal fun rememberChecklistDragState(items: List<ChecklistItem>, allowed: Set<String>, scroll: suspend (Float) -> Unit, onStart: () -> Unit = {}, onMove: (String, String, String?) -> Unit): ChecklistDragState {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val state = remember { ChecklistDragState(scope, haptic, edge) }
    state.items = items.associateBy { it.id }
    state.allowed = allowed
    state.scroll = scroll
    state.onStart = onStart
    state.onMove = onMove
    DisposableEffect(state) { onDispose { state.finish(cancelled = true) } }
    return state
}

internal fun Modifier.checklistDragHost(state: ChecklistDragState): Modifier =
    onGloballyPositioned { state.hostOrigin = it.boundsInRoot().topLeft }.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val source = state.sourceAt(down.position + state.hostOrigin) ?: return@awaitEachGesture
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            state.start(source, held.position + state.hostOrigin)
            try {
                var finished = false
                while (true) {
                    // Once picked up, consume before the child list sees a large first move.
                    // Otherwise its scroll detector can steal the gesture on that very frame.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == held.id } ?: break
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        finished = change.previousPressed
                        change.consume()
                        break
                    }
                    state.move(change.position + state.hostOrigin)
                    change.consume()
                }
                state.finish(cancelled = !finished)
            } finally {
                state.finish(cancelled = true)
            }
        }
    }

internal fun Modifier.checklistViewport(state: ChecklistDragState): Modifier = onGloballyPositioned { state.viewport = it.boundsInRoot() }

/** Card bounds decide the destination; the rows inside make room where the item would land. */
internal fun Modifier.checklistTarget(todoId: String): Modifier = composed {
    val state = LocalChecklistDrag.current ?: return@composed this
    DisposableEffect(state, todoId) { onDispose { state.cards.remove(todoId) } }
    onGloballyPositioned { state.cards[todoId] = it.boundsInRoot() }
}

/**
 * [items] of [todoId] as they would stand if the held item were let go now: gone from the card
 * it left, and in at the drop, where its row shows as an empty slot. The other rows slide out of
 * its way ([slideToPlace]), the way todo cards trade places. The drop is read from where rows are
 * laid out, gap included, so the gap only moves on once the finger passes the next row's middle.
 */
internal fun ChecklistDragState?.preview(todoId: String, items: List<ChecklistItem>): List<ChecklistItem> {
    val held = this?.item ?: return items
    val drop = drop ?: return items
    val rest = items.filter { it.id != held.id }
    if (drop.todoId != todoId) return rest
    val at = drop.beforeId?.let { b -> rest.indexOfFirst { it.id == b } }?.takeIf { it >= 0 } ?: rest.size
    return rest.toMutableList().apply { add(at, held) }
}

/**
 * Glides a row from where it was placed to where it is placed now, so rows making room for a
 * dragged item slide rather than jump. Outer modifiers still see the new place at once: the
 * drag reads rows from there.
 */
internal fun Modifier.slideToPlace(): Modifier = composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IntOffset?>(null) }
    val anim = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    onPlaced { c ->
        val at = c.positionInParent().round()
        val was = target
        target = at
        if (was != null && was != at) {
            // Start from where the row is drawn now, so a slide interrupted mid-way stays smooth.
            val from = anim.value + (was - at)
            scope.launch {
                anim.snapTo(from)
                anim.animateTo(IntOffset.Zero, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold))
            }
        }
    }.offset { anim.value }
}

internal fun Modifier.checklistRow(item: ChecklistItem, handle: Boolean = true): Modifier = composed {
    val state = LocalChecklistDrag.current ?: return@composed this
    val color = MaterialTheme.colorScheme.primary
    val held = state.item?.id == item.id
    DisposableEffect(state, item.id) { onDispose { state.rows.remove(item.id); state.handles.remove(item.id) } }
    onGloballyPositioned {
        state.rows[item.id] = it.boundsInRoot()
        if (handle) state.handles[item.id] = it.boundsInRoot()
    }.drawWithContent {
        if (!held) {
            drawContent()
            return@drawWithContent
        }
        // The held item's slot, where it will land: a dashed outline over a faint tint.
        val inset = 1.dp.toPx()
        drawRoundRect(
            color.copy(alpha = 0.08f), topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(10.dp.toPx()),
        )
        drawRoundRect(
            color.copy(alpha = 0.55f), topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    }
}

internal fun Modifier.checklistHandle(id: String): Modifier = composed {
    val state = LocalChecklistDrag.current ?: return@composed this
    onGloballyPositioned { state.handles[id] = it.boundsInRoot() }
}

/**
 * The held item, lifted off the list under the finger: the same node and strip as on the card,
 * a little larger and casting a shadow. Where it lands shows in the lists themselves; the
 * preview only speaks up when there is nowhere to land.
 */
@Composable
internal fun ChecklistDragPreview(state: ChecklistDragState) {
    val item = state.item ?: return
    val density = LocalDensity.current
    val shape = RoundedCornerShape(12.dp)
    val cs = MaterialTheme.colorScheme
    val lift = remember(item.id) { Animatable(0f) }
    LaunchedEffect(item.id) { lift.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
    Column(
        Modifier.zIndex(10f).offset {
            val p = state.pointer - state.grab - state.hostOrigin
            IntOffset(p.x.roundToInt(), p.y.roundToInt())
        }.width(with(density) { state.originalBounds.width.toDp() }),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val s = 1f + 0.04f * lift.value
                    scaleX = s; scaleY = s
                    rotationZ = -1.2f * lift.value
                }
                .shadow(6.dp + 10.dp * lift.value, shape, spotColor = cs.primary.copy(alpha = 0.4f))
                .background(cs.surfaceContainerHigh, shape)
                .border(1.dp, cs.primary.copy(alpha = 0.35f), shape)
                .heightIn(min = with(density) { state.originalBounds.height.toDp() })
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).border(1.4.dp, if (item.done) cs.primary else cs.outline, CircleShape))
            Spacer(Modifier.width(12.dp))
            Text(
                item.text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
            )
        }
        if (state.drop == null) {
            Text(
                "拖到检查项之间或其他待办上",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onPrimary,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp).clip(RoundedCornerShape(50)).background(cs.primary).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
