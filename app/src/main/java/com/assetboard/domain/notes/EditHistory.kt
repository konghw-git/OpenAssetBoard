package com.assetboard.domain.notes

import com.assetboard.domain.AppClock

/**
 * The undo and redo behind the editor's back button: a stack of committed steps, each carrying
 * the text and cursor before and after. Typing runs together into one step while the writer keeps
 * typing; a command (a format, a checkbox, a paste-sized change) is always a step of its own.
 * Undo and redo only move steps between the stacks, so undoing never becomes its own undo.
 */
class EditHistory(
    private val maxSteps: Int = 100,
    private val now: () -> Long = AppClock::millis,
) {
    /** A settled text with its cursor; what one step puts back. */
    data class Snapshot(val text: String, val start: Int, val end: Int)

    /** Typing merges into one step while it runs; commands stand alone. */
    enum class Kind { TYPING, COMMAND }

    private data class Step(val before: Snapshot, val after: Snapshot, val kind: Kind, val at: Long)

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Remembers one committed change. Cursor-only moves are not history; a typing run within
     * [MERGE_MS] of the top merges into it, keeping the earliest "before". Recording clears the
     * redo stack: a new change makes every undone step history again.
     */
    fun record(before: Snapshot, after: Snapshot, kind: Kind) {
        if (before.text == after.text) return
        redoStack.clear()
        val top = undoStack.lastOrNull()
        if (kind == Kind.TYPING && top != null && top.kind == Kind.TYPING && now() - top.at <= MERGE_MS) {
            undoStack.removeLast()
            undoStack.addLast(Step(top.before, after, Kind.TYPING, now()))
        } else {
            undoStack.addLast(Step(before, after, kind, now()))
        }
        while (undoStack.size > maxSteps) undoStack.removeFirst()
    }

    /**
     * The snapshot one step back takes the text to, or null when there is nothing to undo.
     * [current] must match the top's "after"; a history the caller no longer matches (an external
     * rewrite got in between) is dropped rather than applied over the wrong offsets.
     */
    fun undo(current: Snapshot): Snapshot? {
        val step = undoStack.lastOrNull() ?: return null
        if (step.after.text != current.text) {
            clear()
            return null
        }
        undoStack.removeLast()
        redoStack.addLast(step)
        return step.before
    }

    /** The snapshot one step forward takes the text to; the mirror of [undo]. */
    fun redo(current: Snapshot): Snapshot? {
        val step = redoStack.lastOrNull() ?: return null
        if (step.before.text != current.text) {
            clear()
            return null
        }
        redoStack.removeLast()
        undoStack.addLast(step)
        return step.after
    }

    /** Drops everything: what an external rewrite (an append, a restore) does to old offsets. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private companion object {
        /** A pause this long ends the typing run and starts the next step. */
        const val MERGE_MS = 1_000L
    }
}
