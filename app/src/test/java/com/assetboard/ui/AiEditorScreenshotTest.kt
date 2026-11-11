package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.assetboard.CloseTestContainers
import com.assetboard.PinnedClock
import com.assetboard.TestContainers
import com.assetboard.data.ThemeMode
import com.assetboard.data.ai.AiGateway
import com.assetboard.data.ai.ChatTurn
import com.assetboard.ui.diary.DiaryScreen
import com.assetboard.ui.theme.AssetBoardTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class AiEditorScreenshotTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: TestRule = RuleChain.outerRule(PinnedClock()).around(composeRule).around(CloseTestContainers())
    private val compose get() = composeRule

    private fun streaming(dark: Boolean) {
        val ai = object : AiGateway {
            override suspend fun complete(system: String, user: String): String = error("Expected streaming")
            override suspend fun streamChat(system: String, history: List<ChatTurn>, onText: suspend (String) -> Unit): String {
                onText("　　早上沿着河边跑了五公里，回家吃了早餐。\n\n　　下午读完了第三章，记下了几个想继续琢磨的问题。\n\n　　晚上整理了今天的收支，")
                awaitCancellation()
            }
        }
        val c = TestContainers.open(ApplicationProvider.getApplicationContext(), ai)
        c.settings.setBool(c.settings.networkEnabled, false)
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides c) {
                AssetBoardTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, upIsRed = true) {
                    DiaryScreen(null, {}, {}, {}, {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("今天过得怎么样？")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("AI生成日记").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("早上沿着河边", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("screenshots/diary-ai-stream-${if (dark) "dark" else "light"}.png", roborazziOptions = SHOT_OPTIONS)
    }

    @Test fun diaryStreamingLight() = streaming(false)
    @Test fun diaryStreamingDark() = streaming(true)
}
