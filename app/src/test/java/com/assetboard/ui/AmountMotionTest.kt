package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.assetboard.ui.components.animatedCents
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AmountMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstFrameIsAccurateAndInterruptedUpdatesReachTheLatestAmount() {
        val value = mutableLongStateOf(123_456L)
        compose.mainClock.autoAdvance = false
        compose.setContent { Text(animatedCents(value.longValue).toString()) }
        compose.onNodeWithText("123456").assertExists()
        compose.runOnIdle { value.longValue = 876_543L }
        compose.mainClock.advanceTimeBy(120)
        compose.runOnIdle { value.longValue = -500L }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("-500").assertExists()
    }
}
