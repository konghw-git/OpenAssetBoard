package com.assetboard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.domain.Dates
import com.assetboard.domain.Money
import com.assetboard.ui.theme.AppTheme
import kotlin.math.abs
import kotlin.math.max

data class DonutSlice(val value: Float, val color: Color)

@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    thickness: Dp = 18.dp,
    highlighted: Int? = null,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val inset = stroke / 2 + 4.dp.toPx()
            val arcSize = Size(size.minDimension - inset * 2, size.minDimension - inset * 2)
            val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            val total = slices.sumOf { it.value.toDouble() }.toFloat()
            if (total <= 0f) return@Canvas
            val gap = if (slices.size > 1) 2.2f else 0f
            var start = -90f
            slices.forEachIndexed { i, s ->
                val sweep = 360f * s.value / total
                val w = if (highlighted == i) stroke * 1.35f else stroke
                val drawn = (sweep - gap).coerceAtLeast(0.6f) * progress.value
                drawArc(s.color, start + gap / 2, drawn, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Butt))
                start += sweep * progress.value
            }
        }
        center()
    }
}

data class ChartPoint(val x: Long, val y: Long)

data class ChartSeries(
    val points: List<ChartPoint>,
    val color: Color,
    val fill: Boolean = false,
    val dashed: Boolean = false,
)

