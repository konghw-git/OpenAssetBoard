package com.assetboard.domain.notes

import com.assetboard.domain.notes.NoteMarkdown.Kind
import com.assetboard.domain.notes.NoteMarkdown.Line

/**
 * The edits the note toolbar and the keyboard shortcuts make. Everything here is pure: a text,
 * a selection, and the text and selection that come out. The editor applies the result to its
 * one text field, so the body stays the single source of truth that autosave, sync and backup
 * already understand.
 */
object NoteMarkdownEdit {
    enum class Format { H1, H2, H3, CHECKLIST, BULLET, NUMBERED, QUOTE, DIVIDER, BOLD, ITALIC, STRIKE, CODE, INDENT, OUTDENT }

    data class Result(val text: String, val start: Int, val end: Int = start)

    private data class Replace(val start: Int, val end: Int, val text: String)

    fun format(text: String, start: Int, end: Int, format: Format): Result? {
        // A selection dragged backwards still covers its text.
        val a = minOf(start, end).coerceIn(0, text.length)
        val b = maxOf(start, end).coerceIn(0, text.length)
        return when (format) {
            Format.H1 -> setHeading(text, a, b, 1)
            Format.H2 -> setHeading(text, a, b, 2)
            Format.H3 -> setHeading(text, a, b, 3)
            Format.CHECKLIST -> setBlock(text, a, b, Kind.CHECKLIST)
            Format.BULLET -> setBlock(text, a, b, Kind.BULLET)
            Format.NUMBERED -> setBlock(text, a, b, Kind.NUMBERED)
            Format.QUOTE -> setBlock(text, a, b, Kind.QUOTE)
            Format.DIVIDER -> insertDivider(text, a)
            Format.BOLD -> toggleInline(text, a, b, "**")
            Format.ITALIC -> toggleInline(text, a, b, "*")
            Format.STRIKE -> toggleInline(text, a, b, "~~")
            Format.CODE -> toggleInline(text, a, b, "`")
            Format.INDENT -> shiftIndent(text, a, b, add = true)
            Format.OUTDENT -> shiftIndent(text, a, b, add = false)
        }
    }

    /** `- [ ]` and `- [x]`, keeping the cursor exactly where it was. */
    fun setChecked(text: String, line: Line, checked: Boolean): String? {
        if (line.kind != Kind.CHECKLIST || line.checkAt !in text.indices) return null
        return StringBuilder(text).apply { setCharAt(line.checkAt, if (checked) 'x' else ' ') }.toString()
    }

    /** A cursor inside a checklist's hidden marker belongs after it, where the words start. */
    fun snapCursor(text: String, offset: Int): Int {
        val at = offset.coerceIn(0, text.length)
        val line = NoteMarkdown.parse(text).lineAt(at)
        return if (line.kind == Kind.CHECKLIST && at in line.markerStart until line.markerEnd) line.markerEnd else at
    }

    /**
     * The list, checklist or quote a line carries into the next one, and the Enter that ends an
     * empty item. Null when the change was not a single newline after the old cursor.
     */
    fun continueList(oldText: String, oldStart: Int, oldEnd: Int, newText: String, newStart: Int, newEnd: Int): Result? {
        if (oldStart != oldEnd || newStart != newEnd) return null
        if (newText.length != oldText.length + 1) return null
        if (newStart != oldStart + 1 || oldStart !in 0..oldText.length) return null
        if (newText.getOrNull(oldStart) != '\n') return null
        if (!newText.startsWith(oldText.substring(0, oldStart)) || !newText.endsWith(oldText.substring(oldStart))) return null

        val line = NoteMarkdown.parse(oldText).lineAt(oldStart)
        if (line.kind == Kind.PARAGRAPH || line.kind == Kind.BLANK || line.kind == Kind.DIVIDER || line.kind == Kind.FENCE || line.kind == Kind.CODE) return null

        val blankItem = oldText.substring(line.contentStart, line.end).isBlank()
        if (blankItem) {
            // An empty item: Enter ends the list, leaving a blank line where the marker was.
            val result = oldText.substring(0, line.start) + "\n" + oldText.substring(oldStart)
            return Result(result, line.start)
        }
        if (line.kind == Kind.HEADING) return null

        val indent = oldText.substring(line.start, line.start + line.indent)
        val prefix = when (line.kind) {
            Kind.CHECKLIST -> "$indent${line.marker} [ ] "
            Kind.BULLET -> "$indent${line.marker} "
            Kind.NUMBERED -> "$indent${line.number + 1}${line.delimiter} "
            Kind.QUOTE -> indent + ">".repeat(line.level) + " "
            else -> return null
        }
        val at = oldStart + 1
        val result = newText.substring(0, at) + prefix + newText.substring(at)
        return Result(result, at + prefix.length)
    }

