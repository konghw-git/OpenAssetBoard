package com.assetboard.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Fades and lifts content in on first composition, [index] steps of 45 ms apart, so a page of
 * cards arrives as a cascade. Content composed later (scrolling) appears without the delay.
 */
fun Modifier.appear(index: Int, enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceIn(0, 10) * 45).toLong())
        progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    val lift = 18.dp
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * lift.toPx()
    }
}

/** Shrinks a little while pressed, springing back on release. */
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.97f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * An amount that counts toward [target]: from zero on first show, then from the previous value
 * whenever it changes.
 */
@Composable
fun animatedCents(target: Long, durationMs: Int = 900): Long {
    var shown by remember { mutableLongStateOf(0L) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) {
        val from = shown
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) {
            shown = from + ((target - from) * value.toDouble()).toLong()
        }
        shown = target
    }
    return shown
}

/**
 * Text that changes when a view switches: it rolls like a counter, the old value leaving through
 * the top of its own line as the new one comes up from below, clipped to that line so the two
 * never stack. Equal text never animates.
 */
@Composable
fun SwapText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
) {
    AnimatedContent(
        text,
        modifier,
        transitionSpec = {
            (slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200, delayMillis = 60))) togetherWith
                (slideOutVertically(tween(300, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(160))) using
                SizeTransform(clip = true)
        },
        contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart,
        label = "swap",
    ) { t ->
        Text(t, style = style, color = color, textAlign = textAlign, maxLines = 1)
    }
}
