package com.assetboard.ui.notes

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.assetboard.domain.notes.NoteMarkdown
import com.assetboard.domain.notes.NoteMarkdownEdit

/**
 * What the note editor and its toolbar share: one [TextFieldValue] (text plus cursor), the last
 * text layout (for the checkboxes and the outline's jumps) and the edits the toolbar makes.
 * Everything goes through [onInput], so the session, autosave, drafts and sync keep seeing the
 * body as plain text.
 */
@Stable
class NoteEditorState(private val onInput: (String) -> Unit) {
    var value by mutableStateOf(TextFieldValue(""))
        private set
    var focused by mutableStateOf(false)
    var layout by mutableStateOf<TextLayoutResult?>(null)

    /** Set by the editor; the outline sheet calls it to bring a heading into view. */
    var jumpTo: ((Int) -> Unit)? = null

    private var cachedDoc: NoteMarkdown.Doc? = null

    /** The current text parsed once, shared by the styling, the toolbar and the outline. */
    fun document(): NoteMarkdown.Doc {
        val text = value.text
        cachedDoc?.takeIf { it.text == text }?.let { return it }
        return NoteMarkdown.parse(text).also { cachedDoc = it }
    }

    /** The session's text, which is also what an append or a restore can change under us. */
    fun sync(text: String) {
        if (value.text == text) return
        value = TextFieldValue(text, TextRange(text.length))
    }

    fun edit(next: TextFieldValue) {
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
        if (changed) onInput(v.text)
    }

    fun apply(result: NoteMarkdownEdit.Result) {
        value = TextFieldValue(result.text, TextRange(result.start, result.end))
        onInput(result.text)
    }

    /** Tapping the box drawn over a checklist's marker. */
    fun toggleTask(line: NoteMarkdown.Line) {
        val text = NoteMarkdownEdit.setChecked(value.text, line, !line.checked) ?: return
        value = value.copy(text = text)
        onInput(text)
    }

    fun format(format: NoteMarkdownEdit.Format) {
        val selection = value.selection
        NoteMarkdownEdit.format(value.text, selection.start, selection.end, format)?.let(::apply)
    }

    /** The line the toolbar's buttons act on. */
    fun currentLine(doc: NoteMarkdown.Doc): NoteMarkdown.Line = doc.lineAt(value.selection.start)
}
