package com.assetboard.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatIndentDecrease
import androidx.compose.material.icons.outlined.FormatIndentIncrease
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Toc
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.data.NoteSession
import com.assetboard.domain.Dates
import com.assetboard.domain.notes.NoteMarkdown
import com.assetboard.domain.notes.NoteMarkdown.Kind
import com.assetboard.domain.notes.NoteMarkdown.Line
import com.assetboard.domain.notes.NoteMarkdownEdit
import com.assetboard.ui.components.GlassSheet
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.verticalScrollWithIndicator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The writing surface: one text field showing a rendered Markdown subset, plus the real
 * checkboxes, dividers and quote bars drawn over it. The text itself is the plain Markdown the
 * session saves, so autosave, sync and backup are untouched.
 */
@Composable
fun NoteEditor(
    session: NoteSession,
    state: NoteEditorState,
    autoFocus: Boolean,
    scrollState: ScrollState,
    onFocus: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val status by session.status.collectAsStateWithLifecycle()
    val savedAt by session.savedAt.collectAsStateWithLifecycle()
    val value = state.value
    val doc = state.document()
    // External text (an append from the diary, a restore) lands under the cursor; our own
    // keystrokes are already equal and this is a no-op. Collecting without a state keeps the
    // page from recomposing a second time per keystroke.
    LaunchedEffect(session) {
        session.text.collect { text ->
            if (session.status.value != NoteSession.Status.UNSAVED) state.sync(text)
        }
    }
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
    val focus = remember { FocusRequester() }
    var editorTop by remember { mutableIntStateOf(0) }
    var textTop by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    SideEffect {
        val layout = state.layout
        val textLength = value.text.length
        state.jumpTo = { offset ->
            if (layout != null) {
                val line = layout.getLineForOffset(offset.coerceIn(0, textLength))
                scope.launch {
                    val top = editorTop + textTop + with(density) { 8.dp.toPx() }.toInt() + layout.getLineTop(line).roundToInt()
                    scrollState.animateScrollTo(top.coerceAtLeast(0))
                }
            }
        }
    }
    LaunchedEffect(session, autoFocus) {
        if (autoFocus && session.noteId == null) runCatching { focus.requestFocus() }
    }

    val style = MaterialTheme.typography.bodyLarge.copy(
        color = cs.onSurface,
        fontSize = 17.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    Column(Modifier.fillMaxWidth().onGloballyPositioned { editorTop = it.positionInParent().y.roundToInt() }) {
        if (status == NoteSession.Status.UNSAVED) {
            Surface(color = cs.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "无法保存，请先复制内容", color = cs.onErrorContainer, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("笔记", value.text))
                    }) { Text("复制全部") }
                }
            }
        }
        Box(Modifier.fillMaxWidth().onGloballyPositioned { textTop = it.positionInParent().y.roundToInt() }) {
            Column(Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 360.dp)) {
                    if (value.text.isEmpty()) EmptyHint(style, cs.outline)
                    BasicTextField(
                        value = value,
                        onValueChange = state::edit,
                        textStyle = style,
                        cursorBrush = SolidColor(cs.primary),
                        visualTransformation = look,
                        onTextLayout = { state.layout = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 360.dp)
                            .focusRequester(focus)
                            .onFocusChanged {
                                state.focused = it.isFocused
                                onFocus(it.isFocused)
                            },
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
                EditorFooter(doc, status, session.dirty, savedAt, value.text.isNotEmpty(), cs)
            }
        }
    }
}

