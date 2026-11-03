package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.assetboard.data.DeckMode
import com.assetboard.data.ThemeMode
import com.assetboard.ui.components.CardDeckContent
import com.assetboard.ui.components.DeckCard
import com.assetboard.ui.theme.AssetBoardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The deck turns like a drum: swiping past either end wraps around, while a deck of two stays
 * put at its ends. Tapping the card in front picks it; a neighbour only turns the deck.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class DeckPagerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val picks = mutableListOf<String>()

    private fun card(key: String, title: String) = DeckCard(
        key = key, title = title, count = 1, accent = Color(0xFF23864D),
        icon = Icons.Outlined.FormatListBulleted, countLabel = "1 项未完成",
    )

    private fun show(
        cards: List<DeckCard>,
        selected: String? = null,
        rememberedMode: DeckMode? = null,
        onModeChange: ((DeckMode) -> Unit)? = null,
    ) {
        picks.clear()
        compose.setContent {
            AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                CardDeckContent(
                    title = "清单", cards = cards, selected = selected,
                    onPick = { picks += it }, onDismiss = {},
                    rememberedMode = rememberedMode, onModeChange = onModeChange,
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_600)
        compose.waitForIdle()
    }

    private fun front(title: String) {
        compose.onNodeWithText("打开「$title」").assertExists()
    }

    private fun swipeFront(key: String, back: Boolean = false) {
        compose.onNodeWithTag("deck:$key").performTouchInput { if (back) swipeRight() else swipeLeft() }
        compose.mainClock.advanceTimeBy(900)
        compose.waitForIdle()
    }

    @Test fun theDeckWrapsAroundAtBothEnds() {
        show(
            listOf(
                card("a", "甲"), card("b", "乙"), card("c", "丙"), card("d", "丁"),
            ),
        )
        front("甲")
        swipeFront("a")
        front("乙")
        swipeFront("b")
        front("丙")
        swipeFront("c")
        front("丁")
        swipeFront("d")
        front("甲")
        // And the other way round: from the first card back to the last.
        swipeFront("a", back = true)
        front("丁")
    }

    @Test fun twoCardsDoNotLoop() {
        show(listOf(card("a", "甲"), card("b", "乙")))
        front("甲")
        swipeFront("a")
        front("乙")
        swipeFront("b")
        front("乙")
    }

    @Test fun oneCardStaysPut() {
        show(listOf(card("a", "甲")))
        front("甲")
        swipeFront("a")
        front("甲")
        compose.onNodeWithTag("deck:a").performClick()
        assertEquals(listOf("a"), picks)
    }

    @Test fun tappingTheFrontCardPicksIt() {
        show(listOf(card("a", "甲"), card("b", "乙"), card("c", "丙")))
        compose.onNodeWithTag("deck:a").performClick()
        assertEquals(listOf("a"), picks)
    }

    @Test fun tappingANeighbourTurnsTheDeckWithoutPicking() {
        show(listOf(card("a", "甲"), card("b", "乙"), card("c", "丙")))
        front("甲")
        compose.onNodeWithTag("deck:b").performClick()
        compose.mainClock.advanceTimeBy(900)
        compose.waitForIdle()
        front("乙")
        assertTrue("a neighbour tap must not pick", picks.isEmpty())
    }

    @Test fun theDeckOpensOnTheSelectedCard() {
        show(listOf(card("a", "甲"), card("b", "乙"), card("c", "丙")), selected = "c")
        front("丙")
    }

    @Test fun aLongDeckOpensAsAGridAndCanReturnToTheCarousel() {
        show((0 until 7).map { card("k$it", "卡$it") })
        compose.onNodeWithText("点一张卡直接打开").assertExists()
        compose.onNodeWithContentDescription("环绕展示").performClick()
        compose.onNodeWithText("左右滑动浏览，点中间那张打开").assertExists()
    }

    @Test fun aRememberedListModeOpensEvenAShortDeckAsAList() {
        show(listOf(card("a", "甲"), card("b", "乙"), card("c", "丙")), rememberedMode = DeckMode.LIST)
        compose.onNodeWithText("点一张卡直接打开").assertExists()
    }

    @Test fun aRememberedCarouselModeKeepsALongDeckOnTheCarousel() {
        show((0 until 7).map { card("k$it", "卡$it") }, rememberedMode = DeckMode.CAROUSEL)
        compose.onNodeWithText("左右滑动浏览，点中间那张打开").assertExists()
    }

    @Test fun switchingTheModeAsksForItToBeRemembered() {
        val remembered = mutableListOf<DeckMode>()
        show(listOf(card("a", "甲"), card("b", "乙"), card("c", "丙")), onModeChange = { remembered += it })
        compose.onNodeWithContentDescription("网格展示").performClick()
        compose.onNodeWithContentDescription("环绕展示").performClick()
        assertEquals(listOf(DeckMode.LIST, DeckMode.CAROUSEL), remembered)
    }
}
