package com.assetboard.ui.health

import com.assetboard.domain.AppClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.assetboard.ui.components.FieldShape
import com.assetboard.ui.components.glassFieldColors
import com.assetboard.ui.components.GlassSheet
import com.assetboard.data.BodyMeasurement
import com.assetboard.data.HealthProfile
import com.assetboard.data.MeasurementDraft
import com.assetboard.domain.health.Health
import com.assetboard.ui.components.ConfirmDialog
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.SelectField
import java.time.LocalDate
import com.assetboard.ui.components.verticalScrollWithIndicator

/**
 * One screen: weight (required), body fat (optional), the day (today unless changed, never the
 * future), a folded note. Saving onto a day that already has a record asks first, and only a
 * confirmation overwrites it.
 */
@Composable
fun HealthEditSheet(
    existing: BodyMeasurement?,
    records: List<BodyMeasurement>,
    profile: HealthProfile?,
    onDismiss: () -> Unit,
    onSave: (MeasurementDraft, overwrite: Boolean) -> Unit,
    onDelete: (BodyMeasurement) -> Unit,
) {
    val today = AppClock.today()
    val previous = records.lastOrNull { it.id != existing?.id }
    var weight by rememberSaveable { mutableStateOf(existing?.let { Health.kgInput(it.weightG) } ?: "") }
    var fat by rememberSaveable { mutableStateOf(existing?.bodyFatBp?.let(Health::percentInput) ?: "") }
    var date by rememberSaveable { mutableStateOf(existing?.date ?: today.toString()) }
    var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
    var showNote by rememberSaveable { mutableStateOf(!existing?.note.isNullOrBlank()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    // A new record starts in the weight field: two numbers and save.
    LaunchedEffect(Unit) { if (existing == null) runCatching { focus.requestFocus() } }

    val weightG = Health.parseKg(weight)
    val weightError = when {
        weight.isBlank() -> null
        weightG == null -> "请输入数字，例如 92.1"
        weightG < Health.WEIGHT_MIN_G || weightG > Health.WEIGHT_MAX_G -> "体重需在 20–300 kg 之间"
        else -> null
    }
    val fatBp = Health.parsePercent(fat)
    val fatError = when {
        fat.isBlank() -> null
        fatBp == null -> "请输入数字，例如 25.8"
        fatBp < Health.FAT_MIN_BP || fatBp > Health.FAT_MAX_BP -> "体脂率需在 3%–70% 之间"
        else -> null
    }
    val occupant = records.firstOrNull { it.date == date && it.id != existing?.id }
    val ready = weightG != null && weightError == null && fatError == null
    val draft = { MeasurementDraft(existing?.id, LocalDate.parse(date), weightG!!, fatBp.takeIf { fat.isNotBlank() }, note.takeIf { showNote }) }

    GlassSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScrollWithIndicator(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (existing == null) "记录身体数据" else "编辑记录", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            occupant != null -> "这一天已有记录（${Health.kg(occupant.weightG)} kg），保存会覆盖它"
                            previous != null -> "上次 ${dayLabel(previous.date)} · ${Health.kg(previous.weightG)} kg" + (previous.bodyFatBp?.let { " · ${Health.percent(it)}%" } ?: "")
                            else -> "体重必填，体脂率没称可以不填"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (occupant != null) cs.secondary else cs.onSurfaceVariant,
                    )
                }
                if (existing != null) TextButton(onClick = { onDelete(existing) }) { Text("删除", color = cs.error) }
            }
            OutlinedTextField(
                value = weight,
                onValueChange = { weight = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("体重") },
                suffix = { Text("kg") },
                singleLine = true,
                isError = weightError != null,
                supportingText = weightError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                shape = FieldShape,
                colors = glassFieldColors(),
            )
            OutlinedTextField(
                value = fat,
                onValueChange = { fat = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("体脂率（可选）") },
                suffix = { Text("%") },
                singleLine = true,
                isError = fatError != null,
                supportingText = (fatError ?: "只有普通体重秤时留空").let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                shape = FieldShape,
                colors = glassFieldColors(),
            )
            SelectField(
                label = "日期",
                value = dayLabel(date).let { if (it.contains("月")) it else "$it · ${LocalDate.parse(date).monthValue}月${LocalDate.parse(date).dayOfMonth}日" },
                onClick = { picking = true },
                icon = { Icon(Icons.Default.CalendarMonth, null) },
                supportingText = "出差回来可以补录之前的日子",
            )
            AnimatedVisibility(showNote) {
                OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), maxLines = 3, shape = FieldShape, colors = glassFieldColors())
            }
            if (!showNote) TextButton(onClick = { showNote = true }) { Text("添加备注") }
            if (profile != null && weightG != null && weightError == null) {
                val bmi = Health.bmi(weightG, profile.heightMm)!!
                Text(
                    "BMI ${Health.bmiText(bmi)} · ${Health.bmiGrade(bmi).label}" +
                        (Health.fatMassG(weightG, fatBp.takeIf { fatError == null && fat.isNotBlank() })?.let { " · 脂肪 ${Health.kg(it)} kg" } ?: ""),
                    style = MaterialTheme.typography.labelLarge, color = cs.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { if (occupant != null) confirm = true else onSave(draft(), false) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(if (occupant != null) "覆盖这一天" else "保存") }
        }
    }
    if (picking) {
        DatePickDialog(
            initial = LocalDate.parse(date),
            maxDate = today,
            onDismiss = { picking = false },
            onPick = { date = it.toString(); picking = false },
        )
    }
    if (confirm && occupant != null) {
        ConfirmDialog(
            title = "覆盖${dayLabel(date)}的记录？",
            text = "这一天已有 ${Health.kg(occupant.weightG)} kg" + (occupant.bodyFatBp?.let { " · 体脂 ${Health.percent(it)}%" } ?: "") +
                " 的记录。一天只保留一条，保存后会被这次的数字替换。",
            confirmLabel = "覆盖",
            onConfirm = { confirm = false; onSave(draft(), true) },
            onDismiss = { confirm = false },
        )
    }
}
