package com.assetboard.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.assetboard.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pulling a list down past its top makes something new: the list follows the finger at half
 * speed, a hint appears in the space it leaves, and letting go past [threshold] calls [onCreate].
 * Scrolling back up first takes the pull back. While not [enabled] (what it would make is already
 * open, or the list is busy) the list just stops at its top.
 */
@Stable
class PullToCreate internal constructor(
    private val threshold: Float,
    private val haptic: HapticFeedback,
    private val scope: CoroutineScope,
) : NestedScrollConnection {
    var pulled by mutableFloatStateOf(0f)
        private set
    internal var onCreate: () -> Unit = {}
    internal var enabled: () -> Boolean = { true }
    val armed: Boolean get() = pulled >= threshold
    val progress: Float get() = (pulled / threshold).coerceIn(0f, 1f)

    private fun pullBy(dy: Float) {
        val was = armed
        pulled = (pulled + dy).coerceAtLeast(0f)
        if (armed != was) haptic.performHapticFeedback(if (armed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y < 0 && pulled > 0f) {
            val used = maxOf(available.y, -pulled)
            pullBy(used)
            return Offset(0f, used)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Only a finger pulls; a fling that reaches the top just stops there.
        if (source == NestedScrollSource.UserInput && available.y > 0f && enabled()) {
            pullBy(available.y * 0.5f)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (pulled <= 0f) return Velocity.Zero
        if (armed && enabled()) onCreate()
        scope.launch { animate(pulled, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> pulled = v.coerceAtLeast(0f) } }
        return available
    }
}

@Composable
fun rememberPullToCreate(enabled: Boolean = true, onCreate: () -> Unit): PullToCreate {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val state = remember { PullToCreate(threshold, haptic, scope) }
    val create by rememberUpdatedState(onCreate)
    val on by rememberUpdatedState(enabled)
    state.onCreate = { create() }
    state.enabled = { on }
    return state
}

/**
 * The list ([content]) with the pull gesture and [hint] in the space the pull opens above it,
 * saying what letting go will do. The list makes that space itself, by adding [PullToCreate.pulled]
 * to its top padding: moving the list instead would move the very pointer it is reading.
 */
@Composable
fun PullToCreateBox(state: PullToCreate, hint: String, armedHint: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Edit, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.nestedScroll(state)) {
        content()
        PullHint(state, hint, armedHint, icon, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun PullHint(state: PullToCreate, hint: String, armedHint: String, icon: ImageVector, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val extra = AppTheme.extra
    val spin by animateFloatAsState(if (state.armed) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "armed")
    Row(
        modifier
            .padding(top = 14.dp)
            .graphicsLayer {
                val p = state.progress
                alpha = p
                translationY = (state.pulled - 60.dp.toPx()).coerceAtMost(0f) * 0.4f
                scaleX = 0.85f + 0.15f * p
                scaleY = scaleX
            }
            .glass(RoundedCornerShape(50), glass, elevation = 8.dp)
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .graphicsLayer {
                    val s = 0.9f + 0.18f * spin
                    scaleX = s; scaleY = s
                    rotationZ = -20f * (1f - state.progress)
                }
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(extra.heroEnd, extra.heroStart))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = extra.onHero, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        AnimatedContent(state.armed, transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) }, label = "hint") { on ->
            Text(if (on) armedHint else hint, style = MaterialTheme.typography.labelLarge, color = if (on) cs.primary else cs.onSurfaceVariant)
        }
    }
}
