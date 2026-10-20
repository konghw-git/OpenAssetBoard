package com.assetboard.ui.components

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize

/**
 * The glass material of list cards and floating bars: a translucent fill that takes on the
 * page's ambient colour, a soft sheen over the top, a lit rim and a shadow that stays outside
 * the shape, so the fill never turns grey over it.
 */
@Immutable
data class GlassStyle(
    val fill: Color,
    val sheen: Color,
    val rimLight: Color,
    val rimShade: Color,
    val shadow: Color,
    val floatFill: Color,
)

private val LightGlass = GlassStyle(
    fill = Color.White.copy(alpha = 0.70f),
    sheen = Color.White.copy(alpha = 0.55f),
    rimLight = Color.White.copy(alpha = 0.95f),
    rimShade = Color(0xFF1E5B4F).copy(alpha = 0.07f),
    shadow = Color(0xFF1E3A33).copy(alpha = 0.10f),
    floatFill = Color(0xFFFBFAF7).copy(alpha = 0.62f),
)

private val DarkGlass = GlassStyle(
    fill = Color.White.copy(alpha = 0.06f),
    sheen = Color.White.copy(alpha = 0.06f),
    rimLight = Color.White.copy(alpha = 0.17f),
    rimShade = Color.White.copy(alpha = 0.03f),
    shadow = Color.Black.copy(alpha = 0.40f),
    floatFill = Color(0xFF1E2321).copy(alpha = 0.66f),
)

/** The glass for the current theme; the theme has no flag, so the background decides. */
val glass: GlassStyle
    @Composable @ReadOnlyComposable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkGlass else LightGlass

/**
 * A glass card behind the content. [tint] washes the fill (a selected row); [elevation] sets
 * how far the shadow spreads, zero for none.
 */
fun Modifier.glass(shape: Shape, style: GlassStyle, tint: Color = Color.Unspecified, elevation: Dp = 10.dp): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    if (elevation > 0.dp) softShadow(path, style.shadow, elevation)
    drawPath(path, style.fill)
    if (tint != Color.Unspecified) drawPath(path, tint)
    clipPath(path) {
        drawRect(Brush.verticalGradient(listOf(style.sheen, Color.Transparent), endY = size.height * 0.55f))
    }
    rim(path, style)
}

/** A shadow outside [path] only: the shape is clipped away before the blur is drawn. */
internal fun DrawScope.softShadow(path: Path, color: Color, elevation: Dp) {
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.clipPath(path, ClipOp.Difference)
        val paint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.WHITE
            setShadowLayer(elevation.toPx() * 1.4f, 0f, elevation.toPx() * 0.45f, color.toArgb())
        }
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
        canvas.restore()
    }
}

/** A hairline lit from the top left and fading toward the bottom right. */
internal fun DrawScope.rim(path: Path, style: GlassStyle) {
    val w = 1.dp.toPx()
    drawPath(
        path,
        Brush.linearGradient(
            0f to style.rimLight,
            0.45f to style.rimLight.copy(alpha = style.rimLight.alpha * 0.35f),
            1f to style.rimShade,
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        style = Stroke(w),
    )
}

/**
 * Soft colour fields fixed behind a page, for the glass over it to pick up: green from the top
 * left, sand from the right, a little blue lower down. A sticky header draws its own slice of
 * the same fields ([ambientSlice]), so it covers the rows under it without showing a seam.
 */
@Stable
class Ambient internal constructor(val background: Color, val primary: Color, val secondary: Color, val tertiary: Color) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var size by mutableStateOf(Size.Zero)
}

@Composable
fun rememberAmbient(): Ambient {
    val cs = MaterialTheme.colorScheme
    return remember(cs.background, cs.primary, cs.secondary, cs.tertiary) { Ambient(cs.background, cs.primary, cs.secondary, cs.tertiary) }
}

private fun DrawScope.drawAmbient(a: Ambient, w: Float, h: Float) {
    val dark = a.background.luminance() < 0.5f
    drawRect(a.background, size = Size(w, h))
    fun blob(c: Color, alpha: Float, x: Float, y: Float, r: Float) =
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = alpha), Color.Transparent), center = Offset(x, y), radius = r), r, Offset(x, y))
    blob(a.primary, if (dark) 0.16f else 0.12f, w * 0.05f, h * 0.02f, w * 0.95f)
    blob(a.secondary, if (dark) 0.10f else 0.13f, w * 1.0f, h * 0.22f, w * 0.80f)
    blob(a.tertiary, if (dark) 0.10f else 0.08f, w * 0.0f, h * 0.62f, w * 0.85f)
    blob(a.primary, if (dark) 0.08f else 0.06f, w * 0.95f, h * 0.95f, w * 0.9f)
}

/** The page background. */
fun Modifier.ambient(a: Ambient): Modifier = this
    .onGloballyPositioned {
        a.origin = it.positionInRoot()
        a.size = it.size.toSize()
    }
    .drawBehind { drawAmbient(a, size.width, size.height) }

/** The part of [a]'s page background that lies under this element. */
fun Modifier.ambientSlice(a: Ambient): Modifier = composed {
    var at by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { at = it.positionInRoot() }.drawBehind {
        clipRect {
            translate(a.origin.x - at.x, a.origin.y - at.y) { drawAmbient(a, a.size.width, a.size.height) }
        }
    }
}

/**
 * What a floating glass bar blurs: the page under it, recorded once per frame into a layer the
 * bar draws again, blurred, inside its own shape.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Marks the content a [Backdrop] shows. The glass that blurs it must not be inside it. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * Frosted glass for something floating over [backdrop]: the page under it blurred (Android 12
 * and later; a denser fill before that), then the tinted fill, sheen and rim.
 */
fun Modifier.frosted(backdrop: Backdrop?, shape: Shape, style: GlassStyle, blur: Dp = 22.dp, elevation: Dp = 14.dp): Modifier = composed {
    val blurred = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val canBlur = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    onGloballyPositioned { origin = it.positionInRoot() }.drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        if (elevation > 0.dp) softShadow(path, style.shadow, elevation)
        clipPath(path) {
            if (canBlur) {
                val r = blur.toPx()
                blurred.renderEffect = BlurEffect(r, r, TileMode.Clamp)
                blurred.record(size.toIntSize()) {
                    translate(backdrop!!.origin.x - origin.x, backdrop.origin.y - origin.y) { drawLayer(backdrop.layer) }
                }
                drawLayer(blurred)
                drawRect(style.floatFill)
            } else {
                drawRect(style.floatFill.copy(alpha = (style.floatFill.alpha + 0.3f).coerceAtMost(0.97f)))
            }
            drawRect(Brush.verticalGradient(listOf(style.sheen, Color.Transparent), endY = size.height * 0.6f))
        }
        rim(path, style)
    }
}
