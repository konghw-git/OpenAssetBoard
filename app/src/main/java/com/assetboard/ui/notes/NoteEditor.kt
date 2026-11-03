package com.assetboard.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Toc
import androidx.compose.material.icons.outlined.Undo
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetboard.data.NoteSession
import com.assetboard.domain.Dates
import com.assetboard.domain.notes.NoteMarkdown
import com.assetboard.domain.notes.NoteMarkdown.Kind
import com.assetboard.domain.notes.NoteMarkdownEdit
import com.assetboard.ui.components.GlassSheet
import com.assetboard.ui.components.glass
import com.assetboard.ui.components.verticalScrollWithIndicator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The note page's writing block: the shared [MarkdownBody] under the note's own chrome — the
 * unsaved banner, the word/task footer and the outline's jumps into the text.
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
    val density = LocalDensity.current
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
            MarkdownBody(
                state = state,
                minHeight = 360.dp,
                lineHeight = 30.sp,
                focusRequester = focus,
                onFocus = onFocus,
                hint = { style -> EmptyHint(style, cs.outline) },
                footer = { EditorFooter(doc, status, session.dirty, savedAt, value.text.isNotEmpty(), cs) },
            )
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


/**
 * The formatting bar, pinned above the keyboard while writing. On a wide screen the buttons
 * shrink until every one fits on a single row; on a narrow one the groups wrap into two or three
 * rows on their own, so no button hides behind a horizontal scroll.
 */
@Composable
fun NoteFormatBar(state: NoteEditorState, modifier: Modifier = Modifier) {
    val doc = state.document()
    val line = state.currentLine(doc)
    BoxWithConstraints(modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), glass, elevation = 12.dp)) {
        val wide = maxWidth >= 600.dp
        val buttonSize = if (wide) ((maxWidth - 60.dp) / 16).coerceIn(34.dp, 42.dp) else 42.dp
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BarGroup {
                FormatButton(Icons.Outlined.Undo, "撤销", active = false, enabled = state.canUndo, size = buttonSize) { state.undo() }
                FormatButton(Icons.Outlined.Redo, "重做", active = false, enabled = state.canRedo, size = buttonSize) { state.redo() }
            }
            BarGroup {
                BarDivider()
                HeadingButton("H1", "一级标题", line.kind == Kind.HEADING && line.level == 1, buttonSize) { state.format(Format.H1) }
                HeadingButton("H2", "二级标题", line.kind == Kind.HEADING && line.level == 2, buttonSize) { state.format(Format.H2) }
                HeadingButton("H3", "三级标题", line.kind == Kind.HEADING && line.level == 3, buttonSize) { state.format(Format.H3) }
            }
            BarGroup {
                BarDivider()
                FormatButton(Icons.Outlined.CheckBox, "待办清单", line.kind == Kind.CHECKLIST, size = buttonSize) { state.format(Format.CHECKLIST) }
                FormatButton(Icons.Outlined.FormatListBulleted, "无序列表", line.kind == Kind.BULLET, size = buttonSize) { state.format(Format.BULLET) }
                FormatButton(Icons.Outlined.FormatListNumbered, "有序列表", line.kind == Kind.NUMBERED, size = buttonSize) { state.format(Format.NUMBERED) }
                FormatButton(Icons.Outlined.FormatQuote, "引用", line.kind == Kind.QUOTE, size = buttonSize) { state.format(Format.QUOTE) }
                FormatButton(Icons.Outlined.HorizontalRule, "分割线", false, size = buttonSize) { state.format(Format.DIVIDER) }
            }
            BarGroup {
                BarDivider()
                FormatButton(Icons.Filled.FormatBold, "加粗", false, size = buttonSize) { state.format(Format.BOLD) }
                FormatButton(Icons.Filled.FormatItalic, "斜体", false, size = buttonSize) { state.format(Format.ITALIC) }
                FormatButton(Icons.Filled.FormatStrikethrough, "删除线", false, size = buttonSize) { state.format(Format.STRIKE) }
                FormatButton(Icons.Outlined.Code, "行内代码", false, size = buttonSize) { state.format(Format.CODE) }
            }
            BarGroup {
                BarDivider()
                FormatButton(Icons.Outlined.FormatIndentIncrease, "增加缩进", false, size = buttonSize) { state.format(Format.INDENT) }
                FormatButton(Icons.Outlined.FormatIndentDecrease, "减少缩进", false, size = buttonSize) { state.format(Format.OUTDENT) }
            }
        }
    }
}

private typealias Format = NoteMarkdownEdit.Format

/** One group of buttons; a wrap moves the whole group, so its divider never starts a row. */
@Composable
private fun BarGroup(content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun HeadingButton(text: String, description: String, active: Boolean, size: Dp = 42.dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size).clip(CircleShape)
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
private fun FormatButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    size: Dp = 42.dp,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (active) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .focusProperties { canFocus = false }
            .semantics { contentDescription = label }
            .clickable(interactionSource = source, indication = ripple(bounded = true), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = (if (active) cs.primary else cs.onSurfaceVariant).copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(21.dp),
        )
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
