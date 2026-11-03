package com.assetboard.domain

import com.assetboard.domain.notes.EditHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The undo stack behind the editor's back button: typing runs, commands stand alone. */
class NoteUndoTest {
    private var at = 1_000L
    private val history = EditHistory(now = { at })

    private fun snap(text: String, start: Int = text.length, end: Int = start) =
        EditHistory.Snapshot(text, start, end)

    private fun type(before: String, after: String) =
        history.record(snap(before), snap(after), EditHistory.Kind.TYPING)

    private fun command(before: String, after: String) =
        history.record(snap(before), snap(after), EditHistory.Kind.COMMAND)

    @Test fun oneTypingRunIsOneStep() {
        type("", "你")
        type("你", "你好")
        val back = history.undo(snap("你好"))
        assertEquals("", back?.text)
        assertFalse(history.canUndo)
    }

    @Test fun aPauseStartsANewStep() {
        type("", "你")
        at += 1_500
        type("你", "你好")
        assertEquals("你", history.undo(snap("你好"))?.text)
        assertEquals("", history.undo(snap("你"))?.text)
    }

    @Test fun aCommandStandsAloneBetweenTypingRuns() {
        type("", "买牛奶")
        command("买牛奶", "- [ ] 买牛奶")
        type("- [ ] 买牛奶", "- [ ] 买牛奶和鸡蛋")
        assertEquals("- [ ] 买牛奶", history.undo(snap("- [ ] 买牛奶和鸡蛋"))?.text)
        assertEquals("买牛奶", history.undo(snap("- [ ] 买牛奶"))?.text)
        assertEquals("", history.undo(snap("买牛奶"))?.text)
    }

    @Test fun undoPutsBackTheCursor() {
        history.record(snap("abc", 1, 1), snap("aXbc", 2, 2), EditHistory.Kind.TYPING)
        val back = history.undo(snap("aXbc", 2, 2))
        assertEquals(snap("abc", 1, 1), back)
    }

    @Test fun redoPutsTheChangeBack() {
        type("", "你好")
        history.undo(snap("你好"))
        assertEquals(snap("你好"), history.redo(snap("")))
    }

    @Test fun anUndoIsNotRecorded() {
        type("", "一")
        at += 1_500
        type("一", "一二")
        history.undo(snap("一二"))
        assertTrue(history.canUndo)
        assertTrue(history.canRedo)
        // Undoing again reaches the start; nothing newer was recorded by the undos themselves.
        assertEquals("", history.undo(snap("一"))?.text)
        assertFalse(history.canUndo)
    }

    @Test fun recordingANewChangeClearsRedo() {
        type("", "一")
        history.undo(snap("一"))
        at += 1_500
        type("", "二")
        assertFalse(history.canRedo)
    }

    @Test fun cursorOnlyMovesAreNotHistory() {
        history.record(snap("abc", 0, 0), snap("abc", 3, 3), EditHistory.Kind.TYPING)
        assertFalse(history.canUndo)
    }

    @Test fun theOldestStepFallsOffTheCap() {
        repeat(101) { i -> command("s$i", "s${i + 1}") }
        var text = "s101"
        repeat(100) { i ->
            val back = history.undo(snap(text))
            assertEquals("s${100 - i}", back?.text)
            text = back!!.text
        }
        assertNull(history.undo(snap(text)))
    }

    @Test fun externalRewritesDropTheHistory() {
        type("", "旧文")
        assertNull(history.undo(snap("别处来的文字")))
        assertFalse(history.canUndo)
    }

    @Test fun clearDropsEverything() {
        type("", "旧文")
        history.undo(snap("旧文"))
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
