package com.assetboard.ui.components

import com.assetboard.domain.AppClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import com.assetboard.ui.theme.ExtraColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.Aurora
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The top of each tab: a large title, one line of context under it, and round
 * action buttons on the right. [statusBar] adds the status bar inset when the header is not
 * inside something that already did.
 */
@Composable
fun TabHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    statusBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .then(if (statusBar) Modifier.statusBarsPadding() else Modifier)
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                // A new line of context rises in under the title as the old one fades.
                AnimatedContent(
                    subtitle,
                    transitionSpec = { (fadeIn(tween(240, delayMillis = 40)) + slideInVertically(tween(Motion.MEDIUM, easing = Motion.Decelerate)) { it / 3 }) togetherWith fadeOut(tween(140)) },
                    label = "subtitle",
                ) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A round icon button on a soft card background, the header's action style. [badge] adds a red dot. */
@Composable
fun HeaderAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface, badge: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    // Glass on a page with colour behind it; a plain soft card elsewhere.
    val onGlass = LocalAmbient.current != null
    Box(modifier.size(44.dp)) {
        Box(
            Modifier
                .size(44.dp)
                .pressScale(source, 0.88f)
                .then(
                    if (onGlass) {
                        Modifier.glass(CircleShape, glass, elevation = 8.dp)
                    } else {
                        Modifier
                            .shadow(2.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f))
                            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                    },
                )
                .clip(CircleShape)
                .clickable(interactionSource = source, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp))
        }
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp).size(10.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface).padding(1.5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
            )
        }
    }
}

/** What the backup needs looking at ([BackupHealth]), checked again each time the app comes back. */
@Composable
fun rememberBackupProblem(): String? {
    val settings = com.assetboard.ui.LocalContainer.current.settings
    val context = androidx.compose.ui.platform.LocalContext.current
    val tree by settings.backupTreeUri.collectAsStateWithLifecycle()
    val last by settings.lastBackupAt.collectAsStateWithLifecycle()
    val failure by settings.backupError.collectAsStateWithLifecycle()
    // Bumped on every resume: a folder's permission can go while the app is away.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    return remember(tree, last, failure, resumed) {
        com.assetboard.data.BackupHealth.problem(context, tree, last, failure, AppClock.millis())
    }
}

/** The tabs' settings button, with a red dot while the backup needs looking at. */
@Composable
fun SettingsAction(onClick: () -> Unit) {
    val problem = rememberBackupProblem()
    HeaderAction(Icons.Outlined.Settings, if (problem != null) "设置（备份需要处理）" else "设置", onClick, badge = problem != null)
}

/**
 * The tab's main action, centered above the navigation bar: a glossy pill in the hero gradient
 * with the icon set in a light disc.
 */
@Composable
fun PrimaryActionButton(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extra = AppTheme.extra
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .pressScale(source, 0.93f)
            .shadow(12.dp, shape, ambientColor = extra.heroStart, spotColor = extra.heroStart)
            .clip(shape)
            .background(Brush.linearGradient(listOf(extra.heroEnd, extra.heroMid, extra.heroStart)))
            .drawWithContent {
                drawContent()
                // A soft gloss over the top half.
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), endY = size.height * 0.6f))
            }
            .shine(delayMs = 700)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .height(58.dp)
            .padding(start = 7.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(extra.onHero),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = extra.heroStart, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = extra.onHero, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The hero card's surface, shared by every tab's showpiece: a deep three-stop gradient, a cool
 * light entering at the top right, a brass glow in the lower left, fine contour rings kept out
 * of the reading column, a lit top edge like the glass cards', and one pass of light as the page
 * settles.
 */
fun Modifier.heroSurface(shape: Shape, extra: ExtraColors, elevation: Dp = 14.dp): Modifier = this
    .shadow(elevation, shape, ambientColor = extra.heroStart, spotColor = extra.heroStart)
    .clip(shape)
    .drawBehind {
        val w = size.width
        val h = size.height
        drawRect(Brush.linearGradient(listOf(extra.heroStart, extra.heroMid, extra.heroEnd), Offset.Zero, Offset(w, h)))
        val light = Offset(w * 0.95f, -h * 0.15f)
        drawCircle(Brush.radialGradient(listOf(extra.heroGlow.copy(alpha = 0.55f), Color.Transparent), light, w * 0.65f), w * 0.65f, light)
        val warm = Offset(w * 0.02f, h * 1.1f)
        drawCircle(Brush.radialGradient(listOf(extra.onHeroAccent.copy(alpha = 0.22f), Color.Transparent), warm, w * 0.55f), w * 0.55f, warm)
        repeat(4) { i ->
            drawCircle(extra.onHero.copy(alpha = 0.05f), w * (0.34f + i * 0.11f), Offset(w * 1.12f, h * 0.2f), style = Stroke(1.dp.toPx()))
        }
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent), Offset(w * 0.25f, 0f), Offset(w * 0.75f, h)))
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
        drawPath(path, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent), endY = 30.dp.toPx()), style = Stroke(2.dp.toPx()))
    }
    .shine()

