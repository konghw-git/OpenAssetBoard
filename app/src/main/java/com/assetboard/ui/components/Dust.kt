package com.assetboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deleting by swipe turns the card into dust: [dissolve] snapshots each card, hides it, and
 * [DustHost] blows the snapshot away in small squares on an overlay above the whole app. The
 * list can drop the row at once and close the gap while the dust is still drifting.
 */
@Stable
class DustState {
    internal val bursts = mutableStateListOf<Burst>()
    /** Keys whose card is dust now; each leaves when its burst ends or its row comes back. */
    private val hidden = mutableStateMapOf<Any, Burst>()
    private val targets = HashMap<Any, Target>()

    internal class Target(val layer: GraphicsLayer) {
        var coords: LayoutCoordinates? = null
    }

    /** Hidden cards draw nothing; they are about to leave the list. */
    fun isHidden(key: Any): Boolean = key in hidden

    /**
     * A card composed under [key]. If that key is still hidden, this is the row coming back
     * (an undo, a restore from the trash) while its old dust drifts: it shows at once.
     */
    internal fun register(key: Any, target: Target) {
        targets[key] = target
        cameBack(key)
    }

    internal fun cameBack(key: Any) {
        hidden.remove(key)
    }
    internal fun unregister(key: Any, target: Target) { if (targets[key] === target) targets.remove(key) }

    /**
     * Snapshots and hides the cards of [keys] that are on screen, then starts their dust,
     * drifting toward [direction] (-1 left, 1 right). Returns once they are hidden, and a moment
     * later when there was something to dissolve, so the rows below close up over dust rather
     * than over a card still whole.
     */
    suspend fun dissolve(keys: Collection<Any>, direction: Float = -1f, cell: Float = 9f) {
        var any = false
        for (key in keys) {
            val t = targets[key] ?: continue
            val c = t.coords?.takeIf { it.isAttached } ?: continue
            val image = try {
                t.layer.toImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (image.width == 0 || image.height == 0) continue
            start(Burst(key, image, c.positionInRoot(), particles(image.width, image.height, cell, direction), cell))
            any = true
        }
        if (any) delay(HOLD_MS)
    }

    /** [b]'s card hides and its dust starts. */
    internal fun start(b: Burst) {
        bursts += b
        hidden[b.key] = b
    }

    /**
     * The dust of [b] has settled. Its card shows again: by now the row has left the list, or
     * the delete failed and it should be seen.
     */
    internal fun finished(b: Burst) {
        bursts.remove(b)
        if (hidden[b.key] === b) hidden.remove(b.key)
    }
}

val LocalDust = staticCompositionLocalOf<DustState?> { null }

/** One snapshot breaking up: a particle per [cell]-pixel square, laid out in [p]. */
internal class Burst(val key: Any, val image: ImageBitmap, val origin: Offset, val p: FloatArray, val cell: Float) {
    var start = -1L
}

// Per particle: source x, source y, delay (s), x speed, y speed (px/s), life (s), wobble phase.
private const val STRIDE = 7
private const val SWEEP = 0.42f
private const val JITTER = 0.14f
private const val LIFE_MIN = 0.45f
private const val LIFE_MAX = 0.75f
private const val HOLD_MS = 300L
internal const val DUST_SECONDS = SWEEP + JITTER + LIFE_MAX

private fun particles(w: Int, h: Int, cell: Float, direction: Float): FloatArray {
    val cols = max(1, (w / cell).toInt())
    val rows = max(1, (h / cell).toInt())
    val out = FloatArray(cols * rows * STRIDE)
    val rnd = Random(w * 31 + h)
    var i = 0
    for (cy in 0 until rows) for (cx in 0 until cols) {
        val u = cx.toFloat() / cols
        // The side the dust blows toward goes first, so nothing drifts over card still intact.
        val lead = if (direction < 0) u else 1f - u
        out[i] = cx * cell
        out[i + 1] = cy * cell
        out[i + 2] = lead * SWEEP + rnd.nextFloat() * JITTER
        out[i + 3] = direction * (cell * (8f + rnd.nextFloat() * 26f)) + (rnd.nextFloat() - 0.5f) * cell * 10f
        out[i + 4] = -cell * (4f + rnd.nextFloat() * 22f)
        out[i + 5] = LIFE_MIN + rnd.nextFloat() * (LIFE_MAX - LIFE_MIN)
        out[i + 6] = rnd.nextFloat() * 2f * PI.toFloat()
        i += STRIDE
    }
    return out
}

/** The overlay that draws every burst; place it above the app's content. It takes no touches. */
@Composable
fun DustHost(state: DustState, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state) {
        while (true) {
            snapshotFlow { state.bursts.size }.first { it > 0 }
            while (state.bursts.isNotEmpty()) {
                withFrameNanos { now ->
                    frame = now
                    for (b in state.bursts.toList()) {
                        if (b.start < 0) b.start = now
                        if ((now - b.start) / 1e9f > DUST_SECONDS) state.finished(b)
                    }
                }
            }
        }
    }
    val wobble = with(LocalDensity.current) { 3f * density }
    // Bursts are placed in root coordinates; a host not at the root (inside a sheet) shifts back.
    var at by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.fillMaxSize().onGloballyPositioned { at = it.positionInRoot() }) {
        val now = frame
        translate(-at.x, -at.y) {
        for (b in state.bursts) {
            val t = if (b.start < 0 || now < b.start) 0f else (now - b.start) / 1e9f
            val p = b.p
            val cell = b.cell.roundToInt()
            var i = 0
            while (i < p.size) {
                val local = t - p[i + 2]
                val life = p[i + 5]
                if (local < life) {
                    val sx = p[i].toInt()
                    val sy = p[i + 1].toInt()
                    if (local <= 0f) {
                        drawImage(b.image, IntOffset(sx, sy), IntSize(cell, cell), IntOffset((b.origin.x + sx).roundToInt(), (b.origin.y + sy).roundToInt()), IntSize(cell, cell))
                    } else {
                        val k = local / life
                        // Eased out drift with a little sideways flutter; shrinks and fades.
                        val ease = 1f - (1f - k) * (1f - k)
                        val x = b.origin.x + sx + p[i + 3] * life * ease + sin(p[i + 6] + k * 9f) * wobble * k
                        val y = b.origin.y + sy + p[i + 4] * life * ease
                        val s = (cell * (1f - 0.55f * k)).roundToInt().coerceAtLeast(1)
                        val alpha = (1f - k * k).coerceIn(0f, 1f)
                        drawImage(b.image, IntOffset(sx, sy), IntSize(cell, cell), IntOffset(x.roundToInt(), y.roundToInt()), IntSize(s, s), alpha = alpha)
                    }
                }
                i += STRIDE
            }
        }
        }
    }
}

/**
 * Lets [LocalDust] snapshot this content under [key] and hides it while it turns to dust.
 * Put it inside any sideways offset, so the dust starts where the card is.
 */
fun Modifier.dissolvable(key: Any): Modifier = composed {
    val dust = LocalDust.current ?: return@composed this
    val layer = rememberGraphicsLayer()
    val target = remember(layer) { DustState.Target(layer) }
    DisposableEffect(dust, key, target) {
        dust.register(key, target)
        onDispose { dust.unregister(key, target) }
    }
    this
        .onGloballyPositioned { target.coords = it }
        .graphicsLayer { alpha = if (dust.isHidden(key)) 0f else 1f }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}
