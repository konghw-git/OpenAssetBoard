package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.paint
import androidx.compose.ui.layout.ContentScale
import com.assetboard.R
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The launcher icon under a few launcher masks, and the themed one, for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class IconShotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun launcherIcon() {
        compose.setContent {
            Row(Modifier.background(Color(0xFFF5F3EE)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf<Shape>(CircleShape, RoundedCornerShape(28)).forEach { shape ->
                    Box(Modifier.size(110.dp).clip(shape).paint(painterResource(R.drawable.ic_launcher_background), contentScale = ContentScale.FillBounds), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(110.dp * 108f / 72f))
                    }
                }
                Box(Modifier.size(110.dp).clip(CircleShape).background(Color(0xFFDCE5F5)), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.requiredSize(110.dp * 108f / 72f), colorFilter = ColorFilter.tint(Color(0xFF14243D)))
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/00-launcher-icon.png", roborazziOptions = SHOT_OPTIONS)
    }
}
