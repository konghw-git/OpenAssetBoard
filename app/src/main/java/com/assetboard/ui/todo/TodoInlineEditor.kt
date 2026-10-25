package com.assetboard.ui.todo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.assetboard.data.ChecklistItem
import com.assetboard.data.InlineItem
import com.assetboard.data.Todo
import com.assetboard.ui.components.glass
import java.util.UUID

/**
 * A todo edited where it sits on the list: its title and its checklist as fields, nothing else
 * (the notes, the date and the rest stay in the full editor). The keys work as in a notes app:
 *
 * - Enter splits the line at the cursor; at the end of a line that makes a new, empty item below;
 * - Enter on an empty item drops it and ends the edit, so a run of Enters never leaves blanks;
 * - Backspace at the start of an item joins it to the line above, which for an empty item
 *   deletes it;
 * - several lines pasted at once become as many items.
 *
 * Every item starts with an invisible [MARK], so that Backspace at its start reaches the field
 * as a change of text whatever the keyboard sends (soft keyboards often send nothing for a
 * Backspace in an empty field). Nothing is written while editing: [onCommit] gets the result
 * once, when the editor leaves the screen (完成, Back, another row, the app going to the
 * background), so a save never comes back to move the cursor. With [blankTitle] the title starts
 * empty under the stored one as a hint (a todo just made, still to be named); left empty, it keeps
 * the stored one. [onUndo], for a todo just made from others, offers to take it apart again for as
 * long as the edit lasts, where the snackbar's undo may be under the keyboard.
 */
