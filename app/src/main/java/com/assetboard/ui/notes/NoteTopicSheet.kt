package com.assetboard.ui.notes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetboard.ui.components.GlassSheet
import com.assetboard.data.NoteTopic
import com.assetboard.ui.components.pressScale
import com.assetboard.ui.components.LocalAmbient
import com.assetboard.ui.components.glass
import com.assetboard.ui.theme.AppTheme
import com.assetboard.ui.components.verticalScrollWithIndicator

/** How many topics before the sheet offers a search field. */
private const val SEARCH_FROM = 8

/**
 * Every topic in one place, however many there are: a grid of chips with their counts, a search
 * once the grid gets long, and a new topic made on the spot. Used to filter the list and to file
 * a note. Keys are [NotesViewModel.ALL], [NotesViewModel.UNSORTED] or a topic id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicSheet(
    title: String,
    topics: List<NoteTopic>,
    counts: Map<String?, Int>?,
    selected: String,
    showAll: Boolean,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    onManage: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var naming by rememberSaveable { mutableStateOf<String?>(null) }
    val q = query.trim()
    val matches = if (q.isEmpty()) topics else topics.filter { it.name.contains(q, ignoreCase = true) }
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(if (topics.isEmpty()) "还没有主题" else "${topics.size} 个主题", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (onManage != null && topics.isNotEmpty()) {
                    TextButton(onClick = onManage) {
                        Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("管理")
                    }
                }
            }
            if (topics.size > SEARCH_FROM) {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("找主题") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            Column(Modifier.weight(1f, fill = false).verticalScrollWithIndicator(rememberScrollState()).padding(top = 14.dp, bottom = 12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showAll && q.isEmpty()) {
                        FilterPill("全部", counts?.values?.sum(), cs.primary, selected == NotesViewModel.ALL, null) { onPick(NotesViewModel.ALL) }
                    }
                    matches.forEach { t ->
                        val c = AppTheme.extra.group(topics.indexOf(t))
                        FilterPill(t.name, counts?.let { it[t.id] ?: 0 }, c, selected == t.id, c) { onPick(t.id) }
                    }
                    if (q.isEmpty()) {
                        FilterPill("未归类", counts?.let { it[null] ?: 0 }, cs.onSurfaceVariant, selected == NotesViewModel.UNSORTED, cs.outline) { onPick(NotesViewModel.UNSORTED) }
                    }
                }
                if (q.isNotEmpty() && matches.none { it.name.equals(q, ignoreCase = true) }) {
                    Text(
                        if (matches.isEmpty()) "没有叫「$q」的主题" else "没有完全叫「$q」的主题",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
            OutlinedButton(
                onClick = { naming = q },
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(bottom = 0.dp),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (q.isNotEmpty() && matches.none { it.name.equals(q, ignoreCase = true) }) "新建「$q」" else "新建主题")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    naming?.let { initial ->
        TopicNameDialog("新建主题", initial, onDismiss = { naming = null }) { name ->
            naming = null
            onCreate(name)
        }
    }
}

/** A topic chip: its colour dot, name and count; picked, it fills with the colour. */
@Composable
internal fun FilterPill(name: String, count: Int?, color: Color, selected: Boolean, dot: Color?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) color else color.copy(alpha = 0f), tween(220), label = "pill")
    val onGlass = LocalAmbient.current != null
    // Dark mode's palette is light: picked text goes dark on it instead of white.
    val onColor = if (color.luminance() > 0.5f) Color(0xFF12201C) else Color.White
    val fg = if (selected) onColor else cs.onSurface
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(38.dp)
            .pressScale(source)
            .then(
                when {
                    selected -> Modifier.shadow(4.dp, RoundedCornerShape(50), spotColor = color)
                    onGlass -> Modifier.glass(RoundedCornerShape(50), glass, elevation = 5.dp)
                    else -> Modifier.shadow(1.dp, RoundedCornerShape(50), spotColor = Color.Black.copy(alpha = 0.1f)).background(cs.surfaceContainer, RoundedCornerShape(50))
                },
            )
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = if (dot != null) 12.dp else 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) onColor else dot))
            Spacer(Modifier.width(7.dp))
        }
        Text(name, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = if (selected) onColor.copy(alpha = 0.8f) else cs.onSurfaceVariant)
        }
    }
}
