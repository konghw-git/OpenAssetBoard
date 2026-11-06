package com.assetboard.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.domain.notes.NoteMarkdown

/** Partial replies are visible immediately; only validated, complete edits enter the saved body. */
@Composable
fun AiStreamPreview(busy: Boolean, text: String) {
    if (!busy) return
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val rendered = remember(text, cs) {
        NoteBodyLook(NoteMarkdown.parse(text), null, 16.sp, 20.sp, cs.onSurface,
            cs.onSurfaceVariant, cs.outline, cs.primary, cs.surfaceContainerHighest,
            promoteTitle = false).filter(AnnotatedString(text)).text
    }
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (text.isEmpty()) "AI 正在准备…" else "AI 正在输出…", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (text.isNotEmpty()) Text(rendered, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(scroll))
        }
    }
}
