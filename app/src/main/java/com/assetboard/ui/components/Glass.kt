package com.assetboard.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.isSpecified
import com.assetboard.ui.theme.AppTheme
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

/** The two kinds of glass, picked in settings: clear shows the colour behind, deep is denser and calmer. */
enum class GlassMode(val label: String) { CLEAR("清透"), DEEP("深色") }

val LocalGlassMode = staticCompositionLocalOf { GlassMode.CLEAR }

/**
 * Quiet translucent surfaces with a fine rim. Cards use a denser fill instead of per-card blur;
 * floating navigation still frosts the content beneath it. Text stays clear over either mode.
 */
@Immutable
data class GlassStyle(
    val fill: Color,
    val sheen: Color,
    val rimLight: Color,
    val rimShade: Color,
    /** The thin line inside the top edge, where light enters the glass. */
    val glow: Color,
    /** A faint outer line that keeps the shape from dissolving into the page. */
    val edge: Color,
    val shadow: Color,
    val floatFill: Color,
    /** How much of the page behind is blurred into the body; zero draws none. */
    val blur: Dp,
)

private val LightClear = GlassStyle(
    fill = Color.White.copy(alpha = 0.80f),
    sheen = Color.White.copy(alpha = 0.16f),
    rimLight = Color.White,
    rimShade = Color.White.copy(alpha = 0.55f),
    glow = Color.White.copy(alpha = 0.85f),
    edge = Color(0xFF1E3A33).copy(alpha = 0.06f),
    shadow = Color(0xFF1E3A33).copy(alpha = 0.055f),
    floatFill = Color.White.copy(alpha = 0.82f),
    blur = 0.dp,
)

private val LightDeep = GlassStyle(
    fill = Color(0xFFE4EBE6).copy(alpha = 0.94f),
    sheen = Color.White.copy(alpha = 0.12f),
    rimLight = Color.White,
    rimShade = Color.White.copy(alpha = 0.25f),
    glow = Color.White.copy(alpha = 0.9f),
    edge = Color(0xFF1E3A33).copy(alpha = 0.09f),
    shadow = Color(0xFF1E3A33).copy(alpha = 0.07f),
    floatFill = Color(0xFFE4EBE6).copy(alpha = 0.94f),
    blur = 0.dp,
)

private val DarkClear = GlassStyle(
    fill = Color(0xFF21332B).copy(alpha = 0.88f),
    sheen = Color.White.copy(alpha = 0.025f),
    rimLight = Color.White.copy(alpha = 0.13f),
    rimShade = Color.White.copy(alpha = 0.04f),
    glow = Color.White.copy(alpha = 0.07f),
    edge = Color.Black.copy(alpha = 0.25f),
    shadow = Color.Black.copy(alpha = 0.20f),
    floatFill = Color(0xFF21332B).copy(alpha = 0.90f),
    blur = 0.dp,
)

private val DarkDeep = GlassStyle(
    fill = Color(0xFF14221C).copy(alpha = 0.96f),
    sheen = Color.White.copy(alpha = 0.02f),
    rimLight = Color.White.copy(alpha = 0.10f),
    rimShade = Color.White.copy(alpha = 0.03f),
    glow = Color.White.copy(alpha = 0.05f),
    edge = Color.Black.copy(alpha = 0.35f),
    shadow = Color.Black.copy(alpha = 0.25f),
    floatFill = Color(0xFF14221C).copy(alpha = 0.96f),
    blur = 0.dp,
)

/** The glass for the current theme and the mode picked in settings. */
val glass: GlassStyle
    @Composable @ReadOnlyComposable get() {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        return when (LocalGlassMode.current) {
            GlassMode.CLEAR -> if (dark) DarkClear else LightClear
            GlassMode.DEEP -> if (dark) DarkDeep else LightDeep
        }
    }

/**
 * A glass body behind the content. On a page with an [Ambient] (see [LocalAmbient]) the colour
 * fields under the shape are blurred into it (Android 12 and later). [tint] washes the body (a
 * selected row); [elevation] sets how far the shadow spreads, zero for none.
 */
