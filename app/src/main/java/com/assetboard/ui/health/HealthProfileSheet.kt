package com.assetboard.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.assetboard.data.HealthProfile
import com.assetboard.data.Sex
import com.assetboard.domain.health.Health
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.SelectField
import java.time.LocalDate

/**
 * Sex, birthday, height and an optional target weight. Height turns weight into BMI (changing
 * it recomputes every past BMI, which is never stored); the birthday gives the age of each
 * measurement day for the body fat bands.
 */
@Composable
fun HealthProfileSheet(
    profile: HealthProfile?,
    onDismiss: () -> Unit,
    onSave: (Sex, LocalDate, Int, Long?) -> Unit,
) {
    val today = LocalDate.now()
    var sex by rememberSaveable { mutableStateOf(profile?.sex ?: Sex.MALE) }
    var birth by rememberSaveable { mutableStateOf(profile?.birthDate) }
    var height by rememberSaveable { mutableStateOf(profile?.let { Health.cmInput(it.heightMm) } ?: "") }
    var target by rememberSaveable { mutableStateOf(profile?.targetWeightG?.let(Health::kgInput) ?: "") }
    var picking by rememberSaveable { mutableStateOf(false) }

    val heightMm = Health.parseCm(height)
    val heightError = when {
        height.isBlank() -> null
        heightMm == null -> "请输入数字，例如 182"
        heightMm < Health.HEIGHT_MIN_MM || heightMm > Health.HEIGHT_MAX_MM -> "身高需在 100–250 cm 之间"
        else -> null
    }
    val targetG = Health.parseKg(target)
    val targetError = when {
        target.isBlank() -> null
        targetG == null -> "请输入数字，例如 75"
        targetG < Health.WEIGHT_MIN_G || targetG > Health.WEIGHT_MAX_G -> "目标体重需在 20–300 kg 之间"
        else -> null
    }
    val ready = birth != null && heightMm != null && heightError == null && targetError == null
    val bmi22 = if (heightMm != null && heightError == null) Health.bmiWeightG(heightMm, Health.TARGET_BMI) else null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("健康档案", style = MaterialTheme.typography.titleLarge)
            Text(
                "身高用来算 BMI，生日和性别决定体脂率的分级。只保存在这台手机上，跟着备份走。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Segmented(Sex.entries, sex, { if (it == Sex.MALE) "男" else "女" }) { sex = it }
            SelectField(
                label = "生日",
                value = birth?.let { LocalDate.parse(it).let { d -> "${d.year}年${d.monthValue}月${d.dayOfMonth}日" } } ?: "选择生日",
                onClick = { picking = true },
                icon = { Icon(Icons.Default.CalendarMonth, null) },
            )
            OutlinedTextField(
                value = height,
                onValueChange = { height = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("身高") },
                suffix = { Text("cm") },
                singleLine = true,
                isError = heightError != null,
                supportingText = heightError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = target,
                onValueChange = { target = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("目标体重（可选）") },
                suffix = { Text("kg") },
                singleLine = true,
                isError = targetError != null,
                supportingText = { Text(targetError ?: (bmi22?.let { "留空就按 BMI 22 计算：${Health.kg(it)} kg" } ?: "留空就按 BMI 22 计算")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onSave(sex, LocalDate.parse(birth), heightMm!!, targetG.takeIf { target.isNotBlank() }) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("保存") }
        }
    }
    if (picking) {
        DatePickDialog(
            initial = birth?.let(LocalDate::parse) ?: LocalDate.of(2000, 1, 1),
            maxDate = today,
            onDismiss = { picking = false },
            onPick = { birth = it.toString(); picking = false },
        )
    }
}