/** One lap of the warm fields under the net worth card; slow enough to read as light, not motion. */
private const val AURORA_MS = 16_000
/** One lap of the light running round the card's edge. */
private const val RIM_MS = 7_000
/** Where each warm field rests, as fractions of the card, and how many laps it makes per cycle, so the loop closes without a seam. */
private val FieldHomes = listOf(Offset(0.12f, 0.18f), Offset(0.92f, 0.22f), Offset(0.30f, 0.98f), Offset(0.88f, 0.86f))
private val FieldLaps = intArrayOf(1, -1, 2, -2)

/**
 * The net worth card's surface: warm clear glass over the page, lit from inside by four soft
 * fields of amber, coral, persimmon and rose, each drifting on its own loop; a frosted sheen over
 * the top, a lit rim with a spark of light running round it, and one pass of light as the page
 * settles. Everything moving is read in the draw phase, so the card's content never recomposes
 * for it.
 */
fun Modifier.auroraSurface(shape: Shape, a: Aurora, elevation: Dp = 18.dp): Modifier = composed {
    val clock = rememberInfiniteTransition(label = "aurora")
    val drift = clock.animateFloat(0f, 1f, infiniteRepeatable(tween(AURORA_MS, easing = LinearEasing)), label = "drift")
    val spark = clock.animateFloat(0f, 360f, infiniteRepeatable(tween(RIM_MS, easing = LinearEasing)), label = "spark")
    val dark = a.ink.luminance() > 0.5f
    drawBehind {
        val w = size.width
        val h = size.height
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
        softShadow(path, a.shadow, elevation)
        clipPath(path) {
            drawRect(Brush.linearGradient(a.wash, Offset.Zero, Offset(w, h)))
            val turn = drift.value * 2f * PI.toFloat()
            a.fields.forEachIndexed { i, c ->
                val home = FieldHomes[i % FieldHomes.size]
                val phase = turn * FieldLaps[i % FieldLaps.size] + i * 1.7f
                val center = Offset(w * home.x + cos(phase) * w * 0.10f, h * home.y + sin(phase) * h * 0.16f)
                val r = w * (0.58f + 0.06f * sin(turn + i))
                drawCircle(
                    Brush.radialGradient(0f to c.copy(alpha = a.fieldAlpha), 0.5f to c.copy(alpha = a.fieldAlpha * 0.5f), 1f to Color.Transparent, center = center, radius = r),
                    r, center,
                )
            }
            // Frost over the top, so the colour reads as under glass rather than painted on.
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.07f else 0.30f), Color.Transparent), endY = h * 0.5f))
        }
        drawPath(path, Brush.linearGradient(listOf(a.rim, a.rim.copy(alpha = a.rim.alpha * 0.25f), a.rim.copy(alpha = a.rim.alpha * 0.6f)), Offset.Zero, Offset(w, h)), style = Stroke(1.3.dp.toPx()))
        // The spark: a bright arc of a sweep gradient, seen only through a thin ring at the edge.
        val inset = 2.dp.toPx()
        val inner = Path().apply {
            addOutline(shape.createOutline(Size(w - inset * 2, h - inset * 2), layoutDirection, this@drawBehind))
            translate(Offset(inset, inset))
        }
        clipPath(path) {
            clipPath(inner, ClipOp.Difference) {
                rotate(spark.value) {
                    val glow = a.flame.first()
                    drawCircle(
                        Brush.sweepGradient(
                            0f to Color.Transparent, 0.36f to Color.Transparent, 0.47f to glow.copy(alpha = 0.55f),
                            0.5f to Color.White.copy(alpha = 0.95f), 0.53f to glow.copy(alpha = 0.55f), 0.64f to Color.Transparent, 1f to Color.Transparent,
                            center = center,
                        ),
                        radius = hypot(w, h) / 2f, center = center,
                    )
                }
            }
        }
    }.shine(alpha = 0.22f)
}

/** A tinted rounded square holding a row's icon, so each settings row has its own color. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 34.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(Brush.linearGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.75f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.56f))
    }
}
