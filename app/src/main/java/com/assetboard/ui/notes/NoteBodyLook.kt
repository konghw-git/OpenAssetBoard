package com.assetboard.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.assetboard.data.NoteText
import com.assetboard.domain.notes.NoteMarkdown
import com.assetboard.domain.notes.NoteMarkdown.Kind
import com.assetboard.domain.notes.NoteMarkdown.Line

/**
 * Live preview inside the one text field. Markers are only shown on the line the cursor is on,
 * so a note reads as a finished document until it is touched; the hidden characters are swapped
 * for zero-width word joiners rather than removed, which keeps every offset identical and lets
 * the cursor, selection and IME work exactly as they do in a plain field.
 *
 * Checklists keep their marker's width as spaces; the editor draws a real, tappable box over it.
 */
class NoteBodyLook(
    private val doc: NoteMarkdown.Doc,
    private val activeLines: IntRange?,
    private val bodySize: TextUnit,
    private val titleSize: TextUnit,
    private val textColor: Color,
    private val mutedColor: Color,
    private val faintColor: Color,
    private val accent: Color,
    private val codeBackground: Color,
) : VisualTransformation {
    private data class Styled(val start: Int, val end: Int, val style: SpanStyle)
    private data class Para(val start: Int, val end: Int, val style: ParagraphStyle)

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        if (source != doc.text) return TransformedText(text, OffsetMapping.Identity)
        val chars = source.toCharArray()
        val styles = ArrayList<Styled>()
        val paragraphs = ArrayList<Para>()
        fun style(start: Int, end: Int, value: SpanStyle) {
            val a = start.coerceIn(0, chars.size)
            val b = end.coerceIn(a, chars.size)
            if (a < b) styles += Styled(a, b, value)
        }
        fun para(start: Int, end: Int, lineHeight: TextUnit) {
            val a = start.coerceIn(0, chars.size)
            val b = end.coerceIn(a, chars.size)
            if (a < b) paragraphs += Para(a, b, ParagraphStyle(lineHeight = lineHeight, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)))
        }
        fun hide(start: Int, end: Int) {
            for (i in start.coerceIn(0, chars.size) until end.coerceIn(0, chars.size)) chars[i] = ZERO
        }
        fun spaces(start: Int, end: Int) {
            for (i in start.coerceIn(0, chars.size) until end.coerceIn(0, chars.size)) chars[i] = ' '
        }

        val body = SpanStyle(fontSize = bodySize, color = textColor)
        for (line in doc.lines) {
            val active = activeLines?.contains(line.index) == true
            val isTitle = doc.titleLine?.index == line.index
            when (line.kind) {
                Kind.BLANK -> Unit
                Kind.HEADING -> {
                    if (isTitle) title(line.start, line.end, ::style, ::para) else heading(line.level, line.start, line.end, ::style, ::para)
                    if (active) {
                        style(line.markerStart, line.markerEnd, SpanStyle(color = faintColor, fontSize = bodySize * 0.82f))
                    } else {
                        hide(line.markerStart, line.markerEnd)
                    }
                    inline(line, active, ::style, ::hide)
                    tags(source, line, ::style)
                }
                Kind.CHECKLIST -> {
                    if (isTitle) title(line.start, line.end, ::style, ::para) else style(line.start, line.end, body)
                    // The marker keeps its width; the editor draws the box and handles the tap.
                    spaces(line.markerStart, line.markerEnd)
                    style(
                        line.contentStart, line.end,
                        SpanStyle(
                            color = if (line.checked) mutedColor else textColor,
                            textDecoration = if (line.checked) TextDecoration.LineThrough else null,
                        ),
                    )
                    inline(line, active, ::style, ::hide)
                    tags(source, line, ::style)
                }
                Kind.BULLET -> {
                    if (isTitle) title(line.start, line.end, ::style, ::para) else style(line.start, line.end, body)
                    if (active) {
                        style(line.markerStart, line.markerEnd, SpanStyle(color = accent, fontWeight = FontWeight.Medium))
                    } else {
                        if (line.markerStart in chars.indices) chars[line.markerStart] = '•'
                        style(line.markerStart, line.markerStart + 1, SpanStyle(color = accent, fontWeight = FontWeight.Bold))
                    }
                    inline(line, active, ::style, ::hide)
                    tags(source, line, ::style)
                }
                Kind.NUMBERED -> {
                    if (isTitle) title(line.start, line.end, ::style, ::para) else style(line.start, line.end, body)
                    style(line.markerStart, line.markerEnd, SpanStyle(color = accent, fontWeight = FontWeight.Medium))
                    inline(line, active, ::style, ::hide)
                    tags(source, line, ::style)
                }
                Kind.QUOTE -> {
                    if (isTitle) title(line.start, line.end, ::style, ::para) else style(line.start, line.end, body)
                    style(line.contentStart, line.end, SpanStyle(color = mutedColor, fontStyle = FontStyle.Italic))
                    if (active) {
                        style(line.markerStart, line.markerEnd, SpanStyle(color = faintColor))
                    } else {
                        // Keep the marker's width: the editor draws the quote bar in that space.
                        spaces(line.markerStart, line.markerEnd)
                    }
                    inline(line, active, ::style, ::hide)
                    tags(source, line, ::style)
                }
                Kind.DIVIDER -> {
                    if (active) {
                        style(line.start, line.end, SpanStyle(color = faintColor, fontFamily = FontFamily.Monospace))
                    } else {
                        hide(line.start, line.end)
                        para(line.start, line.end, 26.sp)
                        style(line.start, line.end, SpanStyle(fontSize = 1.sp, color = Color.Transparent))
                    }
                }
                Kind.FENCE -> {
                    style(line.start, line.end, SpanStyle(fontFamily = FontFamily.Monospace, color = faintColor, fontSize = bodySize * 0.84f))
                    if (!active) hide(line.start, line.end)
                }
                Kind.CODE -> {
                    para(line.start, line.end, 24.sp)
                    style(
                        line.start, line.end,
                        SpanStyle(fontFamily = FontFamily.Monospace, fontSize = bodySize * 0.88f, color = mutedColor, background = codeBackground),
                    )
                }
                Kind.PARAGRAPH -> {
                    if (isTitle) title(line.start, line.end, ::style, ::para) else style(line.start, line.end, body)
                    inline(line, active, ::style, ::hide)
                    tags(source, line, ::style)
                }
            }
        }
        val styled = buildAnnotatedString {
            append(String(chars))
            styles.forEach { addStyle(it.style, it.start, it.end) }
            paragraphs.forEach { addStyle(it.style, it.start, it.end) }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    private fun inline(line: Line, active: Boolean, style: (Int, Int, SpanStyle) -> Unit, hide: (Int, Int) -> Unit) {
        for (mark in NoteMarkdown.inlineMarks(doc.text, line)) {
            val span = when (mark.kind) {
                NoteMarkdown.Inline.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                NoteMarkdown.Inline.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                NoteMarkdown.Inline.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                NoteMarkdown.Inline.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                NoteMarkdown.Inline.LINK -> SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
            }
            style(mark.contentStart, mark.contentEnd, span)
            if (active) {
                val marker = SpanStyle(color = faintColor, fontSize = bodySize * 0.82f)
                style(mark.openStart, mark.openEnd, marker)
                style(mark.closeStart, mark.closeEnd, marker)
            } else {
                hide(mark.openStart, mark.openEnd)
                hide(mark.closeStart, mark.closeEnd)
            }
        }
    }

    private fun tags(source: String, line: Line, style: (Int, Int, SpanStyle) -> Unit) {
        val from = line.contentStart.coerceIn(0, source.length)
        val to = line.end.coerceIn(from, source.length)
        if (from >= to) return
        for (m in NoteText.TAG.findAll(source.substring(from, to))) {
            style(from + m.range.first, from + m.range.last + 1, SpanStyle(color = accent, fontWeight = FontWeight.Medium))
        }
    }

    private fun title(
        start: Int,
        end: Int,
        style: (Int, Int, SpanStyle) -> Unit,
        para: (Int, Int, TextUnit) -> Unit,
    ) {
        style(start, end, SpanStyle(fontSize = titleSize, fontWeight = FontWeight.SemiBold, color = textColor, letterSpacing = 0.3.sp))
        para(start, end, 42.sp)
    }

    private fun heading(
        level: Int,
        start: Int,
        end: Int,
        style: (Int, Int, SpanStyle) -> Unit,
        para: (Int, Int, TextUnit) -> Unit,
    ) {
        val span = when (level) {
            1 -> SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = textColor)
            2 -> SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textColor)
            3 -> SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = textColor)
            else -> SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textColor)
        }
        val lineHeight = when (level) {
            1 -> 40.sp
            2 -> 34.sp
            3 -> 31.sp
            else -> 29.sp
        }
        style(start, end, span)
        para(start, end, lineHeight)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is NoteBodyLook) return false
        return doc === other.doc && activeLines == other.activeLines && bodySize == other.bodySize &&
            titleSize == other.titleSize && textColor == other.textColor && mutedColor == other.mutedColor &&
            faintColor == other.faintColor && accent == other.accent && codeBackground == other.codeBackground
    }

    override fun hashCode(): Int {
        var result = doc.hashCode()
        result = 31 * result + (activeLines?.hashCode() ?: 0)
        result = 31 * result + bodySize.hashCode()
        result = 31 * result + titleSize.hashCode()
        result = 31 * result + textColor.hashCode()
        result = 31 * result + mutedColor.hashCode()
        result = 31 * result + faintColor.hashCode()
        result = 31 * result + accent.hashCode()
        result = 31 * result + codeBackground.hashCode()
        return result
    }

    private companion object {
        /** Zero width, no line-break opportunity: hidden syntax keeps offsets and word wrapping. */
        const val ZERO = '⁠'
    }
}
