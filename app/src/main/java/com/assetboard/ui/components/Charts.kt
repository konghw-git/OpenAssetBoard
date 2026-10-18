package com.assetboard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    center: @Composable BoxScope.() -> Unit = {},
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.surfaceVariant
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
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    formatY: (Long) -> String = Money::compact,
    formatX: (Long) -> String = Dates::axis,
    onSelect: (Int?) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val grid = AppTheme.extra.chartGrid
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guideColor = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor, fontFeatureSettings = "tnum")
    var selected by remember(series) { mutableStateOf<Int?>(null) }
    val progress = remember(series.firstOrNull()?.points?.size) { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }

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
        val top = 8.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val yLabels = (0..3).map { i -> minY + (maxY - minY) * i / 3 }.map { it to measurer.measure(formatY(it), labelStyle) }
        val gutter = yLabels.maxOf { it.second.size.width } + 8.dp.toPx()
        val inset = 6.dp.toPx()
        val left = inset
        val right = size.width - gutter
        plotBounds[0] = left
        plotBounds[1] = right
        fun px(x: Long): Float =
            if (maxX == minX) (left + right) / 2 else left + (right - left) * ((x - minX).toFloat() / (maxX - minX).toFloat())
        fun py(y: Long): Float = bottom - (bottom - top) * ((y - minY).toFloat() / (maxY - minY).toFloat())

        // Grid with labels in the right gutter.
        for ((yv, layout) in yLabels) {
            val y = py(yv)
            drawLine(grid, Offset(0f, y), Offset(right + inset, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawText(layout, topLeft = Offset(size.width - layout.size.width, y - layout.size.height / 2f))
        }
        val first = measurer.measure(formatX(minX), labelStyle)
        drawText(first, topLeft = Offset(0f, bottom + 4.dp.toPx()))
        if (maxX != minX) {
            val last = measurer.measure(formatX(maxX), labelStyle)
            drawText(last, topLeft = Offset(right - last.size.width, bottom + 4.dp.toPx()))
        }

        clipRect(right = left + (right - left) * progress.value + inset) {
            for (s in series.asReversed()) {
                val pts = s.points
                if (pts.isEmpty()) continue
                if (pts.size == 1) {
                    drawCircle(s.color, 4.dp.toPx(), Offset(px(pts[0].x), py(pts[0].y)))
                    continue
                }
                val path = Path().apply {
                    moveTo(px(pts[0].x), py(pts[0].y))
                    for (p in pts.drop(1)) lineTo(px(p.x), py(p.y))
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
                        drawCircle(surface, 3.dp.toPx(), Offset(px(p.x), py(p.y)))
                        drawCircle(s.color, 3.dp.toPx(), Offset(px(p.x), py(p.y)), style = Stroke(1.6.dp.toPx()))
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
                drawCircle(surface, 6.dp.toPx(), Offset(px(q.x), py(q.y)))
                drawCircle(s.color, 4.5.dp.toPx(), Offset(px(q.x), py(q.y)))
            }
        }
    }
}

private fun nearestIndex(points: List<ChartPoint>, x: Float, bounds: FloatArray, minX: Long, maxX: Long): Int? {
    if (points.isEmpty()) return null
    if (maxX == minX) return points.lastIndex
    val fraction = ((x - bounds[0]) / (bounds[1] - bounds[0]).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val target = minX + (fraction * (maxX - minX)).toLong()
    return points.indices.minByOrNull { abs(points[it].x - target) }
}

/** Axis-free line for small spaces like the hero card; [animate] draws it in and pulses the last point. */
@Composable
fun Sparkline(values: List<Long>, color: Color, modifier: Modifier = Modifier, animate: Boolean = false) {
    if (values.size < 2) return
    val reveal = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(values) { if (animate) reveal.animateTo(1f, tween(1_200, delayMillis = 200, easing = FastOutSlowInEasing)) }
    val pulse = if (animate) {
        val t = rememberInfiniteTransition(label = "spark")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "pulse").value
    } else 0f
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
            if (animate) drawCircle(color.copy(alpha = 0.35f * (1f - pulse)), (3f + 7f * pulse).dp.toPx(), end)
            drawCircle(color, 3.dp.toPx(), end)
        }
    }
}

enum class ChartRange(val label: String, val days: Long?) {
    M3("3月", 92), Y1("1年", 366), ALL("全部", null);

    fun <T> filter(items: List<T>, at: (T) -> Long, now: Long = System.currentTimeMillis()): List<T> {
        val d = days ?: return items
        val cutoff = now - d * 24 * 3600 * 1000
        return items.filter { at(it) >= cutoff }
    }
}

@Composable
fun RangeChips(selected: ChartRange, onSelect: (ChartRange) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        ChartRange.entries.forEach { r ->
            FilterChip(selected = r == selected, onClick = { onSelect(r) }, label = { Text(r.label) })
        }
    }
}
