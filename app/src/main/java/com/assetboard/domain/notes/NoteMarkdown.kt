package com.assetboard.domain.notes

/**
 * The Markdown subset a note is written in. Parsing is pure and cheap enough to run on every
 * keystroke: the editor uses one parse for its styling, its checkboxes, its outline and its
 * footer, and the list and widget previews use it to drop the syntax a reader does not need.
 *
 * Only the constructs the note editor draws are recognised. Anything else stays a paragraph, so
 * text imported from elsewhere is never lost or reordered.
 */
object NoteMarkdown {
    enum class Kind { BLANK, PARAGRAPH, HEADING, CHECKLIST, BULLET, NUMBERED, QUOTE, DIVIDER, FENCE, CODE }

    /**
     * One line and the offsets of what it is made of. All offsets are absolute in the note body.
     * [markerStart] is after the leading indent for list items, so nested items keep it.
     */
    data class Line(
        val index: Int,
        val start: Int,
        val end: Int,
        val raw: String,
        val kind: Kind,
        val level: Int = 0,
        val indent: Int = 0,
        val markerStart: Int = start + indent,
        val markerEnd: Int = start + indent,
        val contentStart: Int = start + indent,
        val checked: Boolean = false,
        /** Where the `x` or space inside a checklist's brackets sits. */
        val checkAt: Int = -1,
        val number: Int = 0,
        val delimiter: Char = '.',
        val marker: Char = '-',
    ) {
        val content: String get() = raw.substring((contentStart - start).coerceIn(0, raw.length))
        val isList: Boolean get() = kind == Kind.CHECKLIST || kind == Kind.BULLET || kind == Kind.NUMBERED
        val hasBlockMarker: Boolean get() = kind == Kind.HEADING || isList || kind == Kind.QUOTE
    }

    data class Heading(val level: Int, val text: String, val offset: Int)

    data class Doc(
        val text: String,
        val lines: List<Line>,
        /** The first line with something to show; the note's name is taken from it. */
        val titleLine: Line?,
        val headings: List<Heading>,
        val tasks: List<Line>,
    ) {
        val doneTasks: Int = tasks.count { it.checked }
        /** Visible characters, so Markdown markers do not inflate the count. */
        val wordCount: Int = lines.sumOf { line -> NoteMarkdown.displayText(line).count { !it.isWhitespace() } }

        /** The line [offset] falls in; the empty line after a final newline is a line too. */
        fun lineAt(offset: Int): Line {
            val at = offset.coerceIn(0, text.length)
            return lines.lastOrNull { it.start <= at } ?: lines.first()
        }

        fun lineIndexRange(start: Int, end: Int): IntRange {
            val a = lineAt(start).index
            val b = lineAt(end).index
            return a..b
        }
    }

    enum class Inline { BOLD, ITALIC, STRIKE, CODE, LINK }

    /** Inline emphasis inside one line's content: the styled range and the two marker ranges. */
    data class Mark(
        val kind: Inline,
        val contentStart: Int,
        val contentEnd: Int,
        val openStart: Int,
        val openEnd: Int,
        val closeStart: Int,
        val closeEnd: Int,
    )

    private val HEADING = Regex("""^([ \t]{0,3})(#{1,6})([ \t]+)(.*)$""")
    private val CHECKLIST = Regex("""^([ \t]*)([-*+])([ \t]+)\[([ xX])\]([ \t]*)(.*)$""")
    private val BULLET = Regex("""^([ \t]*)([-*+])([ \t]+)(.*)$""")
    private val NUMBERED = Regex("""^([ \t]*)(\d{1,9})([.)])([ \t]+)(.*)$""")
    private val QUOTE = Regex("""^([ \t]*)(>+)([ \t]?)(.*)$""")

    private val BOLD_STAR = Regex("""\*\*([^*\n]+?)\*\*""")
    private val BOLD_UNDER = Regex("""__([^_\n]+?)__""")
    private val STRIKE = Regex("""~~([^~\n]+?)~~""")
    private val CODE = Regex("""`([^`\n]+)`""")
    private val LINK = Regex("""\[([^\]\n]+)\]\(([^)\n]+)\)""")
    // An asterisk may follow Chinese directly (there is no space to separate it), but not an
    // ASCII letter or digit inside a word or an arithmetic expression.
    private val ITALIC_STAR = Regex("""(?<![*\p{IsLatin}\p{N}_])\*([^*\n]+?)\*(?!\*)""")
    private val ITALIC_UNDER = Regex("""(?<![_\p{L}\p{N}])_([^_\n]+?)_(?![_\p{L}\p{N}])""")

