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
    private fun card(
        key: String,
        title: String,
        count: Int,
        group: Int,
        footnote: String?,
        icon: ImageVector,
        countLabel: String? = null,
        preview: List<String> = emptyList(),
    ) = DeckCard(
        key = key, title = title, count = count, accent = AppTheme.extra.group(group), icon = icon, footnote = footnote,
        countLabel = countLabel, preview = preview, previewEmpty = "里面还没有内容",
    )

    @Test fun todoLists() {
        shot("30-list-deck") {
            CardDeckContent(
                title = "清单",
                cards = listOf(
                    card("a", "搬家", 4, 0, "3 个分区", androidx.compose.material.icons.Icons.Outlined.FormatListBulleted,
                        countLabel = "4 项未完成", preview = listOf("打包书架", "预约搬家车", "改地址")),
                    card("b", "论文", 2, 1, "截止还剩 2 天 · 2 个分区", androidx.compose.material.icons.Icons.Outlined.FormatListBulleted,
                        countLabel = "2 项未完成", preview = listOf("第三章初稿", "参考文献")),
                    card("c", "旅行", 1, 2, "截止 11月的小长假", androidx.compose.material.icons.Icons.Outlined.FormatListBulleted,
                        countLabel = "1 项未完成", preview = listOf("订机票")),
                    card("d", "英语", 0, 3, null, androidx.compose.material.icons.Icons.Outlined.FormatListBulleted,
                        countLabel = "没有未完成"),
                ),
                selected = "b",
                onPick = {},
                onDismiss = {},
                hint = "左右滑动浏览，点中间那张打开",
                createLabel = "新建清单",
                onCreate = {},
            )
        }
    }

    /** Data can arrive after the dialog opens; a deck that ends up with more than six still pages. */
    @Test fun aDeckThatLoadsLateStillOpensAsAPage() {
        val loaded = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                val cards = if (loaded.value) {
                    (0 until 7).map { i ->
                        card("t$i", "主题$i", i, i % 4, "主题", androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Label)
                    }
                } else {
                    emptyList()
                }
                CardDeckContent(
                    title = "主题", cards = cards, selected = null, onPick = {}, onDismiss = {},
                    hint = "左右滑动挑一张主题，点一下筛选",
                    gridHint = "点一张卡直接筛选",
                    createLabel = "新建主题", onCreate = {},
                )
            }
        }
        compose.runOnIdle { loaded.value = true }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("点一张卡直接筛选")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun noteTopicsInTheDark() {
        shot("31-topic-deck-dark", dark = true) {
            CardDeckContent(
                title = "主题",
                cards = listOf(
                    card("all", "全部", 5, 0, "所有主题与未归类的笔记", androidx.compose.material.icons.Icons.Outlined.GridView,
                        countLabel = "5 篇笔记", preview = listOf("一本书的摘要", "早睡的真正障碍", "火锅局的启发")),
                    card("t1", "读书", 2, 1, "主题", androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Label,
                        countLabel = "2 篇笔记", preview = listOf("一本书的摘要", "读到一半的书")),
                    card("t2", "健身", 1, 2, "主题", androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Label,
                        countLabel = "1 篇笔记", preview = listOf("早睡的真正障碍")),
                    card("t3", "投资", 2, 3, "主题", androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Label,
                        countLabel = "2 篇笔记", preview = listOf("火锅局的启发", "定投清单")),
                ),
                selected = "all",
                onPick = {},
                onDismiss = {},
                hint = "左右滑动浏览，点中间那张筛选",
                gridHint = "点一张卡直接筛选",
                openVerb = "筛选",
                createLabel = "新建主题",
                onCreate = {},
                manageLabel = "管理主题",
                onManage = {},
            )
        }
    }

    /** Nine topics are a page of cards, not a carousel: everything is visible at once. */
    @Test fun manyTopicsAsAPage() {
        val names = listOf("读书", "健身", "投资", "写作", "旅行", "摄影", "理财", "烹饪", "英语")
        shot("32-topic-deck-grid", dark = true) {
            CardDeckContent(
                title = "主题",
                cards = names.mapIndexed { i, name ->
                    val count = (i % 4) + 1
                    card("t$i", name, count, i, "主题", androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Label,
                        countLabel = "$count 篇笔记", preview = listOf("一篇 $name 的笔记"))
                },
                selected = "t2",
                onPick = {},
                onDismiss = {},
                hint = "左右滑动浏览，点中间那张筛选",
                gridHint = "点一张卡直接筛选",
                openVerb = "筛选",
                createLabel = "新建主题",
                onCreate = {},
                manageLabel = "管理主题",
                onManage = {},
            )
        }
    }
}
