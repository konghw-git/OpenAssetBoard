package com.assetboard.ui.trend

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.domain.Dates
import com.assetboard.domain.Money
import com.assetboard.domain.NetWorthPoint
import com.assetboard.ui.components.LocalHideAmounts
import com.assetboard.ui.components.MASKED
import com.assetboard.ui.components.MorphEasing
import com.assetboard.ui.components.fadeInAlpha
import com.assetboard.ui.components.fadeOutAlpha
import com.assetboard.ui.theme.AppTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where the plot sits inside the canvas, kept from the last draw so touches map onto it. */
private class PlotX(var left: Float = 0f, var right: Float = 1f)

/**
 * Taps pick the nearest stocktake (tap it again to let go); a horizontal drag scrubs. The pick
 * stays after the finger lifts so the numbers can be read.
 */
private fun Modifier.scrub(count: Int, plot: PlotX, xOf: (Int) -> Float, selected: Int?, onSelect: (Int?) -> Unit): Modifier =
    this
        .pointerInput(count, selected) {
            detectTapGestures { o ->
                val i = nearest(count, o.x, xOf)
                onSelect(if (i == selected) null else i)
            }
        }
        .pointerInput(count) {
            detectHorizontalDragGestures { change, _ -> onSelect(nearest(count, change.position.x, xOf)) }
        }

private fun nearest(count: Int, x: Float, xOf: (Int) -> Float): Int? =
    if (count == 0) null else (0 until count).minBy { abs(xOf(it) - x) }

/** Four round values spanning [lo, hi], so the grid reads 2万 / 4万 rather than 1.87万. */
private fun niceTicks(lo: Long, hi: Long): List<Long> {
    val span = (hi - lo).coerceAtLeast(100L).toDouble()
    val raw = span / 3
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }.toLong().coerceAtLeast(1L)
    val first = Math.floorDiv(lo, step) * step
    return (0..4).map { first + it * step }.filter { it <= hi + step / 2 }.take(5)
}

/** An axis figure, or nothing while amounts are hidden. */
private fun axis(v: Long, hide: Boolean): String = if (hide) "" else Money.compact(v)

private fun DrawScope.yGrid(ticks: List<Long>, py: (Long) -> Float, right: Float, grid: Color, measurer: TextMeasurer, style: TextStyle, alpha: Float = 1f, hide: Boolean = false) {
    for (t in ticks) {
        val y = py(t)
        drawLine(grid.copy(alpha = grid.alpha * alpha), Offset(0f, y), Offset(right, y), strokeWidth = 1f)
        val label = measurer.measure(axis(t, hide), style)
        drawText(label, topLeft = Offset(size.width - label.size.width, y - label.size.height / 2f), alpha = alpha)
    }
}

/** Date labels under the plot: the ends always, the middle ones only where they fit. */
private fun DrawScope.xLabels(times: List<Long>, px: (Long) -> Float, bottom: Float, left: Float, right: Float, measurer: TextMeasurer, style: TextStyle, alpha: Float = 1f) {
    if (times.isEmpty()) return
    val lo = times.first()
    val hi = times.last()
    val marks = if (lo == hi) listOf(lo) else (0..3).map { lo + (hi - lo) * it / 3 }
    var lastEnd = Float.NEGATIVE_INFINITY
    marks.forEachIndexed { i, t ->
        val text = measurer.measure(Dates.axis(t), style)
        val x = when (i) {
            0 -> left
            marks.lastIndex -> right - text.size.width
            else -> px(t) - text.size.width / 2f
        }
        val gap = 8.dp.toPx()
        val nextIsLast = i == marks.lastIndex - 1
        val lastStart = right - measurer.measure(Dates.axis(hi), style).size.width
        if (x < lastEnd + gap) return@forEachIndexed
        if (i != marks.lastIndex && nextIsLast && x + text.size.width + gap > lastStart) return@forEachIndexed
        drawText(text, topLeft = Offset(x, bottom + 6.dp.toPx()), alpha = alpha)
        lastEnd = x + text.size.width
    }
}