    fun parse(text: String): Doc {
        val lines = ArrayList<Line>()
        var fence: Char? = null
        var start = 0
        var index = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            val raw = text.substring(start, end)
            val trimmed = raw.trim()
            val kind: Kind
            when {
                fence != null -> {
                    kind = if (isFence(trimmed, fence!!)) {
                        fence = null
                        Kind.FENCE
                    } else {
                        Kind.CODE
                    }
                    lines += Line(index, start, end, raw, kind, indent = indentOf(raw))
                }
                isFenceStart(trimmed) -> {
                    fence = trimmed.first()
                    lines += Line(index, start, end, raw, Kind.FENCE, indent = indentOf(raw))
                }
                else -> lines += parseLine(index, start, end, raw)
            }
            index++
            if (nl < 0) break
            start = end + 1
        }
        val titleLine = lines.firstOrNull { it.kind != Kind.BLANK && it.kind != Kind.DIVIDER && it.kind != Kind.FENCE && displayText(it).isNotEmpty() }
        val headings = lines.filter { it.kind == Kind.HEADING }.map {
            Heading(it.level, stripInline(it.content).trim(), it.start)
        }
        val tasks = lines.filter { it.kind == Kind.CHECKLIST }
        return Doc(text, lines, titleLine, headings, tasks)
    }

    /** What a line reads as once its block marker and inline syntax are gone. */
    fun displayText(line: Line): String = when (line.kind) {
        Kind.BLANK, Kind.DIVIDER, Kind.FENCE -> ""
        Kind.HEADING, Kind.CHECKLIST, Kind.BULLET, Kind.NUMBERED, Kind.QUOTE -> stripInline(line.content).trim()
        Kind.PARAGRAPH, Kind.CODE -> stripInline(line.raw.trim())
    }

    /** The note's name: the first line with something to read. */
    fun title(text: String): String = parse(text).titleLine?.let { displayText(it) }.orEmpty()

    /**
     * One line of reading for a list or a widget: the lines after the title, joined, with list
     * items marked and all syntax removed.
     */
    fun preview(text: String): String {
        val doc = parse(text)
        val title = doc.titleLine
        val body = doc.lines.asSequence()
            .filter { title == null || it.index > title.index }
            .mapNotNull { line ->
                val shown = displayText(line)
                if (shown.isEmpty()) return@mapNotNull null
                when (line.kind) {
                    Kind.CHECKLIST -> (if (line.checked) "☑ " else "☐ ") + shown
                    Kind.BULLET -> "• $shown"
                    else -> shown
                }
            }
            .joinToString(" ")
        return body.take(160)
    }

    /** Inline emphasis in [line], in a stable priority so `**bold**` never also reads as italic. */
    fun inlineMarks(text: String, line: Line): List<Mark> {
        if (line.kind == Kind.CODE || line.kind == Kind.FENCE || line.kind == Kind.DIVIDER || line.kind == Kind.BLANK) return emptyList()
        val from = line.contentStart.coerceIn(0, text.length)
        val to = line.end.coerceIn(from, text.length)
        if (from >= to) return emptyList()
        val body = text.substring(from, to)
        val marks = ArrayList<Mark>()
        fun overlaps(start: Int, end: Int): Boolean = marks.any { start < it.contentEnd && end > it.contentStart }
        fun scan(kind: Inline, regex: Regex) {
            for (m in regex.findAll(body)) {
                val content = m.groups[1] ?: continue
                val cs = from + content.range.first
                val ce = from + content.range.last + 1
                if (cs >= ce || overlaps(cs, ce)) continue
                marks += Mark(
                    kind = kind,
                    contentStart = cs,
                    contentEnd = ce,
                    openStart = from + m.range.first,
                    openEnd = cs,
                    closeStart = ce,
                    closeEnd = from + m.range.last + 1,
                )
            }
        }
        // Code first: emphasis inside it is literal. Links before emphasis so their target is
        // hidden whole; then the longest markers before the shorter ones.
        scan(Inline.CODE, CODE)
        scan(Inline.LINK, LINK)
        scan(Inline.BOLD, BOLD_STAR)
        scan(Inline.BOLD, BOLD_UNDER)
        scan(Inline.STRIKE, STRIKE)
        scan(Inline.ITALIC, ITALIC_STAR)
        scan(Inline.ITALIC, ITALIC_UNDER)
        return marks.sortedBy { it.contentStart }
    }

    /** Removes inline markers and link targets for a title, an outline or a preview. */
    fun stripInline(text: String): String {
        if (text.isEmpty()) return text
        val edits = ArrayList<IntRange>()
        fun find(regex: Regex) {
            for (m in regex.findAll(text)) {
                val content = m.groups[1] ?: continue
                // Keep the content, drop the markers around it.
                edits += (m.range.first until content.range.first)
                edits += (content.range.last + 1) until m.range.last + 1
            }
        }
        find(LINK)
        find(CODE)
        find(BOLD_STAR)
        find(BOLD_UNDER)
        find(STRIKE)
        find(ITALIC_STAR)
        find(ITALIC_UNDER)
        // A pair inside another construct (code, then bold, then italic) would otherwise cut a
        // hole in the text; keep the first, longest construct that covers the range.
        val accepted = ArrayList<IntRange>()
        for (r in edits) {
            if (r.first < 0 || r.first > r.last || r.last >= text.length) continue
            if (accepted.none { r.first <= it.last && r.last >= it.first }) accepted += r
        }
        val out = StringBuilder(text)
        for (r in accepted.sortedByDescending { it.first }) out.delete(r.first, r.last + 1)
        return out.toString()
    }

    private fun parseLine(index: Int, start: Int, end: Int, raw: String): Line {
        val indent = indentOf(raw)
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Line(index, start, end, raw, Kind.BLANK, indent = indent)

        if (isDivider(trimmed)) return Line(index, start, end, raw, Kind.DIVIDER, indent = indent)

        HEADING.matchEntire(raw)?.let { m ->
            val markerStart = start + m.groups[1]!!.value.length
            val markerEnd = markerStart + m.groups[2]!!.value.length + m.groups[3]!!.value.length
            return Line(
                index, start, end, raw, Kind.HEADING,
                level = m.groups[2]!!.value.length,
                indent = indent,
                markerStart = markerStart,
                markerEnd = markerEnd,
                contentStart = markerEnd,
            )
        }
        CHECKLIST.matchEntire(raw)?.let { m ->
            val markerStart = start + m.groups[1]!!.value.length
            val markerEnd = markerStart + m.groups[2]!!.value.length + m.groups[3]!!.value.length + 3 + m.groups[5]!!.value.length
            return Line(
                index, start, end, raw, Kind.CHECKLIST,
                indent = indent,
                markerStart = markerStart,
                markerEnd = markerEnd,
                contentStart = markerEnd,
                checked = m.groups[4]!!.value != " ",
                checkAt = markerStart + m.groups[2]!!.value.length + m.groups[3]!!.value.length + 1,
                marker = m.groups[2]!!.value.first(),
            )
        }
        BULLET.matchEntire(raw)?.let { m ->
            val markerStart = start + m.groups[1]!!.value.length
            val markerEnd = markerStart + m.groups[2]!!.value.length + m.groups[3]!!.value.length
            return Line(
                index, start, end, raw, Kind.BULLET,
                indent = indent,
                markerStart = markerStart,
                markerEnd = markerEnd,
                contentStart = markerEnd,
                marker = m.groups[2]!!.value.first(),
            )
        }
        NUMBERED.matchEntire(raw)?.let { m ->
            val markerStart = start + m.groups[1]!!.value.length
            val markerEnd = markerStart + m.groups[2]!!.value.length + 1 + m.groups[4]!!.value.length
            return Line(
                index, start, end, raw, Kind.NUMBERED,
                indent = indent,
                markerStart = markerStart,
                markerEnd = markerEnd,
                contentStart = markerEnd,
                number = m.groups[2]!!.value.toIntOrNull() ?: 1,
                delimiter = m.groups[3]!!.value.first(),
            )
        }
        QUOTE.matchEntire(raw)?.let { m ->
            val markerStart = start + m.groups[1]!!.value.length
            val markerEnd = markerStart + m.groups[2]!!.value.length + m.groups[3]!!.value.length
            return Line(
                index, start, end, raw, Kind.QUOTE,
                level = m.groups[2]!!.value.length,
                indent = indent,
                markerStart = markerStart,
                markerEnd = markerEnd,
                contentStart = markerEnd,
            )
        }
        return Line(index, start, end, raw, Kind.PARAGRAPH, indent = indent, markerStart = start + indent, markerEnd = start + indent, contentStart = start + indent)
    }

    private fun indentOf(raw: String): Int = raw.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) raw.length else it }

    private fun isFenceStart(trimmed: String): Boolean = trimmed.startsWith("```") || trimmed.startsWith("~~~")

    private fun isFence(trimmed: String, marker: Char): Boolean =
        trimmed.length >= 3 && trimmed.takeWhile { it == marker }.length >= 3 && trimmed.all { it == marker }

    private fun isDivider(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val c = trimmed.first()
        if (c != '-' && c != '*' && c != '_') return false
        var count = 0
        for (ch in trimmed) {
            if (ch == c) count++ else if (!ch.isWhitespace()) return false
        }
        return count >= 3
    }
}