    /**
     * Backspace right after a block marker would delete one invisible marker character and make
     * the line stop parsing as a list or heading. Remove the whole marker instead, which is what
     * the reader sees as one thing.
     */
    fun backspaceAtMarker(oldText: String, oldStart: Int, oldEnd: Int, newText: String, newStart: Int, newEnd: Int): Result? {
        if (oldStart != oldEnd || newStart != newEnd) return null
        if (newText.length != oldText.length - 1) return null
        if (newStart != oldStart - 1 || oldStart !in 1..oldText.length) return null
        if (!newText.startsWith(oldText.substring(0, oldStart - 1)) || !newText.endsWith(oldText.substring(oldStart))) return null
        val line = NoteMarkdown.parse(oldText).lineAt(oldStart)
        if (!line.hasBlockMarker || oldStart != line.contentStart) return null
        if (oldStart - 1 !in line.markerStart until line.markerEnd) return null
        return Result(oldText.removeRange(line.markerStart, line.markerEnd), line.markerStart)
    }

    private fun setHeading(text: String, a: Int, b: Int, level: Int): Result? {
        val doc = NoteMarkdown.parse(text)
        val marker = "#".repeat(level) + " "
        val reps = selectedLines(doc, a, b).mapNotNull { line ->
            when {
                line.kind == Kind.HEADING && line.level == level -> Replace(line.markerStart, line.markerEnd, "")
                line.kind == Kind.HEADING || line.hasBlockMarker -> Replace(line.markerStart, line.markerEnd, marker)
                line.kind == Kind.DIVIDER -> Replace(line.start, line.end, marker)
                line.kind == Kind.BLANK -> Replace(line.start, line.end, marker)
                else -> Replace(line.contentStart, line.contentStart, marker)
            }
        }
        return apply(text, a, b, reps)
    }

    private fun setBlock(text: String, a: Int, b: Int, kind: Kind): Result? {
        val doc = NoteMarkdown.parse(text)
        val marker = when (kind) {
            Kind.CHECKLIST -> "- [ ] "
            Kind.BULLET -> "- "
            Kind.NUMBERED -> "1. "
            Kind.QUOTE -> "> "
            else -> return null
        }
        val reps = selectedLines(doc, a, b).mapNotNull { line ->
            if (line.kind == kind) {
                // The same kind again removes the marker, so every button toggles.
                Replace(line.markerStart, line.markerEnd, "")
            } else {
                when {
                    line.kind == Kind.DIVIDER -> Replace(line.start, line.end, marker)
                    line.kind == Kind.BLANK -> Replace(line.start, line.end, marker)
                    line.hasBlockMarker -> Replace(line.markerStart, line.markerEnd, marker)
                    else -> Replace(line.contentStart, line.contentStart, marker)
                }
            }
        }
        return apply(text, a, b, reps)
    }

    private fun insertDivider(text: String, at: Int): Result {
        val doc = NoteMarkdown.parse(text)
        // At the very end of a body that ends in a newline the cursor sits on the empty line
        // after the divider; that empty line still means "toggle the divider above".
        val line = doc.lineAt(at).let { l ->
            if (l.kind == Kind.BLANK && l.start == at && at == text.length && at > 0) doc.lineAt(at - 1) else l
        }
        if (line.kind == Kind.DIVIDER) {
            // Take the newline before the divider with it, so the lines around it stay apart.
            val start = if (line.start > 0) line.start - 1 else line.start
            val end = if (line.start > 0) line.end else (line.end + 1).coerceAtMost(text.length)
            var result = text.removeRange(start, end)
            if (line.end + 1 == text.length && result.endsWith("\n")) result = result.dropLast(1)
            return Result(result, start.coerceIn(0, result.length))
        }
        if (line.kind == Kind.BLANK && line.raw.isBlank()) {
            // An empty line becomes the divider instead of pushing the list down.
            val result = text.replaceRange(line.start, line.end, "---")
            return Result(result, line.start + 3)
        }
        val atEnd = line.end
        val block = "\n---\n"
        val result = text.substring(0, atEnd) + block + text.substring(atEnd)
        return Result(result, atEnd + block.length)
    }

    private fun toggleInline(text: String, a: Int, b: Int, marker: String): Result {
        // Inline syntax cannot span a line break, so a selection over several lines is wrapped
        // line by line; one pair around the whole block would render as nothing.
        if (a < b && text.substring(a, b).contains('\n')) return toggleInlineByLine(text, a, b, marker)
        var start = a
        var end = b
        if (start == end) {
            // A cursor inside an ASCII word wraps that word. Chinese has no spaces, so a single
            // character under the cursor is the useful unit there; a lone cursor gets an empty
            // pair ready to type in.
            while (start > 0 && isWordChar(text[start - 1])) start--
            while (end < text.length && isWordChar(text[end])) end++
            if (start == end) {
                val at = a.coerceAtMost(text.length - 1)
                if (at in text.indices && text[at].isLetter()) {
                    start = at
                    end = at + 1
                }
            }
            if (start == end) {
                val result = text.substring(0, a) + marker + marker + text.substring(a)
                return Result(result, a + marker.length, a + marker.length)
            }
        }
        val before = if (start >= marker.length) text.substring(start - marker.length, start) else ""
        val after = if (end + marker.length <= text.length) text.substring(end, end + marker.length) else ""
        if (before == marker && after == marker) {
            val result = StringBuilder(text).delete(end, end + marker.length).delete(start - marker.length, start).toString()
            return Result(result, start - marker.length, end - marker.length)
        }
        val result = text.substring(0, start) + marker + text.substring(start, end) + marker + text.substring(end)
        return Result(result, start + marker.length, end + marker.length)
    }

