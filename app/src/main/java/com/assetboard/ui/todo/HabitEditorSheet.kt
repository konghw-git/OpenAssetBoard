package com.assetboard.ui.todo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetboard.domain.Habit
import com.assetboard.ui.components.FieldShape
import com.assetboard.ui.components.GlassSheet
import com.assetboard.ui.components.HabitColors
import com.assetboard.ui.components.HabitIcons
import com.assetboard.ui.components.glassFieldColors
import com.assetboard.ui.components.iconVector
import com.assetboard.ui.components.tint
import com.assetboard.ui.components.verticalScrollWithIndicator

/** The form open under no row: a habit being added. */
private const val NEW = ""

/**
 * The daily habits, from the battery's 每日 pill: put them in order, rename or restyle one, add
 * one, take one off. A removed habit stays in the days it was part of; a new one counts from
 * today, so neither rewrites what the diary already shows.
 */
@Composable
internal fun HabitEditorSheet(habits: List<Habit>, vm: TodoViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // The key of the habit whose form is open, NEW for a new one, null for none.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val full = habits.size >= Habit.MAX_ACTIVE
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding().navigationBarsPadding()) {
            Text("每日打卡", style = MaterialTheme.typography.titleLarge)
            Text(
                "${habits.size}/${Habit.MAX_ACTIVE} 项 · 在待办页打勾，长按看打卡标准，日记里留底",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Column(
                Modifier.weight(1f, fill = false).verticalScrollWithIndicator(rememberScrollState()).padding(top = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                habits.forEachIndexed { i, h ->
                    key(h.key) {
                        HabitRow(
                            h, open = open == h.key,
                            canUp = i > 0, canDown = i < habits.lastIndex,
                            onOpen = { open = if (open == h.key) null else h.key },
                            onMove = { by ->
                                val keys = habits.map { it.key }.toMutableList()
                                keys.add(i + by, keys.removeAt(i))
                                vm.reorderHabits(keys)
                            },
                        )
                        AnimatedVisibility(open == h.key, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            HabitForm(
                                initial = h,
                                taken = habits.map { it.color },
                                onSave = { label, icon, color, rubric -> vm.updateHabit(h.key, label, icon, color, rubric) { open = null } },
                                onCancel = { open = null },
                                onRemove = if (habits.size > 1) ({ removing = h.key }) else null,
                            )
                        }
                    }
                }
                AnimatedVisibility(open == NEW, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    HabitForm(
                        initial = null,
                        taken = habits.map { it.color },
                        onSave = { label, icon, color, rubric -> vm.addHabit(label, icon, color, rubric) { open = null } },
                        onCancel = { open = null },
                        onRemove = null,
                    )
                }
            }
            if (open != NEW) {
                OutlinedButton(onClick = { open = NEW }, enabled = !full, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (full) "最多 ${Habit.MAX_ACTIVE} 项" else "添加打卡")
                }
            }
            Text(
                "新加的从今天算起；移除的不再打卡，过去的日记里仍留着它的记录。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
            )
        }
    }
    removing?.let { k ->
        val h = habits.firstOrNull { it.key == k }
        if (h == null) {
            removing = null
        } else {
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text("移除「${h.label}」？") },
                text = { Text("从今天起不再打这一项。过去的日记和周复盘里，它的记录照旧保留。") },
                confirmButton = {
                    TextButton(onClick = {
                        removing = null
                        vm.removeHabit(k) { open = null }
                    }) { Text("移除", color = cs.error) }
                },
                dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
            )
        }
    }
}

/** A habit as the battery shows it, with arrows to move it; tapping it opens its form. */
@Composable
private fun HabitRow(h: Habit, open: Boolean, canUp: Boolean, canDown: Boolean, onOpen: () -> Unit, onMove: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val edge by animateColorAsState(if (open) h.tint else Color.Transparent, tween(200), label = "habitEdge")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.onSurface.copy(alpha = 0.05f))
            .border(1.5.dp, edge, shape)
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(h.tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(h.iconVector, null, tint = h.tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val conditions = h.conditions
            if (conditions.isNotEmpty()) {
                Text(
                    "${conditions.size} 条标准 · " + conditions.joinToString("；"),
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { onMove(-1) }, enabled = canUp) { Icon(Icons.Rounded.KeyboardArrowUp, "上移") }
        IconButton(onClick = { onMove(1) }, enabled = canDown) { Icon(Icons.Rounded.KeyboardArrowDown, "下移") }
    }
}

/** Name, rubric, icon and color for a habit; [initial] null for a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitForm(initial: Habit?, taken: List<Long>, onSave: (String, String, Long, String) -> Unit, onCancel: () -> Unit, onRemove: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    var label by rememberSaveable(initial?.key) { mutableStateOf(initial?.label.orEmpty()) }
    var rubric by rememberSaveable(initial?.key) { mutableStateOf(initial?.rubric.orEmpty()) }
    var icon by rememberSaveable(initial?.key) { mutableStateOf(initial?.icon ?: "book") }
    var color by rememberSaveable(initial?.key) { mutableStateOf(initial?.color ?: (HabitColors.firstOrNull { it !in taken } ?: HabitColors.first())) }
    val tint = Color(color)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
        OutlinedTextField(
            label, { if (it.length <= Habit.MAX_LABEL) label = it },
            placeholder = { Text("比如：读书 20 分钟") },
            label = { Text(if (initial == null) "新的打卡" else "名字") },
            supportingText = { Text("${label.trim().length}/${Habit.MAX_LABEL}") },
            singleLine = true,
            shape = FieldShape,
            colors = glassFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        val kept = Habit.cleanRubric(rubric)
        OutlinedTextField(
            rubric, { rubric = it },
            label = { Text("打卡标准（可选）") },
            placeholder = { Text("每行一条，做到才算打卡，比如：\n不少于 30 分钟\n心率进入有氧区间") },
            supportingText = { Text("长按电池格时浮出来看，不用一条条勾 · ${kept.length}/${Habit.MAX_RUBRIC}") },
            isError = kept.length > Habit.MAX_RUBRIC,
            minLines = 2,
            maxLines = 6,
            shape = FieldShape,
            colors = glassFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("图标", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HabitIcons.forEach { (name, vector) ->
                val on = name == icon
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (on) tint else cs.onSurface.copy(alpha = 0.05f))
                        .clickable { icon = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(vector, name, tint = if (on) Color.White else cs.onSurfaceVariant, modifier = Modifier.size(19.dp))
                }
            }
        }
        Text("颜色", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HabitColors.forEach { c ->
                val on = c == color
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .then(if (on) Modifier.border(2.dp, cs.surface, CircleShape) else Modifier)
                        .clickable { color = c },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Icon(Icons.Default.Check, "已选", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onRemove != null) TextButton(onClick = onRemove) { Text("移除", color = cs.error) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("取消") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { onSave(label, icon, color, rubric) }, enabled = label.isNotBlank() && kept.length <= Habit.MAX_RUBRIC) { Text(if (initial == null) "添加" else "保存") }
        }
    }
}
