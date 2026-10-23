package com.assetboard.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.assetboard.ui.todo.EditLine
import com.assetboard.ui.todo.MARK
import com.assetboard.ui.todo.onItemChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keys of the in-place todo editor, line by line, as the keyboard delivers them. */
class InlineEditorKeysTest {
    private fun line(key: String, text: String) = EditLine(key, key, false, TextFieldValue(MARK + text, TextRange(MARK.length + text.length)))

    private class Calls {
        val joined = mutableListOf<Pair<Int, String>>()
        val inserted = mutableListOf<Pair<Int, List<String>>>()
        var ended = 0
    }

    private fun type(lines: MutableList<EditLine>, i: Int, text: String, cursor: Int = text.length, calls: Calls = Calls()): Calls {
        onItemChange(
            lines, i, TextFieldValue(text, TextRange(cursor)),
            joinUp = { at, t -> calls.joined += at to t },
            insertAfter = { at, parts -> calls.inserted += at to parts },
            onEmptyEnter = { calls.ended++ },
        )
        return calls
    }

    @Test fun typingKeepsTheMarkAndTheCursorAfterIt() {
        val lines = mutableListOf(line("a", "买"))
        type(lines, 0, MARK + "买菜")
        assertEquals(MARK + "买菜", lines[0].value.text)
        // A cursor put before the mark is moved back after it.
        type(lines, 0, MARK + "买菜", cursor = 0)
        assertEquals(TextRange(MARK.length), lines[0].value.selection)
    }

    @Test fun enterAtTheEndMakesAnEmptyItemBelow() {
        val lines = mutableListOf(line("a", "买菜"))
        val c = type(lines, 0, MARK + "买菜\n")
        assertEquals(MARK + "买菜", lines[0].value.text)
        assertEquals(listOf(0 to listOf("")), c.inserted)
    }

    @Test fun enterInTheMiddleSplitsTheLine() {
        val lines = mutableListOf(line("a", "买菜做饭"))
        val c = type(lines, 0, MARK + "买菜\n做饭", cursor = MARK.length + 3)
        assertEquals(MARK + "买菜", lines[0].value.text)
        assertEquals(listOf(0 to listOf("做饭")), c.inserted)
    }

    @Test fun enterOnAnEmptyItemEndsTheEdit() {
        val lines = mutableListOf(line("a", "买菜"), line("b", ""))
        val c = type(lines, 1, MARK + "\n")
        assertEquals(1, c.ended)
        assertTrue(c.inserted.isEmpty())
    }

    @Test fun backspaceAtTheStartJoinsUpward() {
        val lines = mutableListOf(line("a", "买菜"), line("b", "做饭"))
        // The keyboard deleted the mark: what is left goes up.
        val c = type(lines, 1, "做饭", cursor = 0)
        assertEquals(listOf(1 to "做饭"), c.joined)
        // On an empty item that deletes it.
        val empty = mutableListOf(line("a", "买菜"), line("b", ""))
        assertEquals(listOf(1 to ""), type(empty, 1, "", cursor = 0).joined)
    }

    @Test fun typingOverASelectionThatTookTheMarkPutsItBack() {
        val lines = mutableListOf(line("a", "买菜"))
        val c = type(lines, 0, "鱼", cursor = 1)
        assertTrue(c.joined.isEmpty())
        assertEquals(MARK + "鱼", lines[0].value.text)
    }

    @Test fun pastedLinesBecomeItems() {
        val lines = mutableListOf(line("a", ""))
        val c = type(lines, 0, MARK + "牛奶\n面包\n鸡蛋")
        assertEquals(MARK + "牛奶", lines[0].value.text)
        assertEquals(listOf(0 to listOf("面包", "鸡蛋")), c.inserted)
    }
}
