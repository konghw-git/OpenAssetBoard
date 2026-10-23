package com.assetboard.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
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
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * The app's motion vocabulary. Everything that moves picks from here, so a card folding, a page
 * arriving and a thumb sliding share one hand: a fast start, a long soft settle.
 */
object Motion {
    /** Material's emphasized curve: leaves at once, lands softly. For anything that moves on screen. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** For things entering: nearly all of the movement in the first third. */
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** For things leaving: a quick, quiet exit. */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT = 180
    const val MEDIUM = 320
    const val LONG = 480

    /** A spring with no overshoot, for sizes and positions that must land exactly. */
    fun <T> soft(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    /** A spring with a little give, for things picked up and set down. */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium)
    /** A quick spring for small controls. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
}

/**
 * Fades and lifts content in on first composition, [index] steps of 40 ms apart, so a page of
 * cards arrives as a cascade; it also grows the last two percent. Content composed later (while
 * scrolling) still appears, without the delay, and settles quickly.
 */
fun Modifier.appear(index: Int, enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceIn(0, 8) * 40).toLong())
        progress.animateTo(1f, tween(Motion.LONG, easing = Motion.Decelerate))
    }
    val lift = 16.dp
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * lift.toPx()
        val s = 0.98f + 0.02f * p
        scaleX = s
        scaleY = s
    }
}

/**
 * Shrinks a little while pressed and springs back with a touch of give on release; a pointer
 * hovering over it (a desktop, a stylus) lifts it a hair instead.
 */
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.97f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        when { isPressed -> pressed; hovered || focused -> 1.015f; else -> 1f },
        if (isPressed) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh) else Motion.bouncy(),
        label = "press",
    )
    hoverable(source).graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * A single band of light that crosses the content from the left once, [delayMs] after it
 * appears: the gloss on a hero card as the page settles. Draws nothing once it has passed.
 */
fun Modifier.shine(delayMs: Int = 350, durationMs: Int = 1200, alpha: Float = 0.16f): Modifier = composed {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        t.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }
    drawWithContent {
        drawContent()
        val p = t.value
        if (p <= 0f || p >= 1f) return@drawWithContent
        val band = size.width * 0.38f
        val x = -band + (size.width + band * 2) * p
        val a = alpha * sin(p * PI).toFloat()
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = a), Color.Transparent),
                start = Offset(x, 0f),
                end = Offset(x + band, size.height),
            ),
        )
    }
}

/**
 * Show the real amount immediately. Only subsequent updates interpolate, with a short duration.
 * With [countUp] the first showing rolls up from zero instead, over a longer, decelerating run:
 * the page's one figure arriving.
 */
@Composable
fun animatedCents(target: Long, durationMs: Int = 420, countUp: Boolean = false): Long {
    var shown by remember { mutableLongStateOf(if (countUp) 0L else target) }
    var first by remember { mutableStateOf(countUp) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) {
        val from = shown
        if (from == target) return@LaunchedEffect
        val opening = first
        first = false
        progress.snapTo(0f)
        progress.animateTo(1f, if (opening) tween(1_000, delayMillis = 120, easing = Motion.Decelerate) else tween(durationMs, easing = Motion.Emphasized)) {
            shown = (from.toDouble() * (1.0 - value) + target.toDouble() * value).toLong()
        }
        shown = target
    }
    return shown
}

/**
 * Tilts the content toward the finger while it is held, as a card on a table would, and springs
 * flat again on release. It only watches the pointer, so taps and drags inside still work; a
 * drag the list takes for a scroll lets the card settle at once.
 */
fun Modifier.tilt(maxDegrees: Float = 4f): Modifier = composed {
    var target by remember { mutableStateOf(Offset.Zero) }
    val spec: SpringSpec<Float> = if (target == Offset.Zero) Motion.bouncy() else Motion.snappy()
    val x by animateFloatAsState(target.x, spec, label = "tiltX")
    val y by animateFloatAsState(target.y, spec, label = "tiltY")
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            fun at(p: Offset) {
                target = Offset(
                    (p.x / size.width * 2f - 1f).coerceIn(-1f, 1f),
                    (p.y / size.height * 2f - 1f).coerceIn(-1f, 1f),
                )
            }
            at(down.position)
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed || c.isConsumed) break
                at(c.position)
            }
            target = Offset.Zero
        }
    }.graphicsLayer {
        cameraDistance = 16f * density
        rotationY = x * maxDegrees
        rotationX = -y * maxDegrees
    }
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
            (slideInVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { it } + fadeIn(tween(200, delayMillis = 60))) togetherWith
                (slideOutVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { -it } + fadeOut(tween(160))) using
                SizeTransform(clip = true)
        },
        contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart,
        label = "swap",
    ) { t ->
        Text(t, style = style, color = color, textAlign = textAlign, maxLines = 1)
    }
}
