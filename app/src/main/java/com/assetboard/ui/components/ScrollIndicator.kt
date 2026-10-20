package com.assetboard.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long the bar stays after the content stops, before it fades. */
private const val LINGER_MS = 900L
private val BarWidth = 3.dp
private val EdgeInset = 3.dp
private val EndInset = 6.dp
private val MinThumb = 28.dp

/**
 * Where the viewport sits in the content: its top and its length, both as fractions of the
 * whole. Null when everything fits and there is nothing to show.
 */
private class Window(val start: Float, val length: Float)

/**
 * [verticalScroll] with the app's scroll bar: a slim thumb on the right edge, showing how far
 * down the page is and how much of it fits, while it moves and for a moment after.
 */
fun Modifier.verticalScrollWithIndicator(state: ScrollState): Modifier =
    scrollIndicator(state) {
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE || state.viewportSize <= 0) null
        else {
            val total = (state.viewportSize + max).toFloat()
            Window(state.value / total, state.viewportSize / total)
        }
    }.verticalScroll(state)

/**
 * The same bar over a lazy list. Rows off screen are not measured, so the content's length is
 * an estimate from the ones that are: good enough for a bar that only says roughly where.
 */
fun Modifier.scrollIndicator(state: LazyListState): Modifier =
    scrollIndicator(state) {
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        if (items.isEmpty() || viewport <= 0f) null
        else {
            val each = items.sumOf { it.size }.toFloat() / items.size + info.mainAxisItemSpacing
            val total = each * info.totalItemsCount + info.beforeContentPadding + info.afterContentPadding
            if (total <= viewport + 1f || (!state.canScrollForward && !state.canScrollBackward)) null
            else {
                // Pinned to the ends when the list is at an end, so the bar never stops short.
                val start = when {
                    !state.canScrollBackward -> 0f
                    !state.canScrollForward -> 1f - viewport / total
                    else -> (state.firstVisibleItemIndex * each + state.firstVisibleItemScrollOffset) / total
                }
                Window(start, viewport / total)
            }
        }
    }

private fun Modifier.scrollIndicator(state: ScrollableState, window: () -> Window?): Modifier = composed {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val color = if (dark) Color.White.copy(alpha = 0.40f) else cs.onSurface.copy(alpha = 0.38f)
    val moving = state.isScrollInProgress
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(moving) {
        if (moving) shown = true
        else {
            delay(LINGER_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 120 else 420), label = "scrollbar")
    drawWithContent {
        drawContent()
        if (alpha > 0f) window()?.let { drawBar(it, color.copy(alpha = color.alpha * alpha)) }
    }
}

private fun ContentDrawScope.drawBar(w: Window, color: Color) {
    val inset = EndInset.toPx()
    val track = size.height - inset * 2
    if (track <= 0f) return
    val length = (track * w.length).coerceIn(MinThumb.toPx().coerceAtMost(track), track)
    val top = inset + (track - length) * (w.start / (1f - w.length).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    val width = BarWidth.toPx()
    drawRoundRect(
        color,
        topLeft = Offset(size.width - EdgeInset.toPx() - width, top),
        size = Size(width, length),
        cornerRadius = CornerRadius(width / 2),
    )
}
