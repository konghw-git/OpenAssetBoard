package com.assetboard.ui.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.domain.notes.NoteMarkdown
import com.assetboard.domain.notes.NoteMarkdown.Kind
import kotlin.math.roundToInt

/**
 * The writing surface the note and diary pages share: one text field showing a rendered Markdown
 * subset, with the real checkboxes, dividers and quote bars drawn over it. The text itself is the
 * plain Markdown the session saves, so autosave, sync and backup are untouched; the hosts keep
 * their own chrome (banners, footers, menus) and hand the differences in as slots.
 */
@Composable
fun MarkdownBody(
    state: NoteEditorState,
    modifier: Modifier = Modifier,
    minHeight: Dp = 360.dp,
    lineHeight: TextUnit = 30.sp,
    focusRequester: FocusRequester? = null,
    /** Extra modifiers on the text field itself: the diary's selection menu, say. */
    fieldExtras: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
    /** Said instead of the text when the body is empty. */
    hint: @Composable (TextStyle) -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val value = state.value
    val doc = state.document()
    val activeLines = remember(doc, value.selection, state.focused) {
        if (state.focused) doc.lineIndexRange(value.selection.start, value.selection.end) else null
    }
    val look = remember(doc, activeLines, cs) {
        NoteBodyLook(
            doc = doc,
            activeLines = activeLines,
            bodySize = 17.sp,
            titleSize = 26.sp,
            textColor = cs.onSurface,
            mutedColor = cs.onSurfaceVariant,
            faintColor = cs.outline,
            accent = cs.primary,
            codeBackground = cs.primary.copy(alpha = 0.06f),
        )
    }
    val density = LocalDensity.current
    val targets = remember(state.layout, doc, density) { taskTargets(state.layout, doc, density) }
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = cs.onSurface,
        fontSize = 17.sp,
        lineHeight = lineHeight,
        letterSpacing = 0.3.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = minHeight)) {
            if (value.text.isEmpty()) hint(style)
            BasicTextField(
                value = value,
                onValueChange = state::edit,
                textStyle = style,
                cursorBrush = SolidColor(cs.primary),
                visualTransformation = look,
                onTextLayout = { state.layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged {
                        state.focused = it.isFocused
                        onFocus(it.isFocused)
                    }
                    .then(fieldExtras),
            )
            Canvas(Modifier.matchParentSize()) {
                val layout = state.layout ?: return@Canvas
                drawTaskBoxes(targets, cs.primary, cs.onPrimary, cs.outline)
                drawDividers(doc, activeLines, layout, cs.outline, cs.primary)
                drawQuoteBars(doc, activeLines, layout, cs.primary)
            }
            targets.forEach { target ->
                TaskToggle(target) { state.toggleTask(target.line) }
            }
        }
        Spacer(Modifier.height(10.dp))
        footer()
    }
}

private data class TaskTarget(
    val line: NoteMarkdown.Line,
    val boxLeft: Float,
    val boxTop: Float,
    val boxSize: Float,
    val touchLeft: Int,
    val touchTop: Int,
)

