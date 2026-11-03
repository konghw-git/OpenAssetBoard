package com.assetboard.ui.notes

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.assetboard.domain.notes.EditHistory
import com.assetboard.domain.notes.NoteMarkdown
import com.assetboard.domain.notes.NoteMarkdownEdit
import kotlin.math.abs

/**
 * What the note editor and its toolbar share: one [TextFieldValue] (text plus cursor), the last
 * text layout (for the checkboxes and the outline's jumps), the edits the toolbar makes and the
 * undo history behind the back button. Everything goes through [onInput], so the session,
 * autosave, drafts and sync keep seeing the body as plain text.
 */
@Stable
class NoteEditorState(private val onInput: (String) -> Unit) {
    var value by mutableStateOf(TextFieldValue(""))
        private set
    var focused by mutableStateOf(false)
    var layout by mutableStateOf<TextLayoutResult?>(null)

    /** Set by the editor; the outline sheet calls it to bring a heading into view. */
    var jumpTo: ((Int) -> Unit)? = null

    private val history = EditHistory()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private var cachedDoc: NoteMarkdown.Doc? = null

    /** The current text parsed once, shared by the styling, the toolbar and the outline. */
    fun document(): NoteMarkdown.Doc {
        val text = value.text
        cachedDoc?.takeIf { it.text == text }?.let { return it }
        return NoteMarkdown.parse(text).also { cachedDoc = it }
    }

    /**
     * The session's text, which is also what an append or a restore can change under us. An
     * external change invalidates every offset the history holds, so the history goes with it.
     */
    fun sync(text: String) {
        if (value.text == text) return
        history.clear()
        canUndo = false
        canRedo = false
        value = TextFieldValue(text, TextRange(text.length))
    }

    fun edit(next: TextFieldValue) {
        val before = value
        var v = next
        // While the IME is composing (pinyin, say) leave its text alone: the smart edits below
        // need a settled value and a single, committed change.
        if (v.text != value.text && v.composition == null) {
            NoteMarkdownEdit.backspaceAtMarker(
                value.text, value.selection.start, value.selection.end,
                v.text, v.selection.start, v.selection.end,
            )?.let { v = TextFieldValue(it.text, TextRange(it.start, it.end)) }
                ?: NoteMarkdownEdit.continueList(
                    value.text, value.selection.start, value.selection.end,
                    v.text, v.selection.start, v.selection.end,
                )?.let { v = TextFieldValue(it.text, TextRange(it.start, it.end)) }
        }
        if (v.composition == null && v.selection.collapsed) {
            val snapped = NoteMarkdownEdit.snapCursor(v.text, v.selection.start)
            if (snapped != v.selection.start) v = v.copy(selection = TextRange(snapped))
        }
        val changed = v.text != value.text
        value = v
        if (changed) {
            onInput(v.text)
            record(before, v, kindOf(before, v))
        }
    }

    fun apply(result: NoteMarkdownEdit.Result) {
        val before = value
        value = TextFieldValue(result.text, TextRange(result.start, result.end))
        onInput(result.text)
        record(before, value, EditHistory.Kind.COMMAND)
    }

    /** Tapping the box drawn over a checklist's marker. */
    fun toggleTask(line: NoteMarkdown.Line) {
        val before = value
        val text = NoteMarkdownEdit.setChecked(value.text, line, !line.checked) ?: return
        value = value.copy(text = text)
        onInput(text)
        record(before, value, EditHistory.Kind.COMMAND)
    }

    fun format(format: NoteMarkdownEdit.Format) {
        val selection = value.selection
        NoteMarkdownEdit.format(value.text, selection.start, selection.end, format)?.let(::apply)
    }

    /**
     * The toolbar's back button: one step of history, not an edit, so undoing never records
     * itself and never runs through the smart-edit rules.
     */
    fun undo() {
        val back = history.undo(snapshot(value))
        if (back == null) {
            canUndo = history.canUndo
            canRedo = history.canRedo
            return
        }
        putBack(back)
    }

    fun redo() {
        val forward = history.redo(snapshot(value))
        if (forward == null) {
            canUndo = history.canUndo
            canRedo = history.canRedo
            return
        }
        putBack(forward)
    }

    /** The line the toolbar's buttons act on. */
    fun currentLine(doc: NoteMarkdown.Doc): NoteMarkdown.Line = doc.lineAt(value.selection.start)

    private fun snapshot(v: TextFieldValue) = EditHistory.Snapshot(v.text, v.selection.min, v.selection.max)

    private fun record(before: TextFieldValue, after: TextFieldValue, kind: EditHistory.Kind) {
        history.record(snapshot(before), snapshot(after), kind)
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    /** Typing runs together while the writer keeps going; a replace or a jump stands alone. */
    private fun kindOf(before: TextFieldValue, after: TextFieldValue): EditHistory.Kind {
        val typing = before.selection.collapsed && after.selection.collapsed &&
            abs(after.text.length - before.text.length) <= 4
        return if (typing) EditHistory.Kind.TYPING else EditHistory.Kind.COMMAND
    }

    private fun putBack(s: EditHistory.Snapshot) {
        val text = s.text
        val changed = text != value.text
        value = TextFieldValue(text, TextRange(s.start.coerceIn(0, text.length), s.end.coerceIn(0, text.length)))
        canUndo = history.canUndo
        canRedo = history.canRedo
        if (changed) onInput(text)
    }
}