/**
 * Line chart for money over time. The first series drives selection: drag or tap to scrub,
 * [onSelect] reports the index into its points (null when released).
 *
 * [target] (a dashed level line with its label) and [band] (a tinted range) are background:
 * they never widen the y range, never take part in the selection, and are clipped to what the
 * data shows, so a far-away target cannot flatten the line into the axis.
 *
 * It draws itself in from the left once. After that, new data morphs out of the line that was
 * there: every point starts at the old line's height at the same place across the plot, the axis
 * labels and the background cross-fade, so switching a metric or a range reads as one movement.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    formatY: (Long) -> String = Money::compact,
    formatX: (Long) -> String = Dates::axis,
    onSelect: (Int?) -> Unit = {},
    target: Long? = null,
    targetLabel: String? = null,
    band: LongRange? = null,
) {
    val measurer = rememberTextMeasurer()
    val grid = AppTheme.extra.chartGrid
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guideColor = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val targetColor = MaterialTheme.colorScheme.tertiary
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor, fontFeatureSettings = "tnum")
    var selected by remember(series) { mutableStateOf<Int?>(null) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    // The frame last drawn; a change of data morphs from it.
    val last = remember { arrayOfNulls<ChartFrame>(1) }
    val change = remember(series, band, target) { Any() }
    val from = remember(change) { last[0] }
    val morph = remember(change) { Animatable(if (from == null) 1f else 0f) }
    LaunchedEffect(morph) { morph.animateTo(1f, tween(450, easing = MorphEasing)) }

    // Plot bounds from the last draw, so touch x maps onto the same axis the lines use.
    val plotBounds = remember { floatArrayOf(0f, 1f) }
    val primary = series.firstOrNull()?.points.orEmpty()
    val allPoints = series.flatMap { it.points }
    if (allPoints.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("还没有数据", style = MaterialTheme.typography.bodyMedium, color = labelColor)
        }
        return
    }
    val minX = allPoints.minOf { it.x }
    val maxX = allPoints.maxOf { it.x }
    var minY = allPoints.minOf { it.y }
    var maxY = allPoints.maxOf { it.y }
    if (minY == maxY) {
        val pad = max(100L, abs(minY) / 10)
        minY -= pad
        maxY += pad
    } else {
        val pad = (maxY - minY) / 8
        minY -= pad
        maxY += pad
    }
    fun nx(x: Long): Float = if (maxX == minX) 0.5f else (x - minX).toFloat() / (maxX - minX).toFloat()
    fun ny(y: Long): Float = (y - minY).toFloat() / (maxY - minY).toFloat()
    val frame = ChartFrame(
        shape = primary.map { nx(it.x) to ny(it.y) },
        yTexts = (0..3).map { i -> formatY(minY + (maxY - minY) * i / 3) },
        xTexts = listOfNotNull(formatX(minX), formatX(maxX).takeIf { maxX != minX }),
        band = band?.let { ny(it.first).coerceIn(0f, 1f) to ny(it.last).coerceIn(0f, 1f) }?.takeIf { it.second > it.first },
        target = target?.takeIf { it in minY..maxY }?.let(::ny),
        targetLabel = targetLabel,
    )
    SideEffect { last[0] = frame }

    Canvas(
        modifier
            .pointerInput(series) {
                detectTapGestures(
                    onPress = { offset ->
                        selected = nearestIndex(primary, offset.x, plotBounds, minX, maxX)
                        onSelect(selected)
                        tryAwaitRelease()
                        selected = null
                        onSelect(null)
                    },
                )
            }
            .pointerInput(series) {
                detectHorizontalDragGestures(
                    onDragEnd = { selected = null; onSelect(null) },
                    onDragCancel = { selected = null; onSelect(null) },
                ) { change, _ ->
                    selected = nearestIndex(primary, change.position.x, plotBounds, minX, maxX)
                    onSelect(selected)
                }
            },
    ) {
        val t = morph.value
        val old = from?.takeIf { t < 1f }
        val top = 8.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val yLabels = frame.yTexts.map { measurer.measure(it, labelStyle) }
        frame.gutter = yLabels.maxOf { it.size.width } + 8.dp.toPx()
        val gutter = if (old != null) old.gutter + (frame.gutter - old.gutter) * t else frame.gutter
        val inset = 6.dp.toPx()
        val left = inset
        val right = size.width - gutter
        plotBounds[0] = left
        plotBounds[1] = right
        fun sx(n: Float): Float = left + (right - left) * n
        fun sy(n: Float): Float = bottom - (bottom - top) * n
        fun px(x: Long): Float = sx(nx(x))
        // Mid-morph a point sits between the old line's height and its own.
        fun py(x: Long, y: Long): Float {
            val n = ny(y)
            val start = old?.heightAt(nx(x)) ?: return sy(n)
            return sy(start + (n - start) * t)
        }

        // Grid at fixed quarters, so only the labels change; old and new labels cross-fade.
        for (i in 0..3) {
            val y = sy(i / 3f)
            drawLine(grid, Offset(0f, y), Offset(right + inset, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            if (old != null) {
                val o = measurer.measure(old.yTexts[i], labelStyle)
                drawText(o, topLeft = Offset(size.width - o.size.width, y - o.size.height / 2f), alpha = fadeOutAlpha(t))
            }
            val l = yLabels[i]
            drawText(l, topLeft = Offset(size.width - l.size.width, y - l.size.height / 2f), alpha = if (old != null) fadeInAlpha(t) else 1f)
        }
        fun xLabels(texts: List<String>, alpha: Float) {
            texts.getOrNull(0)?.let { drawText(measurer.measure(it, labelStyle), topLeft = Offset(0f, bottom + 4.dp.toPx()), alpha = alpha) }
            texts.getOrNull(1)?.let {
                val l = measurer.measure(it, labelStyle)
                drawText(l, topLeft = Offset(right - l.size.width, bottom + 4.dp.toPx()), alpha = alpha)
            }
        }
        if (old != null && old.xTexts != frame.xTexts) {
            xLabels(old.xTexts, fadeOutAlpha(t))
            xLabels(frame.xTexts, fadeInAlpha(t))
        } else {
            xLabels(frame.xTexts, 1f)
        }

        // Background: the band and the target, only where they fall inside the plotted range.
        fun background(f: ChartFrame, alpha: Float) {
            f.band?.let { (lo, hi) ->
                drawRect(bandColor.copy(alpha = bandColor.alpha * alpha), topLeft = Offset(left - inset, sy(hi)), size = Size(right - left + inset * 2, sy(lo) - sy(hi)))
            }
            f.target?.let { n ->
                val y = sy(n)
                drawLine(
                    targetColor.copy(alpha = alpha), Offset(left - inset, y), Offset(right + inset, y), strokeWidth = 1.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
                f.targetLabel?.let {
                    val layout = measurer.measure(it, labelStyle.copy(color = targetColor))
                    val above = y - layout.size.height - 2.dp.toPx()
                    drawText(layout, topLeft = Offset(right - layout.size.width - 2.dp.toPx(), if (above >= top) above else y + 2.dp.toPx()), alpha = alpha)
                }
            }
        }
        if (old != null) background(old, 1f - t)
        background(frame, if (old != null) t else 1f)

        clipRect(right = left + (right - left) * reveal.value + inset) {
            for (s in series.asReversed()) {
                val pts = s.points
                if (pts.isEmpty()) continue
                if (pts.size == 1) {
                    drawCircle(s.color, 4.dp.toPx(), Offset(px(pts[0].x), py(pts[0].x, pts[0].y)))
                    continue
                }
                val path = Path().apply {
                    moveTo(px(pts[0].x), py(pts[0].x, pts[0].y))
                    for (p in pts.drop(1)) lineTo(px(p.x), py(p.x, p.y))
                }
                if (s.fill) {
                    val area = Path().apply {
                        addPath(path)
                        lineTo(px(pts.last().x), bottom)
                        lineTo(px(pts.first().x), bottom)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.22f), s.color.copy(alpha = 0f)), startY = top, endY = bottom))
                }
                drawPath(
                    path, s.color,
                    style = Stroke(
                        width = 2.2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
                    ),
                )
                if (pts.size <= 24 && !s.dashed) {
                    for (p in pts) {
                        val c = Offset(px(p.x), py(p.x, p.y))
                        drawCircle(surface, 3.dp.toPx(), c)
                        drawCircle(s.color, 3.dp.toPx(), c, style = Stroke(1.6.dp.toPx()))
                    }
                }
            }
        }

        selected?.let { idx ->
            val p = primary.getOrNull(idx) ?: return@let
            val x = px(p.x)
            drawLine(guideColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
            for (s in series) {
                val q = s.points.minByOrNull { abs(it.x - p.x) } ?: continue
                drawCircle(surface, 6.dp.toPx(), Offset(px(q.x), py(q.x, q.y)))
                drawCircle(s.color, 4.5.dp.toPx(), Offset(px(q.x), py(q.x, q.y)))
            }
        }
    }
}

/** Moves at once and settles slowly, so a switch answers the tap without a lag. */
internal val MorphEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Labels during a morph: the old ones gone by the middle, the new ones in after it, never stacked. */
internal fun fadeOutAlpha(t: Float) = (1f - t * 2.5f).coerceIn(0f, 1f)
internal fun fadeInAlpha(t: Float) = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)

