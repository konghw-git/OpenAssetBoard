package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.GridView
import com.assetboard.data.ThemeMode
import com.assetboard.ui.components.CardDeckContent
import com.assetboard.ui.components.DeckCard
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.theme.AssetBoardTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 3D deck of cards that stands in for the lists and the note topics, on its own. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class DeckShotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            AssetBoardTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, upIsRed = true) { content() }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = SHOT_OPTIONS)
    }

    @Composable
    private fun card(key: String, title: String, count: Int, group: Int, footnote: String?, icon: ImageVector) = DeckCard(
        key = key, title = title, count = count, accent = AppTheme.extra.group(group), icon = icon, footnote = footnote,
    )

    @Test fun todoLists() {
        shot("30-list-deck") {
            CardDeckContent(
                title = "清单",
                cards = listOf(
                    card("a", "搬家", 4, 0, "3 个分区", androidx.compose.material.icons.Icons.Outlined.FormatListBulleted),
                    card("b", "论文", 2, 1, "截止还剩 2 天 · 2 个分区", androidx.compose.material.icons.Icons.Outlined.FormatListBulleted),
                    card("c", "旅行", 1, 2, "截止 11月的小长假", androidx.compose.material.icons.Icons.Outlined.FormatListBulleted),
                    card("d", "英语", 0, 3, null, androidx.compose.material.icons.Icons.Outlined.FormatListBulleted),
                ),
                selected = "b",
                onPick = {},
                onDismiss = {},
                hint = "左右滑动挑一张清单，点一下打开",
                createLabel = "新建清单",
                onCreate = {},
            )
        }
    }

    @Test fun noteTopicsInTheDark() {
        shot("31-topic-deck-dark", dark = true) {
            CardDeckContent(
                title = "主题",
                cards = listOf(
                    card("all", "全部", 5, 0, "所有主题与未归类的笔记", androidx.compose.material.icons.Icons.Outlined.GridView),
                    card("t1", "读书", 2, 1, "主题", androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Label),
                    card("t2", "健身", 1, 2, "主题", androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Label),
                    card("t3", "投资", 2, 3, "主题", androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Label),
                ),
                selected = "all",
                onPick = {},
                onDismiss = {},
                hint = "左右滑动挑一个主题，点一下筛选",
                createLabel = "新建主题",
                onCreate = {},
                manageLabel = "管理主题",
                onManage = {},
            )
        }
    }
}