@Composable
fun TodoInlineEditor(
    todo: Todo,
    checklist: List<ChecklistItem>,
    onCommit: (title: String, items: List<InlineItem>) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    blankTitle: Boolean = false,
    onUndo: (() -> Unit)? = null,
    /** The checklist item to start in, when the edit began with a tap on one; the title otherwise. */
    focusItem: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    var title by remember(todo.id) { mutableStateOf(if (blankTitle) TextFieldValue("") else TextFieldValue(todo.title, TextRange(todo.title.length))) }
    val lines = remember(todo.id) {
        mutableStateListOf<EditLine>().apply {
            addAll(checklist.sortedBy { it.sortOrder }.map { EditLine(it.id, it.id, it.done, marked(it.text)) })
        }
    }
    val requesters = remember(todo.id) { mutableMapOf<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    // The field to focus once it is on screen: the title when the edit starts, or the item tapped.
    var pending by remember(todo.id) { mutableStateOf<String?>(focusItem?.takeIf { id -> checklist.any { it.id == id } } ?: TITLE) }
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        // A new item's field exists only after the next frame; try a few.
        repeat(5) {
            withFrameNanos {}
            if (runCatching { requester(target).requestFocus() }.isSuccess) {
                pending = null
                return@LaunchedEffect
            }
        }
        pending = null
    }

    val commit by rememberUpdatedState(onCommit)
    DisposableEffect(todo.id) {
        onDispose { commit(title.text, lines.map { InlineItem(it.id, it.value.text.removePrefix(MARK)) }) }
    }
    val done by rememberUpdatedState(onDone)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) done() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    BackHandler { onDone() }

    /** Puts [text] into the field above line [i] (the title for the first), the cursor where they meet. */
    fun joinUp(i: Int, text: String) {
        lines.removeAt(i)
        if (i == 0) {
            val at = title.text.length
            title = TextFieldValue(title.text + text, TextRange(at))
            pending = TITLE
        } else {
            val above = lines[i - 1]
            val at = above.value.text.length
            lines[i - 1] = above.copy(value = TextFieldValue(above.value.text + text, TextRange(at)))
            pending = above.key
        }
    }

    /** New items from [parts], after line [after] (-1: right under the title); the cursor at the end of the last. */
    fun insertItems(after: Int, parts: List<String>) {
        val made = parts.map { EditLine(newKey(), null, false, marked(it)) }
        lines.addAll(after + 1, made)
        pending = made.last().key
    }

    val shape = TodoCardShape
    Column(
        modifier
            .fillMaxWidth()
            .glass(shape, glass, cs.primary.copy(alpha = 0.06f), elevation = 10.dp)
            .clip(shape)
            .border(1.5.dp, cs.primary.copy(alpha = 0.45f), shape)
            .animateContentSize()
            .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
            // The box as it stands; ticking it waits until the edit is over.
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp).clip(CircleShape).border(1.8.dp, if (todo.important) cs.error else cs.outline, CircleShape))
            }
            val titleStyle = todoTitleStyle.copy(color = cs.onSurface)
            Box(Modifier.weight(1f).padding(start = 2.dp, top = 6.dp, bottom = 6.dp)) {
                if (blankTitle && title.text.isEmpty()) Text("给新待办起个标题", style = titleStyle, color = cs.outline)
                BasicTextField(
                    value = title,
                    onValueChange = { v ->
                        if ('\n' !in v.text) {
                            title = v
                            return@BasicTextField
                        }
                        // Enter, or lines pasted: what follows the first line becomes items at the top.
                        val parts = v.text.split('\n')
                        title = TextFieldValue(parts.first(), TextRange(parts.first().length))
                        insertItems(-1, parts.drop(1))
                    },
                    textStyle = titleStyle,
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester(TITLE)),
                )
            }
        }
        lines.forEachIndexed { i, line ->
            key(line.key) {
                Row(Modifier.fillMaxWidth().padding(start = 13.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(14.dp).clip(CircleShape)
                            .background(if (line.done) cs.primary else cs.surface.copy(alpha = 0f))
                            .border(if (line.done) 0.dp else 1.4.dp, cs.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (line.done) Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(10.dp))
                    }
                    Spacer(Modifier.width(13.dp))
                    // Straight on the card, as the items read when not being edited; a hairline under the text marks the field.
                    val rule = cs.outlineVariant
                    Box(
                        Modifier.weight(1f)
                            .drawBehind { drawLine(rule, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), strokeWidth = 1.dp.toPx()) }
                            .padding(horizontal = 6.dp, vertical = 7.dp),
                    ) {
                        if (line.value.text.length <= 1) Text("检查项", style = checkItemStyle, color = cs.outline)
                        BasicTextField(
                            value = line.value,
                            onValueChange = { v -> onItemChange(lines, i, v, ::joinUp, ::insertItems, onEmptyEnter = { lines.removeAt(i); onDone() }) },
                            textStyle = checkItemStyle.copy(
                                color = if (line.done) cs.outline else cs.onSurface,
                                textDecoration = if (line.done) TextDecoration.LineThrough else null,
                            ),
                            cursorBrush = SolidColor(cs.primary),
                            modifier = Modifier.fillMaxWidth().focusRequester(requester(line.key)),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (lines.isEmpty()) "回车添加检查项" else "回车添加 · 空项回车结束 · 行首退格删除",
                style = MaterialTheme.typography.labelSmall, color = cs.outline, modifier = Modifier.weight(1f),
            )
            if (onUndo != null) {
                Text(
                    "撤销", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClickLabel = "撤销，拆回原来的待办", onClick = onUndo).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                "完成", style = MaterialTheme.typography.labelLarge, color = cs.primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onDone).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** One checklist line being edited: [id] for an item already saved, [key] to tell lines apart on screen. */
internal data class EditLine(val key: String, val id: String?, val done: Boolean, val value: TextFieldValue)

/** Sits at the start of every item field; see [TodoInlineEditor]. */
internal const val MARK = "​"

private const val TITLE = "title"

private fun newKey() = UUID.randomUUID().toString()

private fun marked(text: String) = TextFieldValue(MARK + text, TextRange(MARK.length + text.length))

/**
 * Line [i] typed into: a Backspace that took the mark joins it upward, a line break splits it,
 * anything else is kept with the cursor held after the mark.
 */
internal fun onItemChange(
    lines: MutableList<EditLine>,
    i: Int,
    v: TextFieldValue,
    joinUp: (Int, String) -> Unit,
    insertAfter: (Int, List<String>) -> Unit,
    onEmptyEnter: () -> Unit,
) {
    val old = lines.getOrNull(i) ?: return
    val text = v.text
    if (!text.startsWith(MARK)) {
        if (old.value.text.removePrefix(MARK) == text) {
            // Backspace at the start: exactly the mark went.
            joinUp(i, text)
        } else {
            // The mark went with a selection typed over: put it back.
            lines[i] = old.copy(value = TextFieldValue(MARK + text, TextRange(v.selection.start + 1, v.selection.end + 1)))
        }
        return
    }
    val body = text.removePrefix(MARK)
    if ('\n' in body) {
        val parts = body.split('\n')
        if (parts.all { it.isEmpty() } && old.value.text.removePrefix(MARK).isEmpty()) {
            onEmptyEnter()
            return
        }
        lines[i] = old.copy(value = TextFieldValue(MARK + parts.first(), TextRange(MARK.length + parts.first().length)))
        insertAfter(i, parts.drop(1))
        return
    }
    val sel = TextRange(v.selection.start.coerceAtLeast(MARK.length), v.selection.end.coerceAtLeast(MARK.length))
    lines[i] = old.copy(value = v.copy(selection = sel))
}