private fun taskTargets(layout: TextLayoutResult?, doc: NoteMarkdown.Doc, density: Density): List<TaskTarget> {
    if (layout == null || doc.tasks.isEmpty() || layout.layoutInput.text.isEmpty()) return emptyList()
    // The layout is built from the transformed text (zero-width markers and spaces), which has
    // the same length and offsets as the body; a length check is enough to rule out a stale one.
    if (layout.layoutInput.text.length != doc.text.length) return emptyList()
    return doc.tasks.mapNotNull { line ->
        val marker = line.markerStart.coerceIn(0, layout.layoutInput.text.length - 1)
        val lineIndex = layout.getLineForOffset(marker)
        val top = layout.getLineTop(lineIndex)
        val bottom = layout.getLineBottom(lineIndex)
        val left = layout.getBoundingBox(marker).left
        val boxSize = with(density) { 19.dp.toPx() }
        val boxLeft = left + with(density) { 1.dp.toPx() }
        val boxTop = (top + bottom) / 2f - boxSize / 2f
        val touch = with(density) { 32.dp.toPx() }.roundToInt()
        TaskTarget(
            line = line,
            boxLeft = boxLeft,
            boxTop = boxTop,
            boxSize = boxSize,
            touchLeft = (boxLeft + boxSize / 2f - touch / 2f).roundToInt().coerceAtLeast(0),
            touchTop = ((top + bottom) / 2f).roundToInt() - touch / 2,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTaskBoxes(
    targets: List<TaskTarget>,
    accent: Color,
    onAccent: Color,
    border: Color,
) {
    val radius = CornerRadius(5.dp.toPx())
    for (target in targets) {
        val topLeft = Offset(target.boxLeft, target.boxTop)
        val size = Size(target.boxSize, target.boxSize)
        if (target.line.checked) {
            drawRoundRect(accent, topLeft, size, radius)
            val p = Path().apply {
                moveTo(target.boxLeft + target.boxSize * 0.24f, target.boxTop + target.boxSize * 0.52f)
                lineTo(target.boxLeft + target.boxSize * 0.43f, target.boxTop + target.boxSize * 0.70f)
                lineTo(target.boxLeft + target.boxSize * 0.77f, target.boxTop + target.boxSize * 0.31f)
            }
            drawPath(p, onAccent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawRoundRect(border.copy(alpha = 0.42f), topLeft, size, radius, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDividers(
    doc: NoteMarkdown.Doc,
    activeLines: IntRange?,
    layout: TextLayoutResult,
    hair: Color,
    accent: Color,
) {
    if (layout.layoutInput.text.length != doc.text.length) return
    for (line in doc.lines) {
        if (line.kind != Kind.DIVIDER || activeLines?.contains(line.index) == true) continue
        val lineIndex = layout.getLineForOffset(line.start.coerceIn(0, layout.layoutInput.text.length - 1))
        val y = (layout.getLineTop(lineIndex) + layout.getLineBottom(lineIndex)) / 2f
        drawLine(
            androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, hair.copy(alpha = 0.30f), hair.copy(alpha = 0.30f), Color.Transparent)),
            Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
        )
        drawCircle(accent.copy(alpha = 0.45f), radius = 1.5.dp.toPx(), center = Offset(size.width / 2f, y))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQuoteBars(
    doc: NoteMarkdown.Doc,
    activeLines: IntRange?,
    layout: TextLayoutResult,
    accent: Color,
) {
    if (layout.layoutInput.text.length != doc.text.length) return
    for (line in doc.lines) {
        if (line.kind != Kind.QUOTE || activeLines?.contains(line.index) == true) continue
        val first = layout.getLineForOffset(line.start.coerceIn(0, layout.layoutInput.text.length - 1))
        val last = layout.getLineForOffset((line.end - 1).coerceIn(line.start, layout.layoutInput.text.length - 1))
        val top = layout.getLineTop(first)
        val bottom = layout.getLineBottom(last)
        val x = layout.getBoundingBox(line.markerStart.coerceIn(0, layout.layoutInput.text.length - 1)).left + 1.dp.toPx()
        val barTop = top + 5.dp.toPx()
        val barHeight = (bottom - top - 10.dp.toPx()).coerceAtLeast(4.dp.toPx())
        drawRoundRect(
            accent.copy(alpha = 0.30f), Offset(x, barTop), Size(2.5.dp.toPx(), barHeight), CornerRadius(2.dp.toPx()),
        )
    }
}

@Composable
private fun TaskToggle(target: TaskTarget, onToggle: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .offset { IntOffset(target.touchLeft, target.touchTop) }
            .size(32.dp)
            .focusProperties { canFocus = false }
            .toggleable(
                value = target.line.checked,
                interactionSource = source,
                indication = null,
                role = Role.Checkbox,
                onValueChange = {
                    onToggle()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .semantics { contentDescription = "待办：${target.line.content.trim().ifEmpty { "空待办" }}" },
    )
}
