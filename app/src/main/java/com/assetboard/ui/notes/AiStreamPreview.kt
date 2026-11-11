package com.assetboard.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.domain.notes.NoteMarkdown

/** Partial replies are visible immediately; only validated, complete edits enter the saved body. */
@Composable
fun AiStreamPreview(
    busy: Boolean,
    text: String,
    status: String = "正在整理文字…",
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!busy) return
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val rendered = remember(text, cs) {
        NoteBodyLook(NoteMarkdown.parse(text), null, 16.sp, 20.sp, cs.onSurface,
            cs.onSurfaceVariant, cs.outline, cs.primary, cs.surfaceContainerHighest,
            promoteTitle = false).filter(AnnotatedString(text)).text
    }
    // Follow the measured content height, not the previous frame's scroll range.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.distinctUntilChanged().collect { scroll.scrollTo(it) }
    }
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh, modifier = modifier.fillMaxWidth().testTag("ai-stream-preview")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (text.isEmpty()) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary)
                Text(
                    if (text.isEmpty()) status else "正在生成 · ${text.count { !it.isWhitespace() }} 字",
                    style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("停止") }
            }
            if (text.isNotEmpty()) Text(rendered + AnnotatedString(" ▍"), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(scroll).padding(bottom = 8.dp))
        }
    }
}