fun Modifier.glass(shape: Shape, style: GlassStyle, tint: Color = Color.Unspecified, elevation: Dp = 10.dp): Modifier = composed {
    val ambient = LocalAmbient.current
    val blur = ambient != null && style.blur > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val layer = if (blur) rememberGraphicsLayer() else null
    var at by remember { mutableStateOf(Offset.Unspecified) }
    (if (blur) onGloballyPositioned { at = it.positionInRoot() } else Modifier).drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        if (elevation > 0.dp) softShadow(path, style.shadow, elevation)
        if (layer != null && ambient != null && at.isSpecified) {
            clipPath(path) {
                val r = style.blur.toPx()
                layer.renderEffect = BlurEffect(r, r, TileMode.Clamp)
                layer.record(size.toIntSize()) {
                    translate(ambient.origin.x - at.x, ambient.origin.y - at.y) { drawAmbient(ambient, ambient.size.width, ambient.size.height) }
                }
                drawLayer(layer)
            }
        }
        drawPath(path, style.fill)
        if (tint != Color.Unspecified) drawPath(path, tint)
        clipPath(path) {
            drawRect(Brush.verticalGradient(listOf(style.sheen, Color.Transparent), endY = size.height * 0.5f))
        }
        rim(path, style)
    }
}

/** A shadow outside [path] only: the shape is clipped away before the blur is drawn. */
internal fun DrawScope.softShadow(path: Path, color: Color, elevation: Dp) {
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.clipPath(path, ClipOp.Difference)
        val paint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.WHITE
            setShadowLayer(elevation.toPx() * 1.5f, 0f, elevation.toPx() * 0.5f, color.toArgb())
        }
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
        canvas.restore()
    }
}

/**
 * The lit edges: a faint outer line for definition, a rim bright at the top left that dims
 * across and brightens a little again at the bottom right, and a glow just inside the top.
 */
internal fun DrawScope.rim(path: Path, style: GlassStyle) {
    drawPath(path, style.edge, style = Stroke(2.dp.toPx()))
    drawPath(
        path,
        Brush.linearGradient(
            0f to style.rimLight,
            0.4f to style.rimLight.copy(alpha = style.rimLight.alpha * 0.25f),
            0.75f to style.rimShade.copy(alpha = style.rimShade.alpha * 0.4f),
            1f to style.rimShade,
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        style = Stroke(1.2.dp.toPx()),
    )
    clipPath(path) {
        drawPath(
            path,
            Brush.verticalGradient(listOf(style.glow, Color.Transparent), startY = 0f, endY = minOf(size.height * 0.45f, 26.dp.toPx())),
            style = Stroke(3.dp.toPx()),
        )
    }
}

/** The page's [Ambient], for the glass on it to blur. */
val LocalAmbient = compositionLocalOf<Ambient?> { null }

/**
 * Restrained mineral-green and warm fields fixed behind a page. A
 * sticky header draws its own slice of the same fields ([ambientSlice]), so it covers the rows
 * under it without showing a seam.
 */
@Stable
class Ambient internal constructor(val background: Color, val palette: List<Color>, val dark: Boolean) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var size by mutableStateOf(Size.Zero)
}

@Composable
fun rememberAmbient(): Ambient {
    val cs = MaterialTheme.colorScheme
    val palette = AppTheme.extra.groupPalette
    return remember(cs.background, palette) { Ambient(cs.background, palette, cs.background.luminance() < 0.5f) }
}

private fun DrawScope.drawAmbient(a: Ambient, w: Float, h: Float) {
    drawRect(a.background, size = Size(w, h))
    // A body that holds its colour, then a soft edge: blurred under glass it reads as frosted.
    fun orb(c: Color, alpha: Float, x: Float, y: Float, r: Float) = drawCircle(
        Brush.radialGradient(0f to c.copy(alpha = alpha), 0.55f to c.copy(alpha = alpha * 0.7f), 1f to Color.Transparent, center = Offset(x, y), radius = r),
        r, Offset(x, y),
    )
    val k = if (a.dark) 1.3f else 1f
    orb(a.palette[0], 0.065f * k, w * 0.10f, h * 0.05f, w * 0.85f)
    orb(a.palette[2], 0.035f * k, w, h * 0.40f, w * 0.70f)
}

/** A page with [Ambient] colour behind [content], for the glass on it. */
@Composable
fun AmbientPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val a = rememberAmbient()
    Box(modifier.fillMaxSize().ambient(a)) {
        CompositionLocalProvider(LocalAmbient provides a, content = content)
    }
}

/** The page background; also makes [a] the one the glass on the page blurs, through [LocalAmbient]. */
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