/** A rounded label box above the plot, centered on [x] but kept inside the canvas. */
private fun DrawScope.bubble(x: Float, lines: List<Pair<String, TextStyle>>, measurer: TextMeasurer, bg: Color) {
    val layouts = lines.map { (t, s) -> measurer.measure(t, s) }
    val padH = 10.dp.toPx()
    val padV = 6.dp.toPx()
    val w = layouts.maxOf { it.size.width } + padH * 2
    val h = layouts.sumOf { it.size.height } + padV * 2
    val left = (x - w / 2).coerceIn(0f, max(0f, size.width - w))
    drawRoundRect(bg, Offset(left, 0f), Size(w, h), CornerRadius(10.dp.toPx()))
    var y = padV
    for (l in layouts) {
        drawText(l, topLeft = Offset(left + (w - l.size.width) / 2, y))
        y += l.size.height
    }
}

/**
 * Net worth over the period: one line, its area, a dashed line at the starting value so gains
 * and losses read at a glance, and a bubble with the picked stocktake's date and amount.
 */
@Composable
fun NetWorthChart(points: List<NetWorthPoint>, selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val hide = LocalHideAmounts.current
    val extra = AppTheme.extra
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = cs.onSurfaceVariant, fontFeatureSettings = "tnum")
    // Drawn in from the left once; a new range then morphs out of the line that was there.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    val last = remember { arrayOfNulls<TrendFrame>(1) }
    val change = remember(points) { Any() }
    val from = remember(change) { last[0] }
    val morph = remember(change) { Animatable(if (from == null) 1f else 0f) }
    LaunchedEffect(morph) { morph.animateTo(1f, tween(450, easing = MorphEasing)) }
    val plot = remember { PlotX() }
    if (points.isEmpty()) return
    val minX = points.first().snapshot.at
    val maxX = points.last().snapshot.at
    fun nx(t: Long) = if (maxX == minX) 0.5f else (t - minX).toFloat() / (maxX - minX)
    fun px(t: Long) = plot.left + (plot.right - plot.left) * nx(t)
    val start = points.first().netWorth
    val line by animateColorAsState(extra.signed(points.last().netWorth - start).takeIf { points.size > 1 } ?: cs.primary, tween(520), label = "line")
    val lo0 = points.minOf { it.netWorth }
    val hi0 = points.maxOf { it.netWorth }
    val pad = max((hi0 - lo0) / 6, max(100L, abs(hi0) / 20))
    val ticks = niceTicks(lo0 - pad, hi0 + pad)
    val lo = min(ticks.first(), lo0)
    val hi = max(ticks.last(), hi0)
    fun ny(v: Long) = (v - lo).toFloat() / (hi - lo).coerceAtLeast(1)
    val frame = TrendFrame(points.map { nx(it.snapshot.at) to ny(it.netWorth) }, ticks.map { ny(it) to it }, points.map { it.snapshot.at }, ny(start))
    SideEffect { last[0] = frame }

    Canvas(modifier.scrub(points.size, plot, { px(points[it].snapshot.at) }, selected, onSelect)) {
        // Room above the line for the picked stocktake's bubble.
        val top = 40.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val t = morph.value
        val old = from?.takeIf { t < 1f }
        frame.gutter = ticks.maxOf { measurer.measure(axis(it, hide), labelStyle).size.width } + 10.dp.toPx()
        val gutter = if (old != null) old.gutter + (frame.gutter - old.gutter) * t else frame.gutter
        plot.left = 8.dp.toPx()
        plot.right = size.width - gutter
        fun sy(n: Float) = bottom - (bottom - top) * n
        fun py(v: Long) = sy(ny(v))
        // Mid-morph a point sits between the old line's height and its own.
        fun my(at: Long, v: Long): Float {
            val n = ny(v)
            val from0 = old?.heightAt(nx(at)) ?: return sy(n)
            return sy(from0 + (n - from0) * t)
        }
        if (old != null) {
            yGrid(old.ticks.map { it.second }, { v -> sy(old.ticks.first { it.second == v }.first) }, plot.right + 4.dp.toPx(), extra.chartGrid, measurer, labelStyle, fadeOutAlpha(t), hide)
            xLabels(old.times, { at -> plot.left + (plot.right - plot.left) * old.nx(at) }, bottom, 0f, plot.right, measurer, labelStyle, fadeOutAlpha(t))
        }
        yGrid(ticks, ::py, plot.right + 4.dp.toPx(), extra.chartGrid, measurer, labelStyle, if (old != null) fadeInAlpha(t) else 1f, hide)
        xLabels(points.map { it.snapshot.at }, ::px, bottom, 0f, plot.right, measurer, labelStyle, if (old != null) fadeInAlpha(t) else 1f)

        // The starting value, for "above the line is a gain".
        if (points.size > 1) {
            val y = if (old != null) sy(old.start + (ny(start) - old.start) * t) else py(start)
            drawLine(cs.outline, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
        }
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.snapshot.at), my(p.snapshot.at, p.netWorth)) else path.lineTo(px(p.snapshot.at), my(p.snapshot.at, p.netWorth)) }
        clipRect(right = plot.left + (plot.right - plot.left) * reveal.value + 8.dp.toPx()) {
            if (points.size > 1) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(px(maxX), bottom)
                    lineTo(px(minX), bottom)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.22f), line.copy(alpha = 0f)), startY = top, endY = bottom))
                drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (points.size <= 40) {
                for (p in points) {
                    val c = Offset(px(p.snapshot.at), my(p.snapshot.at, p.netWorth))
                    drawCircle(cs.surfaceContainer, 3.5.dp.toPx(), c)
                    drawCircle(line, 3.5.dp.toPx(), c, style = Stroke(1.8.dp.toPx()))
                }
            }
        }

        val pick = selected?.let { points.getOrNull(it) } ?: return@Canvas
        val x = px(pick.snapshot.at)
        val y = py(pick.netWorth)
        drawLine(cs.outline, Offset(x, top - 4.dp.toPx()), Offset(x, bottom), strokeWidth = 1.dp.toPx())
        drawCircle(line.copy(alpha = 0.25f), 10.dp.toPx(), Offset(x, y))
        drawCircle(line, 5.dp.toPx(), Offset(x, y))
        drawCircle(cs.surfaceContainer, 2.dp.toPx(), Offset(x, y))
        bubble(
            x,
            listOf(
                Dates.date(pick.snapshot.at) to TextStyle(fontSize = 10.sp, color = cs.inverseOnSurface.copy(alpha = 0.75f)),
                (if (hide) MASKED else Money.format(pick.netWorth)) to TextStyle(fontSize = 13.sp, color = cs.inverseOnSurface, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            ),
            measurer, cs.inverseSurface,
        )
    }
}