@Composable
private fun EmptyHint(style: androidx.compose.ui.text.TextStyle, hint: Color) {
    Column {
        Text("标题", style = style.copy(color = hint, fontSize = 24.sp, fontWeight = FontWeight.SemiBold))
        Text("第一行是标题，接着写下你的想法。", style = style.copy(color = hint))
        Spacer(Modifier.height(6.dp))
        Text(
            "# 标题 · - [ ] 待办 · --- 分割线 · **加粗**",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = hint.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun EditorFooter(
    doc: NoteMarkdown.Doc,
    status: NoteSession.Status,
    dirty: Boolean,
    savedAt: Long?,
    hasText: Boolean,
    cs: androidx.compose.material3.ColorScheme,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${doc.wordCount} 字", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        if (doc.tasks.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(2.dp).clip(CircleShape).background(cs.outline.copy(alpha = 0.5f)))
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Outlined.CheckBox, null, tint = cs.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("${doc.doneTasks}/${doc.tasks.size}", style = MaterialTheme.typography.labelMedium, color = cs.primary)
        }
        Spacer(Modifier.weight(1f))
        val label = when {
            status != NoteSession.Status.READY -> null
            dirty -> "正在保存…"
            savedAt != null -> "已保存 " + Dates.time(savedAt)
            hasText -> "已保存"
            else -> null
        }
        label?.let {
            Icon(Icons.Outlined.CloudDone, null, tint = cs.outline, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = cs.outline)
        }
    }
}

private data class TaskTarget(
    val line: Line,
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
            Brush.horizontalGradient(listOf(Color.Transparent, hair.copy(alpha = 0.30f), hair.copy(alpha = 0.30f), Color.Transparent)),
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

/** The formatting bar, pinned above the keyboard while writing. */
@Composable
fun NoteFormatBar(state: NoteEditorState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val doc = state.document()
    val line = state.currentLine(doc)
    Box(modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), glass, elevation = 12.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeadingButton("H1", "一级标题", Format.H1, line.kind == Kind.HEADING && line.level == 1) { state.format(Format.H1) }
            HeadingButton("H2", "二级标题", Format.H2, line.kind == Kind.HEADING && line.level == 2) { state.format(Format.H2) }
            HeadingButton("H3", "三级标题", Format.H3, line.kind == Kind.HEADING && line.level == 3) { state.format(Format.H3) }
            BarDivider()
            FormatButton(Icons.Outlined.CheckBox, "待办清单", line.kind == Kind.CHECKLIST) { state.format(Format.CHECKLIST) }
            FormatButton(Icons.Outlined.FormatListBulleted, "无序列表", line.kind == Kind.BULLET) { state.format(Format.BULLET) }
            FormatButton(Icons.Outlined.FormatListNumbered, "有序列表", line.kind == Kind.NUMBERED) { state.format(Format.NUMBERED) }
            FormatButton(Icons.Outlined.FormatQuote, "引用", line.kind == Kind.QUOTE) { state.format(Format.QUOTE) }
            FormatButton(Icons.Outlined.HorizontalRule, "分割线", false) { state.format(Format.DIVIDER) }
            BarDivider()
            FormatButton(Icons.Filled.FormatBold, "加粗", false) { state.format(Format.BOLD) }
            FormatButton(Icons.Filled.FormatItalic, "斜体", false) { state.format(Format.ITALIC) }
            FormatButton(Icons.Filled.FormatStrikethrough, "删除线", false) { state.format(Format.STRIKE) }
            FormatButton(Icons.Outlined.Code, "行内代码", false) { state.format(Format.CODE) }
            BarDivider()
            FormatButton(Icons.Outlined.FormatIndentIncrease, "增加缩进", false) { state.format(Format.INDENT) }
            FormatButton(Icons.Outlined.FormatIndentDecrease, "减少缩进", false) { state.format(Format.OUTDENT) }
        }
    }
}

private typealias Format = NoteMarkdownEdit.Format

@Composable
private fun HeadingButton(text: String, description: String, format: Format, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(42.dp).clip(CircleShape)
            .background(if (active) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .focusProperties { canFocus = false }
            .semantics { contentDescription = description }
            .clickable(interactionSource = source, indication = ripple(bounded = true), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (active) cs.primary else cs.onSurfaceVariant)
    }
}

@Composable
private fun FormatButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(42.dp).clip(CircleShape)
            .background(if (active) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .focusProperties { canFocus = false }
            .semantics { contentDescription = label }
            .clickable(interactionSource = source, indication = ripple(bounded = true), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (active) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun BarDivider() {
    Box(
        Modifier.padding(horizontal = 4.dp).width(1.dp).height(22.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    )
}

/** The outline's way in while reading: a small glass pill above the tab bar's spot. */
@Composable
fun NoteOutlinePill(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.height(42.dp)
            .glass(RoundedCornerShape(50), glass, elevation = 8.dp)
            .clip(RoundedCornerShape(50))
            .focusProperties { canFocus = false }
            .semantics { contentDescription = "大纲" }
            .clickable(interactionSource = source, indication = ripple(bounded = true), onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Toc, null, tint = cs.primary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("大纲", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
    }
}

/** The document's structure: its headings, and a way to the first unfinished task. */
@Composable
fun NoteOutlineSheet(doc: NoteMarkdown.Doc, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    GlassSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScrollWithIndicator(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 18.dp).navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("大纲", style = MaterialTheme.typography.titleLarge)
                    Text(
                        buildString {
                            append("${doc.headings.size} 个小标题 · ${doc.wordCount} 字")
                            if (doc.tasks.isNotEmpty()) append(" · 待办 ${doc.doneTasks}/${doc.tasks.size}")
                        },
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Toc, null, tint = cs.primary, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            if (doc.headings.isEmpty()) {
                EmptyOutline()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    doc.headings.forEach { heading -> OutlineRow(heading) { onJump(heading.offset) } }
                }
            }
            if (doc.tasks.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = cs.outline.copy(alpha = 0.18f))
                Spacer(Modifier.height(10.dp))
                val first = doc.tasks.firstOrNull { !it.checked }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = first != null) { first?.let { onJump(it.start) } }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(cs.primary.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.CheckBox, null, tint = cs.primary, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("待办 ${doc.doneTasks}/${doc.tasks.size}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (first != null) "跳到第一条未完成" else "都完成了",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                    if (first != null) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.outline, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineRow(heading: NoteMarkdown.Heading, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(start = (8 + (heading.level - 1) * 14).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(cs.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "H${heading.level.coerceAtMost(6)}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.primary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            heading.text,
            style = if (heading.level <= 2) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (heading.level <= 2) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.outline, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EmptyOutline() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Toc, null, tint = cs.primary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("还没有小标题", style = MaterialTheme.typography.titleMedium)
        Text(
            "在正文里用 #、##、### 起一行，就会出现在这里。",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("# 一级", "## 二级", "### 三级").forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}
