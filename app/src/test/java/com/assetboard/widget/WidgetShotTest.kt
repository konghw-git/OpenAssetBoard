package com.assetboard.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.core.app.ApplicationProvider
import com.assetboard.PinnedClock
import com.assetboard.data.Countdown
import com.assetboard.ui.SHOT_OPTIONS
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * The widgets as the launcher draws them, at the sizes people give them: Glance composes the
 * RemoteViews a launcher would get, they are inflated here and drawn over a wallpaper-like
 * gradient. Also at a font scale of 1.3, where a layout that fits at 1.0 can push its last row
 * out of the cell. Lists (LazyColumn) need the launcher's adapter and come out empty here, so
 * the countdown is drawn alone; the todo and note widgets are mostly lists and are left to a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class WidgetShotTest {
    @get:Rule val clock = PinnedClock()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** [told] is the size the widget is told it has, when the launcher reports another than it draws. */
    private fun shot(name: String, w: Int, h: Int, scale: Float = 1f, told: DpSize? = null, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(scale)
        try {
            val suffix = (if (scale == 1f) "" else "-f${(scale * 100).toInt()}") + (told?.let { "-told${it.width.value.toInt()}x${it.height.value.toInt()}" } ?: "")
            render(w, h, told ?: DpSize(w.dp, h.dp), content).captureRoboImage("screenshots/80-widget-$name-${w}x$h$suffix.png", SHOT_OPTIONS)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun render(w: Int, h: Int, told: DpSize, content: @Composable () -> Unit): Bitmap {
        val probe = object : GlanceAppWidget() {
            override val sizeMode = SizeMode.Exact
            override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { content() }
        }
        val rv = runBlocking { probe.compose(context, size = told) }
        val d = context.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val frame = FrameLayout(context)
        frame.addView(rv.apply(context, frame), FrameLayout.LayoutParams((w * d).toInt(), (h * d).toInt()).apply { setMargins(pad, pad, pad, pad) })
        frame.measure(
            View.MeasureSpec.makeMeasureSpec((w * d).toInt() + 2 * pad, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((h * d).toInt() + 2 * pad, View.MeasureSpec.EXACTLY),
        )
        frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
        val bmp = createBitmap(frame.measuredWidth, frame.measuredHeight)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), 0xFF2B3A55.toInt(), 0xFFB88A6A.toInt(), Shader.TileMode.CLAMP)
        })
        frame.draw(c)
        return bmp
    }

    private val one = listOf(Countdown("a", "甜甜恋爱纪念日", "2027-05-20", yearly = false, pinned = true, createdAt = 0, updatedAt = 0))

    @Test fun countdown() {
        shot("countdown", 150, 150) { CountdownContent(one) }
        shot("countdown", 355, 165) { CountdownContent(one) }
        shot("countdown", 330, 440) { CountdownContent(one) }
        shot("countdown", 150, 150, scale = 1.3f) { CountdownContent(one) }
        shot("countdown", 355, 165, scale = 1.3f) { CountdownContent(one) }
        // A vivo launcher told a 4×2 countdown about two thirds of the width it drew.
        shot("countdown", 370, 165, told = DpSize(245.dp, 150.dp)) { CountdownContent(one) }
    }

    private val board = Board(
        netWorth = 1_774_682, assets = 3_774_682, debts = 2_000_000, change = 0,
        lastStocktakeAt = PinnedClock.PINNED.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        line = listOf(1_200_000L, 1_210_000, 3_400_000, 3_420_000, 3_380_000, 1_500_000, 3_300_000, 3_350_000, 1_800_000, 1_790_000, 1_774_682, 1_774_682),
        groups = listOf(Triple("投资账户", 3_200_000L, 4), Triple("负债账户", 2_000_000L, 3), Triple("虚拟账户", 308_021L, 2), Triple("储蓄账户", 271_366L, 1)),
        goal = 5_000_000,
    )

    @Test fun netWorth() {
        val draw: @Composable () -> Unit = { AssetContent(board, hide = false, upIsRed = true) }
        shot("asset", 150, 150, content = draw)
        shot("asset", 355, 110, content = draw)
        shot("asset", 355, 165, content = draw)
        shot("asset", 165, 330, content = draw)
        shot("asset", 355, 260, content = draw)
        shot("asset", 330, 440, content = draw)
        shot("asset", 355, 110, scale = 1.3f, content = draw)
        shot("asset", 355, 165, scale = 1.3f, content = draw)
        shot("asset", 355, 260, scale = 1.3f, content = draw)
        shot("asset", 370, 260, told = DpSize(245.dp, 230.dp), content = draw)
    }
}