/**
 * What one draw of [NetWorthChart] showed, on a 0..1 square: the line, the ticks, the dates and
 * the starting value. The next range morphs out of it. [gutter] is filled in by the draw.
 */
private class TrendFrame(
    val shape: List<Pair<Float, Float>>,
    val ticks: List<Pair<Float, Long>>,
    val times: List<Long>,
    val start: Float,
) {
    var gutter = 0f

    fun nx(at: Long): Float = if (times.first() == times.last()) 0.5f else (at - times.first()).toFloat() / (times.last() - times.first())

    /** The line's height at [x] across the plot, straight between its points and flat past its ends. */
    fun heightAt(x: Float): Float {
        if (x <= shape.first().first) return shape.first().second
        if (x >= shape.last().first) return shape.last().second
        val i = shape.indexOfFirst { it.first >= x }
        val (x0, y0) = shape[i - 1]
        val (x1, y1) = shape[i]
        return if (x1 == x0) y1 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }
}

/**
 * Each stocktake as a pair of bars from zero: assets up, debts down, with net worth as the line
 * between them. Shows why net worth moved without stacking three lines on one axis.
 */
@Composable
fun BalanceBars(points: List<NetWorthPoint>, selected: Int?, onSelect: (Int?) -> Unit, assetsColor: Color, debtsColor: Color, netColor: Color, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val hide = LocalHideAmounts.current
    val extra = AppTheme.extra
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = cs.onSurfaceVariant, fontFeatureSettings = "tnum")
    val grow = remember(points.size, points.firstOrNull()?.snapshot?.id) { Animatable(0f) }
    LaunchedEffect(grow) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val plot = remember { PlotX() }
    if (points.isEmpty()) return
    val n = points.size
    // Evenly spaced by stocktake, not by date: bars need room, and the order is what matters.
    fun cx(i: Int): Float {
        val slot = (plot.right - plot.left) / n
        return plot.left + slot * (i + 0.5f)
    }
    val top0 = points.maxOf { max(it.assets, it.netWorth) }.coerceAtLeast(1L)
    val bottom0 = points.maxOf { max(it.liabilities, -it.netWorth) }.coerceAtLeast(0L)

    Canvas(modifier.scrub(n, plot, ::cx, selected, onSelect)) {
        val top = 8.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val ticks = buildList {
            add(0L); add(top0)
            if (bottom0 > 0) add(-bottom0)
        }
        val gutter = ticks.maxOf { measurer.measure(axis(it, hide), labelStyle).size.width } + 10.dp.toPx()
        plot.left = 4.dp.toPx()
        plot.right = size.width - gutter
        val total = (top0 + bottom0).toFloat()
        val zero = top + (bottom - top) * (top0 / total)
        fun py(v: Long) = zero - (bottom - top) * (v / total)
        for (t in ticks) {
            val y = py(t)
            drawLine(if (t == 0L) cs.outline else extra.chartGrid, Offset(0f, y), Offset(plot.right, y), strokeWidth = if (t == 0L) 1.2f else 1f)
            val label = measurer.measure(axis(t, hide), labelStyle)
            drawText(label, topLeft = Offset(size.width - label.size.width, y - label.size.height / 2f))
        }
        xLabels(listOf(0L, (n - 1).toLong()).distinct().map { points[it.toInt()].snapshot.at }, { t -> cx(points.indexOfFirst { it.snapshot.at == t }) }, bottom, 0f, plot.right, measurer, labelStyle)

        val slot = (plot.right - plot.left) / n
        val barW = (slot * 0.56f).coerceIn(2.dp.toPx(), 18.dp.toPx())
        val r = CornerRadius(min(barW / 2, 4.dp.toPx()))
        points.forEachIndexed { i, p ->
            val dim = selected != null && selected != i
            val a = if (dim) 0.35f else 1f
            val x = cx(i) - barW / 2
            val up = (zero - py(p.assets)) * grow.value
            val down = (py(-p.liabilities) - zero) * grow.value
            if (up > 0f) drawRoundRect(assetsColor.copy(alpha = 0.85f * a), Offset(x, zero - up), Size(barW, up), r)
            if (down > 0f) drawRoundRect(debtsColor.copy(alpha = 0.85f * a), Offset(x, zero), Size(barW, down), r)
        }
        // Net worth: the line through the middle of each pair.
        if (n > 1) {
            val path = Path()
            points.forEachIndexed { i, p ->
                val y = zero + (py(p.netWorth) - zero) * grow.value
                if (i == 0) path.moveTo(cx(i), y) else path.lineTo(cx(i), y)
            }
            drawPath(path, netColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (n <= 40 || selected != null) {
            points.forEachIndexed { i, p ->
                if (n > 40 && i != selected) return@forEachIndexed
                val c = Offset(cx(i), zero + (py(p.netWorth) - zero) * grow.value)
                drawCircle(cs.surfaceContainer, if (i == selected) 5.dp.toPx() else 3.dp.toPx(), c)
                drawCircle(netColor, if (i == selected) 5.dp.toPx() else 3.dp.toPx(), c, style = Stroke(if (i == selected) 2.5.dp.toPx() else 1.6.dp.toPx()))
            }
        }
    }
}
