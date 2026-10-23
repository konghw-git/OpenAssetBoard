package com.assetboard.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.paint
import com.assetboard.R
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The README's banner and icon, into screenshots/ (converted to WebP for docs/images/ by
 * hand). The banner shows the screens rendered by the other screenshot tests, so run those first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandShotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val green = Color(0xFF123F36)
    private val paper = Color(0xFFF7F5EF)
    private val gold = Color(0xFFE2B45E)

    @Test @Config(sdk = [35], application = Application::class, qualifiers = "w512dp-h512dp-mdpi")
    fun icon() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Transparent), contentAlignment = Alignment.Center) {
                AppIcon(512.dp, corner = 112.dp)
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/brand-icon.png", roborazziOptions = SHOT_OPTIONS)
    }

    @Test @Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h640dp-xhdpi")
    fun banner() {
        val shots = listOf("01-overview", "20-todo-home", "50-notes").map { File("screenshots/$it.png") }
        assumeTrue("render the screenshot tests first", shots.all { it.exists() })
        // Made of the other screens, which are checked themselves; a fresh record may also run it
        // before them, leaving no baseline to check it against.
        assumeTrue("the banner is not checked", System.getProperty("roborazzi.test.verify") != "true")
        val images = shots.map { BitmapFactory.decodeFile(it.path).asImageBitmap() }
        compose.setContent {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF0E3830), Color(0xFF1B5246), Color(0xFF14463C)), start = Offset.Zero, end = Offset(2560f, 1280f)),
                ),
            ) {
                // A warm glow behind the name, the icon's sun spread over the page.
                Box(
                    Modifier.size(900.dp).offset((-260).dp, (-220).dp)
                        .background(Brush.radialGradient(listOf(gold.copy(alpha = 0.16f), Color.Transparent))),
                )
                Row(Modifier.fillMaxSize().padding(start = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(560.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(112.dp, corner = 26.dp, shadow = true)
                            Spacer(Modifier.width(28.dp))
                            Column {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text("日盈", color = paper, fontSize = 76.sp, fontWeight = FontWeight.Bold, lineHeight = 80.sp)
                                    Text("  Gain", color = gold, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
                                }
                                Text("日日行动，日日有盈", color = paper.copy(alpha = 0.92f), fontSize = 26.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.height(30.dp))
                        Text("资产 · 待办 · 日记 · 笔记，全部留在本机", color = paper.copy(alpha = 0.85f), fontSize = 22.sp)
                        Spacer(Modifier.height(22.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("本地优先", "无账号", "无广告", "Android 10+").forEach {
                                Text(
                                    it, color = paper, fontSize = 17.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(paper.copy(alpha = 0.08f))
                                        .border(1.dp, paper.copy(alpha = 0.28f), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Text("OpenAssetBoard  ·  local-first, open source, no cloud", color = paper.copy(alpha = 0.55f), fontSize = 18.sp)
                    }
                }
                // Three phones, staggered and cut by the bottom edge, clear of the text on the left.
                Phone(images[2], 232.dp, Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = 168.dp).rotate(4f))
                Phone(images[1], 232.dp, Modifier.align(Alignment.TopEnd).offset(x = (-200).dp, y = 112.dp))
                Phone(images[0], 232.dp, Modifier.align(Alignment.TopEnd).offset(x = (-386).dp, y = 168.dp).rotate(-4f))
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/brand-banner.png", roborazziOptions = SHOT_OPTIONS)
    }

    @androidx.compose.runtime.Composable
    private fun AppIcon(size: Dp, corner: Dp, shadow: Boolean = false) {
        val shape = RoundedCornerShape(corner)
        Box(
            Modifier.size(size).then(if (shadow) Modifier.shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
                .clip(shape).paint(painterResource(R.drawable.ic_launcher_background), contentScale = ContentScale.FillBounds),
            contentAlignment = Alignment.Center,
        ) {
            // The adaptive foreground is a 108 grid with its art inside 72: drawn larger, clipped.
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(size * 108f / 72f))
        }
    }

    @androidx.compose.runtime.Composable
    private fun Phone(image: androidx.compose.ui.graphics.ImageBitmap, width: Dp, modifier: Modifier) {
        val shape = RoundedCornerShape(30.dp)
        val height = width * image.height.toFloat() / image.width
        Box(
            modifier.size(width + 12.dp, height + 12.dp).shadow(30.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape).background(Color(0xFF0B0F0E)).padding(6.dp),
        ) {
            Image(image, null, Modifier.size(width, height).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop)
        }
    }
}
