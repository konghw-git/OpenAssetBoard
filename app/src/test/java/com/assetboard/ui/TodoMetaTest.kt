package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.assetboard.data.ThemeMode
import com.assetboard.data.Todo
import com.assetboard.data.TodoKind
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.RowExtras
import com.assetboard.ui.todo.TodoRow
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h880dp-xxhdpi")
class TodoMetaTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun deadlineAndEarlyReminderRemainReadableOnANarrowPhone() {
        val today = LocalDate.parse("2026-10-24")
        val t = Todo("t", TodoKind.ORDINARY, "Meeting", planDate = today.toString(),
            deadline = today.minusDays(120).toString(), remindTime = "14:00", remindBefore = 1440, createdAt = 0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
              AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                TodoRow(t, false, today, RowExtras(), {}, {}, modifier = Modifier.width(288.dp))
              }
            }
        }
        for (text in listOf("已超期 120 天", "14:00 · 提前1天")) {
            val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            assertEquals("The tag is truncated: $text", text.length, layout.getLineEnd(0))
            assertTrue("The tag is clipped: $text", layout.size.width + 1f >= layout.multiParagraph.intrinsics.maxIntrinsicWidth)
        }
    }
}
