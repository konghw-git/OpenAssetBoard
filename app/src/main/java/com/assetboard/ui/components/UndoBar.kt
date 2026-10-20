package com.assetboard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** A short message, optionally with an undo (or another [action]) offered for [UNDO_MS]. */
data class UndoEvent(val text: String, val action: String = "撤销", val undo: (suspend () -> Unit)? = null)

const val UNDO_MS = 5_000L

/** Snackbar content that knows how long its action stays, for the countdown ring. */
class TimedVisuals(
    override val message: String,
    override val actionLabel: String?,
    val millis: Long,
) : SnackbarVisuals {
    override val withDismissAction = false
    override val duration = SnackbarDuration.Indefinite
}

/**
 * Shows [events] one at a time; a newer one replaces the one on screen. Undo stays offered for
 * five seconds, plain messages for three.
 */
@Composable
fun UndoMessages(events: Flow<UndoEvent>, host: SnackbarHostState, onUndo: (UndoEvent) -> Unit) {
    val undo by rememberUpdatedState(onUndo)
    LaunchedEffect(events, host) {
        events.collectLatest { e ->
            val millis = if (e.undo != null) UNDO_MS else 3_000L
            val result = withTimeoutOrNull(millis) {
                host.showSnackbar(TimedVisuals(e.text, if (e.undo != null) e.action else null, millis))
            }
            if (result == null) host.currentSnackbarData?.dismiss()
            if (result == SnackbarResult.ActionPerformed) undo(e)
        }
    }
}

/**
 * The app's snackbars as a small frosted capsule rather than a full-width bar, so the list stays
 * readable under it. Swipe it sideways to dismiss.
 */
@Composable
fun GlassSnackbarHost(host: SnackbarHostState, modifier: Modifier = Modifier, backdrop: Backdrop? = null) {
    SnackbarHost(host, modifier) { data -> GlassSnackbar(data, backdrop) }
}

@Composable
private fun GlassSnackbar(data: SnackbarData, backdrop: Backdrop?) {
    val cs = MaterialTheme.colorScheme
    val style = glass
    val shape = RoundedCornerShape(50)
    val visuals = data.visuals
    val millis = (visuals as? TimedVisuals)?.millis
    val left = remember(data) { Animatable(1f) }
    LaunchedEffect(data) { if (millis != null) left.animateTo(0f, tween(millis.toInt(), easing = LinearEasing)) }
    var dx by remember(data) { mutableFloatStateOf(0f) }
    val dismissAt = with(LocalDensity.current) { 72.dp.toPx() }
    Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .graphicsLayer {
                    translationX = dx
                    alpha = 1f - (abs(dx) / (dismissAt * 2)).coerceAtMost(0.8f)
                }
                .pointerInput(data) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (abs(dx) > dismissAt) data.dismiss() else dx = 0f },
                        onDragCancel = { dx = 0f },
                    ) { change, amount ->
                        change.consume()
                        dx += amount
                    }
                }
                .widthIn(max = 380.dp)
                .heightIn(min = 46.dp)
                .frosted(backdrop, shape, style, elevation = 12.dp)
                .padding(start = 18.dp, end = if (visuals.actionLabel != null) 6.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 12.dp),
            )
            val action = visuals.actionLabel
            if (action != null) {
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { data.performAction() }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (millis != null) {
                        val ring = cs.primary
                        val track = cs.primary.copy(alpha = 0.16f)
                        Canvas(Modifier.size(15.dp)) {
                            val w = 2.dp.toPx()
                            val s = Size(size.width - w, size.height - w)
                            val o = Offset(w / 2, w / 2)
                            drawArc(track, 0f, 360f, false, o, s, style = Stroke(w))
                            drawArc(ring, -90f, 360f * left.value, false, o, s, style = Stroke(w, cap = StrokeCap.Round))
                        }
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(action, style = MaterialTheme.typography.labelLarge, color = cs.primary)
                }
            }
        }
    }
}
