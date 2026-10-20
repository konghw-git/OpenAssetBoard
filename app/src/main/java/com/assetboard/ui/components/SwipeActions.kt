package com.assetboard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Keeps one row of a list open at a time; opening another closes the last. */
@Stable
class SwipeGroup {
    var open by mutableStateOf<Any?>(null)
        internal set

    fun closeAll() { open = null }
}

@Composable
fun rememberSwipeGroup(): SwipeGroup = remember { SwipeGroup() }

/** A button revealed by swiping right. */
class SwipeAction(val label: String, val icon: ImageVector, val color: Color, val onClick: () -> Unit)

private val ActionWidth = 66.dp
private val DeleteWidth = 74.dp
private val Gap = 8.dp
private const val FLING = 900f

/**
 * One list row with the app's swipe gestures, the same for todos and notes:
 *
 * - left reveals 删除; swiping on past the threshold, or tapping it, turns the card to dust and
 *   calls [onDelete];
 * - right reveals [actions]; swiping on past the threshold runs the first one.
 *
 * An open row closes when another opens, when it is tapped, or when [group] closes all.
 */
@Composable
fun SwipeActionsBox(
    key: Any,
    group: SwipeGroup?,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    actions: List<SwipeAction> = emptyList(),
    onDelete: (() -> Unit)? = null,
    enabled: Boolean = true,
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
    val first by rememberUpdatedState(actions.firstOrNull())

    val gap = with(density) { Gap.toPx() }
    val startOpen = if (actions.isEmpty()) 0f else with(density) { (ActionWidth + Gap).toPx() } * actions.size + gap
    val endOpen = if (onDelete == null) 0f else with(density) { (DeleteWidth + Gap).toPx() } + gap
    val startFull = maxOf(startOpen + with(density) { 64.dp.toPx() }, width * 0.58f)
    val endFull = maxOf(endOpen + with(density) { 64.dp.toPx() }, width * 0.52f)
    val x = offset.value
    val armedStart = x > startFull && actions.isNotEmpty()
    val armedEnd = -x > endFull && onDelete != null
    val hidden = dust?.isHidden(key) == true

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

    val drag = rememberDraggableState { d ->
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

    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat() }
            .graphicsLayer { alpha = if (hidden) 0f else 1f },
    ) {
        if (x > 0.5f) {
            StartActions(actions, x, startOpen, armedStart, shape, Modifier.matchParentSize()) { a ->
                settle(0f)
                a.onClick()
            }
        } else if (x < -0.5f) {
            DeleteAction(-x, endOpen, armedEnd, shape, Modifier.matchParentSize()) { runDelete() }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    drag, Orientation.Horizontal,
                    enabled = enabled && (actions.isNotEmpty() || onDelete != null),
                    onDragStarted = {
                        dragging = true
                        if (group != null && group.open != key) group.open = key
                    },
                    onDragStopped = { v ->
                        dragging = false
                        val now = offset.value
                        when {
                            now < 0 && (-now > endFull || (v < -FLING * 3 && -now > endOpen)) && onDelete != null -> runDelete()
                            now < 0 && (-now > endOpen * 0.5f || v < -FLING) && v < FLING -> settle(-endOpen)
                            now > 0 && now > startFull && actions.isNotEmpty() -> runFirst()
                            now > 0 && (now > startOpen * 0.5f || v > FLING) && v > -FLING -> settle(startOpen)
                            else -> settle(0f)
                        }
                    },
                )
                .dissolvable(key),
        ) {
            content()
            // While open, a tap on the card closes it instead of opening the item.
            if (abs(x) > 0.5f && !dragging) {
                Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { settle(0f) } })
            }
        }
    }
}

@Composable
private fun StartActions(actions: List<SwipeAction>, x: Float, open: Float, armed: Boolean, shape: RoundedCornerShape, modifier: Modifier, onClick: (SwipeAction) -> Unit) {
    val density = LocalDensity.current
    val progress = (x / open).coerceIn(0f, 1f)
    val gap = with(density) { Gap.toPx() }
    val fill by animateFloatAsState(if (armed) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "armed")
    val base = with(density) { ActionWidth.toPx() }
    // Armed, the first button stretches over the whole revealed space and the rest fade.
    val firstWidth = base + (x - gap * 2 - base) * fill
    Row(modifier.padding(start = Gap), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
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
                        alpha = if (i == 0) progress else progress * (1f - fill)
                    },
            ) { onClick(a) }
        }
    }
}

@Composable
private fun DeleteAction(x: Float, open: Float, armed: Boolean, shape: RoundedCornerShape, modifier: Modifier, onClick: () -> Unit) {
    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    val progress = (x / open).coerceIn(0f, 1f)
    val gap = with(density) { Gap.toPx() }
    val base = with(density) { DeleteWidth.toPx() }
    val pop by animateFloatAsState(if (armed) 1.12f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "pop")
    // The button fills the space the card left, so a long swipe reads as one red stretch.
    val w = (x - gap * 2).coerceAtLeast(base * progress).coerceAtLeast(1f)
    Box(modifier.padding(end = Gap), contentAlignment = Alignment.CenterEnd) {
        ActionButton(
            "删除", Icons.Outlined.Delete, cs.error, shape,
            Modifier
                .width(with(density) { w.toDp() })
                .fillMaxHeight()
                .graphicsLayer {
                    alpha = progress
                    val s = 0.7f + 0.3f * progress
                    scaleY = s
                },
            iconScale = pop,
            onClick = onClick,
        )
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, color: Color, shape: RoundedCornerShape, modifier: Modifier, iconScale: Float = 1f, onClick: () -> Unit) {
    val on = Color.White
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.92f), color)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, tint = on, modifier = Modifier.size(21.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
            Text(label, color = on, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
