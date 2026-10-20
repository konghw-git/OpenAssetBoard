package com.assetboard.ui.diary

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetboard.data.Note
import com.assetboard.data.NoteText
import com.assetboard.data.NoteTopic
import com.assetboard.ui.notes.NotePicker
import com.assetboard.ui.notes.extractBlock
import com.assetboard.ui.notes.extractBody
import com.assetboard.ui.notes.dayLabel
import java.time.LocalDate

/**
 * Copies parts of a day's diary into a note; the diary itself stays as it is. Read-only apart
 * from the optional title, so opening it never brings up the keyboard. Appending picks the note
 * on a second page of the same sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractSheet(
    date: LocalDate,
    text: String,
    notes: List<Note>,
    topics: List<NoteTopic>,
    onDismiss: () -> Unit,
    onSaveNew: (body: String) -> Unit,
    onAppend: (note: Note, block: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val paragraphs = remember(text) { NoteText.paragraphs(text) }
    // A single paragraph is what there is to take; with more, the user picks.
    var picked by rememberSaveable(text) { mutableStateOf(if (paragraphs.size == 1) listOf(0) else emptyList()) }
    var title by rememberSaveable { mutableStateOf("") }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val chosen = paragraphs.filterIndexed { i, _ -> i in picked }
    val all = picked.size == paragraphs.size

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Back from the picker returns to the paragraphs, not out of the sheet.
        BackHandler(choosing) { choosing = false }
        AnimatedContent(
            choosing,
            transitionSpec = {
                val forward = targetState
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
            },
            label = "extract",
        ) { picking ->
            if (picking) {
                NotePicker(
                    notes, topics,
                    onBack = { choosing = false },
                    onPick = { n -> onAppend(n, extractBlock(date, chosen)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).imePadding().navigationBarsPadding(),
                )
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding().navigationBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("提炼到笔记", style = MaterialTheme.typography.titleLarge)
                            Text("${dayLabel(date)}的日记 · 勾选要留下的段落，日记本身不变", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        if (paragraphs.size > 1) {
                            TextButton(onClick = { picked = if (all) emptyList() else paragraphs.indices.toList() }) { Text(if (all) "全不选" else "全选") }
                        }
                    }
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            title, { title = it.replace('\n', ' ') },
                            label = { Text("标题（可选）") },
                            placeholder = { Text("不填就用第一段的第一行") },
                            supportingText = { Text("存为新笔记时放在第一行；追加到已有笔记时不用") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        paragraphs.forEachIndexed { i, p ->
                            Paragraph(p, i in picked) { picked = if (i in picked) picked - i else (picked + i).sorted() }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { choosing = true }, enabled = chosen.isNotEmpty(), modifier = Modifier.weight(1f).height(50.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("追加到笔记…")
                        }
                        Button(onClick = { onSaveNew(extractBody(title, chosen)) }, enabled = chosen.isNotEmpty(), modifier = Modifier.weight(1f).height(50.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.NoteAdd, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (chosen.isEmpty()) "存为新笔记" else "存为新笔记 · ${chosen.size}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Paragraph(text: String, checked: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (checked) cs.primary.copy(alpha = 0.08f) else cs.surfaceContainerHigh, tween(180), label = "para")
    val edge by animateColorAsState(if (checked) cs.primary.copy(alpha = 0.55f) else Color.Transparent, tween(180), label = "edge")
    Surface(color = bg, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, edge), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(onClick = onToggle).padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked, { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = cs.primary))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (checked) cs.onSurface else cs.onSurfaceVariant,
                maxLines = 8, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