    /**
     * One inline pair per selected line. Only the words on each line are wrapped, not its list or
     * heading marker, and an already wrapped line is left alone unless every line is wrapped, in
     * which case the action removes the pairs again.
     */
    private fun toggleInlineByLine(text: String, a: Int, b: Int, marker: String): Result {
        val doc = NoteMarkdown.parse(text)
        val spans = selectedLines(doc, a, b).mapNotNull { line ->
            var from = maxOf(a, line.contentStart)
            var to = minOf(b, line.end)
            // The selection left after a wrap includes the closing markers of every line but the
            // last. They are syntax, not words, so trim them off at the edges before deciding
            // whether this line is wrapped and where a new pair belongs.
            val marks = NoteMarkdown.inlineMarks(text, line)
            val syntax = marks.flatMap { listOf(it.openStart until it.openEnd, it.closeStart until it.closeEnd) }
            while (from < to && syntax.any { from in it }) from++
            while (to > from && syntax.any { to - 1 in it }) to--
            if (from < to) from to to else null
        }
        if (spans.isEmpty()) return Result(text, a, b)
        fun wrapped(span: Pair<Int, Int>): Boolean {
            val (from, to) = span
            val before = if (from >= marker.length) text.substring(from - marker.length, from) else ""
            val after = if (to + marker.length <= text.length) text.substring(to, to + marker.length) else ""
            return before == marker && after == marker
        }
        val remove = spans.all(::wrapped)
        val edits = spans.filter { wrapped(it) == remove }.flatMap { (from, to) ->
            if (remove) listOf(Replace(from - marker.length, from, ""), Replace(to, to + marker.length, ""))
            else listOf(Replace(from, from, marker), Replace(to, to, marker))
        }
        if (edits.isEmpty()) return Result(text, a, b)
        val result = apply(text, spans.first().first, spans.last().second, edits) ?: return Result(text, a, b)
        // The last pair added sits just after the selection; leave it outside, as a single-line
        // wrap does, so typing carries on with the words rather than with the closing marker.
        val closedLast = !remove && edits.any { it.start == spans.last().second && it.text == marker }
        return if (closedLast) result.copy(end = (result.end - marker.length).coerceAtLeast(result.start)) else result
    }

    private fun shiftIndent(text: String, a: Int, b: Int, add: Boolean): Result? {
        val doc = NoteMarkdown.parse(text)
        val reps = selectedLines(doc, a, b).mapNotNull { line ->
            if (add) {
                Replace(line.start, line.start, "  ")
            } else {
                val spaces = line.raw.takeWhile { it == ' ' }.length.coerceAtMost(2)
                if (spaces > 0) Replace(line.start, line.start + spaces, "") else null
            }
        }
        return if (reps.isEmpty()) null else apply(text, a, b, reps)
    }

    /** The lines a selection touches; a selection ending at a line start does not take that line. */
    private fun selectedLines(doc: NoteMarkdown.Doc, a: Int, b: Int): List<Line> {
        if (a == b) return listOf(doc.lineAt(a))
        val first = doc.lineAt(a)
        val endAt = if (b > a && doc.lineAt(b).start == b) b - 1 else b
        val last = doc.lineAt(endAt.coerceAtLeast(first.start))
        return doc.lines.filter { it.index in first.index..last.index }
    }

    private fun apply(text: String, a: Int, b: Int, edits: List<Replace>): Result? {
        if (edits.isEmpty()) return null
        val sorted = edits.distinct().sortedBy { it.start }
        val out = StringBuilder()
        var at = 0
        for (r in sorted) {
            if (r.start < at) continue
            out.append(text, at, r.start)
            out.append(r.text)
            at = r.end
        }
        out.append(text, at, text.length)

        // Every endpoint moves to just after a rewritten marker; a cursor at a line start then
        // sits inside the new heading or list rather than before it.
        fun map(position: Int): Int {
            var shift = 0
            for (r in sorted) {
                val oldLen = r.end - r.start
                if (position < r.start) break
                if (position > r.end || (position == r.end && oldLen > 0)) {
                    shift += r.text.length - oldLen
                    continue
                }
                return r.start + shift + r.text.length
            }
            return position + shift
        }
        val end = map(b)
        val start = map(a).coerceAtMost(end)
        return Result(out.toString(), start, end)
    }

    private fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
}