/**
 * What one draw of a chart showed, on a 0..1 square: the main line's shape, the axis texts and the
 * background. The next data morphs out of it. [gutter] is filled in by the draw that used it.
 */
internal class ChartFrame(
    val shape: List<Pair<Float, Float>>,
    val yTexts: List<String>,
    val xTexts: List<String>,
    val band: Pair<Float, Float>? = null,
    val target: Float? = null,
    val targetLabel: String? = null,
) {
    var gutter = 0f

    /** The line's height at [x] across the plot, straight between its points and flat past its ends. */
    fun heightAt(x: Float): Float? {
        if (shape.isEmpty()) return null
        if (x <= shape.first().first) return shape.first().second
        if (x >= shape.last().first) return shape.last().second
        val i = shape.indexOfFirst { it.first >= x }
        val (x0, y0) = shape[i - 1]
        val (x1, y1) = shape[i]
        return if (x1 == x0) y1 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }
}

private fun nearestIndex(points: List<ChartPoint>, x: Float, bounds: FloatArray, minX: Long, maxX: Long): Int? {
    if (points.isEmpty()) return null
    if (maxX == minX) return points.lastIndex
    val fraction = ((x - bounds[0]) / (bounds[1] - bounds[0]).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val target = minX + (fraction * (maxX - minX)).toLong()
    return points.indices.minByOrNull { abs(points[it].x - target) }
}

/** Axis-free line with a bounded reveal; the endpoint stays still once the data is visible. */
@Composable
fun Sparkline(values: List<Long>, color: Color, modifier: Modifier = Modifier, animate: Boolean = false) {
    if (values.size < 2) return
    val reveal = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(values, animate) {
        if (animate) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        } else reveal.snapTo(1f)
    }
    Canvas(modifier) {
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it != 0L } ?: 1L
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - size.height * ((v - min).toFloat() / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        clipRect(right = size.width * reveal.value + 6.dp.toPx()) {
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0f))))
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (reveal.value >= 1f) {
            val last = values.last()
            val end = Offset(size.width, size.height - size.height * ((last - min).toFloat() / span))
            drawCircle(color.copy(alpha = 0.12f), 5.dp.toPx(), end)
            drawCircle(color, 3.dp.toPx(), end)
        }
    }
}

enum class ChartRange(val label: String, val days: Long?) {
    M3("3月", 92), H6("半年", 183), Y1("1年", 366), ALL("全部", null);

    fun <T> filter(items: List<T>, at: (T) -> Long, now: Long = System.currentTimeMillis()): List<T> {
        val d = days ?: return items
        val cutoff = now - d * 24 * 3600 * 1000
        return items.filter { at(it) >= cutoff }
    }
}

@Composable
fun RangeChips(selected: ChartRange, onSelect: (ChartRange) -> Unit, modifier: Modifier = Modifier) {
    SegmentedControl(ChartRange.entries, selected, { it.label }, onSelect, modifier, style = SegmentStyle.TINT, height = 32.dp)
}
