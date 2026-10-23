package com.assetboard.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assetboard.data.ThemeMode
import com.assetboard.domain.Habit
import com.assetboard.ui.theme.AssetBoardTheme
import com.assetboard.ui.todo.HabitBattery
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A habit's name sits in the middle of its cell, on the phone and in the unfolded layout, where
 * the side column makes the battery, and so each cell, short. There the name's line box was once
 * taller than the cell and was cut off below, so the text sat high.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w850dp-h900dp-xhdpi")
class HabitCellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var height by mutableStateOf(0.dp)

    private fun show(h: Dp) {
        height = h
        compose.setContent {
            AssetBoardTheme(ThemeMode.LIGHT, upIsRed = true) {
                HabitBattery(Habit.DEFAULTS, setOf(Habit.WORKOUT.key), {}, {}, Modifier.width(150.dp).height(height))
            }
        }
        compose.waitForIdle()
    }

    private fun assertCentred(where: String) {
        for (h in Habit.DEFAULTS) {
            val node = compose.onNodeWithText(h.label, useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            val box = node.size.height.toFloat()
            val top = layout.getLineTop(0)
            val bottom = layout.getLineBottom(0)
            assertTrue("$where: ${h.label}'s line (${bottom - top}px) is taller than its box ($box px)", bottom - top <= box + 0.5f)
            assertTrue("$where: ${h.label} sits off the middle: ${top}px above, ${box - bottom}px below", kotlin.math.abs(top - (box - bottom)) <= 1f)
        }
    }

    @Test fun namesSitInTheMiddleOfShortCells() {
        // The unfolded layout's battery: as tall as its ring, 178dp in the 340dp side column.
        show(178.dp)
        assertCentred("unfolded")
    }

    @Test fun namesSitInTheMiddleOfPhoneCells() {
        show(236.dp)
        assertCentred("phone")
    }
}
