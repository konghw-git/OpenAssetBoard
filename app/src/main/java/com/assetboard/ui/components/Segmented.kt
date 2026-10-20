package com.assetboard.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class SegmentStyle {
    /** A light track with a raised card-coloured thumb: the default choice between views. */
    SOFT,
    /** The thumb in the theme colour: the one control a page is about. */
    SOLID,
    /** No track, a faintly tinted thumb: a secondary choice that should not compete. */
    TINT,
}

/**
 * One choice out of a few, as a pill whose thumb slides to the picked option on a spring. The
 * labels take the picked colour exactly where the thumb covers them, so the colour moves with it
 * rather than switching at the end; every label keeps one weight so nothing shifts sideways, and a
 * pick gives a light tick.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    style: SegmentStyle = SegmentStyle.SOFT,
    height: Dp = 36.dp,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val index = options.indexOf(selected).coerceAtLeast(0)
    val pos by animateFloatAsState(
        index.toFloat(),
        spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "thumb",
    )
    // On glass the track is a faint well and the soft thumb a bright pane, not opaque card.
    val onGlass = LocalAmbient.current != null
    val dark = cs.background.luminance() < 0.5f
    val track = when {
        style == SegmentStyle.TINT -> Color.Transparent
        onGlass -> cs.onSurface.copy(alpha = if (dark) 0.08f else 0.06f)
        style == SegmentStyle.SOFT -> cs.surfaceContainerHigh
        else -> cs.surfaceContainer
    }
    val thumb = when (style) {
        SegmentStyle.SOFT -> if (onGlass) (if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.85f)) else cs.surfaceContainer
        SegmentStyle.SOLID -> cs.primary
        SegmentStyle.TINT -> cs.primary.copy(alpha = 0.12f)
    }
    val on = if (style == SegmentStyle.SOLID) cs.onPrimary else cs.primary
    val off = cs.onSurfaceVariant
    val inset = if (style == SegmentStyle.TINT) 0.dp else 4.dp
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height + inset * 2)
            .clip(RoundedCornerShape(50))
            .background(track)
            .padding(inset),
    ) {
        val w = maxWidth / options.size.coerceAtLeast(1)
        Box(
            Modifier
                .offset { IntOffset((w.toPx() * pos).roundToInt(), 0) }
                .width(w)
                .fillMaxHeight()
                .then(
                    if (style == SegmentStyle.SOFT) {
                        Modifier.shadow(3.dp, RoundedCornerShape(50), ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
                    } else Modifier,
                )
                .clip(RoundedCornerShape(50))
                .background(thumb),
        )
        // Labels twice: the unpicked colour outside the thumb, the picked colour inside it, each
        // clipped to the thumb's live bounds, so text stays crisp wherever the thumb is mid-slide.
        Labels(options, label, off, Modifier.drawWithContent {
            val l = w.toPx() * pos
            clipRect(l, 0f, l + w.toPx(), size.height, ClipOp.Difference) { this@drawWithContent.drawContent() }
        })
        Labels(options, label, on, Modifier.drawWithContent {
            val l = w.toPx() * pos
            clipRect(l, 0f, l + w.toPx(), size.height) { this@drawWithContent.drawContent() }
        })
        Row(Modifier.fillMaxSize()) {
            options.forEach { o ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (o != selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(o)
                            }
                        }
                        // The labels below are drawn twice; this is the one screen readers and tests see.
                        .semantics {
                            role = Role.Tab
                            this.selected = o == selected
                            text = AnnotatedString(label(o))
                        },
                )
            }
        }
    }
}

@Composable
private fun <T> Labels(options: List<T>, label: (T) -> String, color: Color, modifier: Modifier) {
    Row(modifier.fillMaxSize().clearAndSetSemantics {}) {
        options.forEach { o ->
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    label(o),
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}
