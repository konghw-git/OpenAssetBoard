package com.assetboard.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/**
 * Places where a sideways stroke always turns the tabs, even over a swipeable row: rows ask
 * [contains] before taking a stroke (see [SwipeActionsBox]).
 */
@Stable
class TabSwipeZones {
    private val zones = HashMap<Any, Rect>()

    fun contains(p: Offset): Boolean = zones.values.any { it.contains(p) }

    internal fun set(key: Any, bounds: Rect?) {
        if (bounds == null) zones.remove(key) else zones[key] = bounds
    }
}

val LocalTabSwipeZones = staticCompositionLocalOf<TabSwipeZones?> { null }

private val ZoneWidth = 28.dp
// The system lets an app take back at most 200dp of each edge from the back gesture.
private val ZoneHeight = 184.dp

/**
 * Two short handles halfway down the left and right edges of a page full of swipeable rows: a
 * stroke that starts on one turns the tabs. Each is a sliver of light against the edge, fading at
 * both ends, that brightens and widens while the pages move ([active]). They take no touches
 * themselves; the system back gesture gives way under them.
 */
@Composable
fun BoxScope.TabSwipeHandles(active: Boolean) {
    val zones = LocalTabSwipeZones.current
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val lit by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 160 else 420), label = "handle")
    val rest = if (dark) Color.White.copy(alpha = 0.22f) else cs.onSurface.copy(alpha = 0.16f)
    for (side in listOf(Alignment.CenterStart, Alignment.CenterEnd)) {
        val key = remember { Any() }
        DisposableEffect(zones, key) { onDispose { zones?.set(key, null) } }
        val left = side == Alignment.CenterStart
        Box(
            Modifier
                .align(side)
                .width(ZoneWidth)
                .height(ZoneHeight)
                .systemGestureExclusion()
                .onGloballyPositioned { zones?.set(key, it.boundsInRoot()) }
                .drawBehind {
                    val w = (3.dp + 1.5.dp * lit).toPx()
                    val h = size.height * (0.40f + 0.08f * lit)
                    val x = if (left) 5.dp.toPx() else size.width - 5.dp.toPx() - w
                    val top = (size.height - h) / 2
                    // A soft glow around the sliver while the pages move.
                    if (lit > 0f) {
                        val glowW = 18.dp.toPx()
                        val gx = if (left) 0f else size.width - glowW
                        drawRoundRect(
                            Brush.horizontalGradient(
                                if (left) listOf(cs.primary.copy(alpha = 0.22f * lit), Color.Transparent) else listOf(Color.Transparent, cs.primary.copy(alpha = 0.22f * lit)),
                                startX = gx, endX = gx + glowW,
                            ),
                            Offset(gx, top - 12.dp.toPx()), Size(glowW, h + 24.dp.toPx()), CornerRadius(glowW),
                        )
                    }
                    val core = androidx.compose.ui.graphics.lerp(rest, cs.primary.copy(alpha = 0.75f), lit)
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.Transparent, core, core, Color.Transparent), startY = top, endY = top + h),
                        Offset(x, top), Size(w, h), CornerRadius(w / 2),
                    )
                },
        )
    }
}
