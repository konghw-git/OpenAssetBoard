package com.assetboard.ui.health

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetboard.data.FoodDraft
import com.assetboard.data.FoodEntry
import com.assetboard.data.FoodRepository
import com.assetboard.data.FoodUnit
import com.assetboard.domain.LedgerException
import com.assetboard.domain.health.Energy
import com.assetboard.ui.components.FieldShape
import com.assetboard.ui.components.GlassSheet
import com.assetboard.ui.components.SegmentStyle
import com.assetboard.ui.components.SegmentedControl
import com.assetboard.ui.components.SelectField
import com.assetboard.ui.components.DatePickDialog
import com.assetboard.ui.components.glassFieldColors
import com.assetboard.ui.components.verticalScrollWithIndicator
import com.assetboard.ui.theme.NumberStyle
import java.time.LocalDate

/**
 * One thing eaten. Most food has no label, so the sheet opens on a single number: roughly how many
 * kcal one portion was, and how many portions (水煮蛋 × 2); the nutrients are optional and folded
 * away. With a label: its reference amount, energy in kJ or kcal and how much went down, worked
 * out as it is typed; energy left empty is estimated from the three nutrients. Something eaten
 * before fills everything in one tap, so a daily carton of milk is a tap and 保存.
 */
@Composable
fun FoodEditSheet(
    existing: FoodEntry?,
    /** The day a new entry goes to. */
    date: LocalDate,
    recent: List<FoodEntry>,
    onDismiss: () -> Unit,
    onSave: (FoodDraft) -> Unit,
    onDelete: (FoodEntry) -> Unit,
) {
    val today = LocalDate.now()
    val label = existing?.takeIf { it.unit in FoodUnit.labelUnits }
    var labelled by rememberSaveable { mutableStateOf(label != null) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var per by rememberSaveable { mutableStateOf(label?.per?.toString() ?: "100") }
    var unit by rememberSaveable { mutableStateOf(label?.unit ?: FoodUnit.G) }
    var amount by rememberSaveable { mutableStateOf(label?.amount?.toString() ?: "") }
    // Without a label: how many portions, in halves.
    var halves by rememberSaveable { mutableIntStateOf(existing?.let(Energy::servingHalves) ?: Energy.SERVING_PER) }
    var kj by rememberSaveable { mutableStateOf(existing?.inKj ?: false) }
    var energy by rememberSaveable { mutableStateOf(existing?.let { Energy.energyInput(it.energyCal, it.inKj) } ?: "") }
    var protein by rememberSaveable { mutableStateOf(existing?.proteinMg?.let(Energy::gramsInput) ?: "") }
    var fat by rememberSaveable { mutableStateOf(existing?.fatMg?.let(Energy::gramsInput) ?: "") }
    var carb by rememberSaveable { mutableStateOf(existing?.carbMg?.let(Energy::gramsInput) ?: "") }
    var sodium by rememberSaveable { mutableStateOf(existing?.sodiumMg?.toString() ?: "") }
    var day by rememberSaveable { mutableStateOf(existing?.date ?: date.toString()) }
    // The optional nutrients, folded away until asked for or already filled in; they fold up again.
    var nutrients by rememberSaveable { mutableStateOf(existing?.let { hasNutrients(it) } ?: false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    // The recent foods past the first two rows, unfolded.
    var recentOpen by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val amountFocus = remember { FocusRequester() }
    // Bumped by picking something eaten before: the amount takes the focus, ready to change.
    var filled by remember { mutableIntStateOf(0) }
    LaunchedEffect(filled) { if (filled > 0 && labelled) runCatching { amountFocus.requestFocus() } }

    fun fill(e: FoodEntry) {
        labelled = e.unit in FoodUnit.labelUnits
        name = e.name.orEmpty()
        if (labelled) {
            per = e.per.toString()
            unit = e.unit!!
            amount = e.amount.toString()
        }
        halves = Energy.servingHalves(e)
        kj = e.inKj
        energy = Energy.energyInput(e.energyCal, e.inKj)
        protein = e.proteinMg?.let(Energy::gramsInput) ?: ""
        fat = e.fatMg?.let(Energy::gramsInput) ?: ""
        carb = e.carbMg?.let(Energy::gramsInput) ?: ""
        sodium = e.sodiumMg?.toString() ?: ""
        nutrients = hasNutrients(e)
        filled++
    }

    // ------------------------------------------------------------ what the fields say
    fun bad(text: String, parsed: Any?) = text.isNotBlank() && parsed == null
    val perV = Energy.parseAmount(per)?.takeIf { it in 1..Energy.PER_MAX }
    val amountV = Energy.parseAmount(amount)?.takeIf { it in 1..Energy.AMOUNT_MAX }
    val proteinV = Energy.parseGrams(protein)
    val fatV = Energy.parseGrams(fat)
    val carbV = Energy.parseGrams(carb)
    val sodiumV = Energy.parseMilligrams(sodium)
    val typed = Energy.parseEnergy(energy, kj)
    val atwater = if (proteinV != null && fatV != null && carbV != null) Energy.atwaterCal(proteinV, fatV, carbV) else null
    val energyCal = if (energy.isBlank()) atwater else typed
    val fieldError = bad(energy, typed) || bad(protein, proteinV) || bad(fat, fatV) || bad(carb, carbV) || bad(sodium, sodiumV) ||
        (labelled && (bad(per, perV) || bad(amount, amountV)))
    // One portion is stored as no count at all, as before portions could be counted.
    val counted = !labelled && halves != Energy.SERVING_PER
    val entry = energyCal?.takeIf { !fieldError && (!labelled || (perV != null && amountV != null)) }?.let {
        FoodEntry(
            id = existing?.id ?: "", date = day, eatenAt = 0, name = name.trim().takeIf { n -> n.isNotEmpty() },
            per = if (labelled) perV else if (counted) Energy.SERVING_PER else null,
            amount = if (labelled) amountV else if (counted) halves else null,
            unit = if (labelled) unit else if (counted) FoodUnit.SERVING else null,
            energyCal = it, inKj = kj, proteinMg = proteinV, fatMg = fatV, carbMg = carbV, sodiumMg = sodiumV,
            createdAt = 0, updatedAt = 0,
        )
    }
    val rule = entry?.let { e -> runCatching { FoodRepository.check(e) }.exceptionOrNull()?.let { (it as? LedgerException)?.message ?: "数字不对" } }
    val ready = entry != null && rule == null

    GlassSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScrollWithIndicator(rememberScrollState()).padding(bottom = 16.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val side = Modifier.padding(horizontal = 24.dp)
            Row(side, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (existing == null) "记一笔饮食" else "编辑记录", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (labelled) "照包装上的营养成分表填，自动换算" else "估个大概的热量就行",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                if (existing != null) TextButton(onClick = { onDelete(existing) }) { Text("删除", color = cs.error) }
            }
            if (existing == null && recent.isNotEmpty()) {
                // Typing a name narrows them to the ones that match, so a long list never needs scrolling through.
                val q = name.trim()
                val matches = if (q.isEmpty()) recent else recent.filter { it.name.orEmpty().contains(q, ignoreCase = true) }
                if (matches.isNotEmpty()) {
                    val folded = q.isEmpty() && !recentOpen && matches.size > RECENT_FOLDED
                    Column(side, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (q.isEmpty()) "最近吃过 · 点一下填好" else "最近吃过里有 ${matches.size} 项「$q」",
                                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (q.isEmpty() && matches.size > RECENT_FOLDED) {
                                Text(
                                    if (recentOpen) "收起" else "全部 ${matches.size}",
                                    style = MaterialTheme.typography.labelMedium, color = cs.secondary,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { recentOpen = !recentOpen }.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        RecentGrid(if (folded) matches.take(RECENT_FOLDED) else matches) { fill(it) }
                    }
                }
            }
            Column(side, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(Energy.NAME_MAX) },
                    label = { Text("名称（可选）") }, placeholder = { Text("如 水煮蛋、燕麦奶") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = FieldShape, colors = glassFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                // A portion is usually guessed in kcal, labels give kJ: an empty energy field follows the mode.
                SegmentedControl(listOf(false, true), labelled, { if (it) "按包装换算" else "填热量" }, { labelled = it; if (energy.isBlank()) kj = it })
                if (labelled) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField(per, { per = it }, "每", unit.label, bad(per, perV), Modifier.weight(1f), integer = true, unitInLabel = false)
                        SegmentedControl(FoodUnit.labelUnits, unit, { it.label }, { unit = it }, Modifier.width(118.dp), style = SegmentStyle.SOFT)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val what = when {
                        labelled -> "能量"
                        halves == Energy.SERVING_PER -> "热量"
                        else -> "每份热量"
                    }
                    NumberField(energy, { energy = it }, what, if (kj) "kJ" else "kcal", bad(energy, typed), Modifier.weight(1f), last = !labelled)
                    SegmentedControl(listOf(true, false), kj, { if (it) "kJ" else "kcal" }, { kj = it }, Modifier.width(118.dp), style = SegmentStyle.SOFT)
                }
                if (labelled) {
                    NumberField(
                        amount, { amount = it }, "吃了多少", unit.label, bad(amount, amountV),
                        Modifier.fillMaxWidth().focusRequester(amountFocus), integer = true, last = true,
                    )
                } else {
                    ServingStepper(halves) { halves = it }
                }
                NutrientsToggle(open = nutrients, filled = listOf(protein, fat, carb, sodium).count { it.isNotBlank() }) { nutrients = !nutrients }
                AnimatedVisibility(nutrients, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField(protein, { protein = it }, "蛋白质", "g", bad(protein, proteinV), Modifier.weight(1f))
                            NumberField(fat, { fat = it }, "脂肪", "g", bad(fat, fatV), Modifier.weight(1f))
                            NumberField(carb, { carb = it }, "碳水", "g", bad(carb, carbV), Modifier.weight(1f))
                        }
                        NumberField(sodium, { sodium = it }, "钠", "mg", bad(sodium, sodiumV), Modifier.fillMaxWidth(), integer = true, last = true)
                    }
                }
                // Only once there is something to show: an empty sheet stays a short form.
                AnimatedVisibility(entry != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    entry?.let { Result(it, typed, atwater, kj, energyBlank = energy.isBlank(), rule = rule) }
                }
                SelectField(
                    label = "日期",
                    value = dayLabel(day).let { if (it.contains("月")) it else "$it · ${LocalDate.parse(day).monthValue}月${LocalDate.parse(day).dayOfMonth}日" },
                    onClick = { picking = true },
                    icon = { Icon(Icons.Default.CalendarMonth, null) },
                )
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = {
                        val e = entry ?: return@Button
                        onSave(FoodDraft(existing?.id, LocalDate.parse(day), e.name, e.per, e.amount, e.unit, e.energyCal, e.inKj, e.proteinMg, e.fatMg, e.carbMg, e.sodiumMg))
                    },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text(entry?.takeIf { ready }?.let { "保存 · ${Energy.kcal(Energy.energyOf(it))} kcal" } ?: "保存") }
            }
        }
    }
    if (picking) {
        DatePickDialog(
            initial = LocalDate.parse(day),
            maxDate = today,
            onDismiss = { picking = false },
            onPick = { day = it.toString(); picking = false },
        )
    }
}

/**
 * How many portions, on a well like the fields: − and + a whole portion at a time, and below one
 * a half (半碗饭).
 */
@Composable
private fun ServingStepper(halves: Int, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val one = Energy.SERVING_PER
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(FieldShape)
            .background(glassFieldColors().unfocusedContainerColor)
            .border(1.dp, cs.onSurface.copy(alpha = 0.12f), FieldShape)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("吃了几份", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        StepButton(Icons.Default.Remove, "少一份", enabled = halves > 1) { onChange(if (halves > one) halves - one else 1) }
        Text(
            Energy.servings(halves),
            style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(52.dp).semantics { contentDescription = "${Energy.servings(halves)} 份" },
        )
        StepButton(Icons.Default.Add, "多一份", enabled = halves < Energy.SERVINGS_MAX_HALVES) { onChange(if (halves < one) one else halves + one) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(cs.secondary.copy(alpha = if (enabled) 0.14f else 0.05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) cs.secondary else cs.outline.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
    }
}

/** The fold over the nutrients: a quiet line saying what is in it, the chevron turning. */
@Composable
private fun NutrientsToggle(open: Boolean, filled: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "nutrients")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("营养素", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
        Text(
            if (filled > 0) "已填 $filled 项" else "蛋白质 · 脂肪 · 碳水 · 钠，可选",
            style = MaterialTheme.typography.labelMedium, color = if (filled > 0) cs.secondary else cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        Icon(Icons.Default.ExpandMore, if (open) "收起营养素" else "展开营养素", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp).rotate(turn))
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    unit: String,
    error: Boolean,
    modifier: Modifier,
    integer: Boolean = false,
    last: Boolean = false,
    unitInLabel: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onChange(t.filter { it.isDigit() || (!integer && it == '.') }.take(9)) },
        // The unit goes in the label, which shows while the field is empty; a suffix would not.
        label = { Text(if (unitInLabel) "$label $unit" else label, maxLines = 1) },
        suffix = if (unitInLabel) null else { { Text(unit) } },
        singleLine = true,
        isError = error,
        textStyle = MaterialTheme.typography.bodyLarge.merge(NumberStyle),
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = if (last) ImeAction.Done else ImeAction.Next),
        modifier = modifier,
        shape = FieldShape,
        colors = glassFieldColors(),
    )
}

private const val RECENT_COLUMNS = 3
/** The recent foods shown before 全部: two rows. */
private const val RECENT_FOLDED = RECENT_COLUMNS * 2

/**
 * Things eaten before in rows of equal cells, however long their names: a name cut short rather
 * than a cell grown, so the columns line up like a table.
 */
@Composable
private fun RecentGrid(items: List<FoodEntry>, onPick: (FoodEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(RECENT_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { e -> RecentCell(e, Modifier.weight(1f)) { onPick(e) } }
                repeat(RECENT_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Something eaten before: its name over what it came to, on a tinted tile. */
@Composable
private fun RecentCell(e: FoodEntry, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cs.secondary.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(e.name.orEmpty(), style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(Energy.kcal(Energy.energyOf(e)) + " kcal", style = MaterialTheme.typography.labelSmall.merge(NumberStyle), color = cs.secondary, maxLines = 1)
    }
}

/**
 * What the fields come to: the total, how it was worked out (the kJ conversion, the scaling to
 * the amount eaten), the nutrients, and a word when the label's energy and its nutrients disagree.
 */
@Composable
private fun Result(entry: FoodEntry, typed: Long?, atwater: Long?, kj: Boolean, energyBlank: Boolean, rule: String?) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.secondary.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("约 ", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            Text(Energy.kcal(Energy.energyOf(entry)), style = MaterialTheme.typography.headlineSmall.merge(NumberStyle), fontWeight = FontWeight.SemiBold)
            Text(" kcal", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        val how = buildList {
            val serving = entry.unit == FoodUnit.SERVING
            val base = when {
                serving -> "每份 "
                entry.per != null && entry.unit != null -> "每 ${entry.per} ${entry.unit.label} "
                else -> "这一份 "
            }
            when {
                energyBlank -> add(base + "按营养素估算 ${Energy.energyInput(entry.energyCal, false)} kcal")
                kj && typed != null -> add(base + "${Energy.energyInput(typed, true)} kJ = ${Energy.energyInput(typed, false)} kcal")
                entry.per != null -> add(base + "${Energy.energyInput(entry.energyCal, false)} kcal")
            }
            if (serving) {
                add("吃了 ${Energy.servings(Energy.servingHalves(entry))} 份")
            } else if (entry.per != null && entry.amount != null && entry.unit != null) {
                add("吃了 ${entry.amount} ${entry.unit.label}，是它的 ${ratio(entry.amount, entry.per)} 倍")
            }
        }
        how.forEach { Text(it, style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = cs.onSurfaceVariant) }
        val nutrients = listOfNotNull(
            Energy.proteinOf(entry)?.let { "蛋白质 ${Energy.grams(it)} g" },
            Energy.fatOf(entry)?.let { "脂肪 ${Energy.grams(it)} g" },
            Energy.carbOf(entry)?.let { "碳水 ${Energy.grams(it)} g" },
            Energy.sodiumOf(entry)?.let { "钠 ${Energy.milligrams(it)} mg" },
        )
        if (nutrients.isNotEmpty()) {
            Text(nutrients.joinToString(" · "), style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = cs.onSurface, modifier = Modifier.padding(top = 2.dp))
        }
        if (typed != null && atwater != null && Energy.disagrees(typed, atwater)) {
            Text(
                "按营养素推算约 ${Energy.energyInput(atwater, false)} kcal，和填的能量相差较大，看看单位（kJ / kcal）或数字",
                style = MaterialTheme.typography.labelMedium, color = cs.secondary, modifier = Modifier.padding(top = 2.dp),
            )
        }
        rule?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = cs.error) }
    }
}

private fun hasNutrients(e: FoodEntry) = e.proteinMg != null || e.fatMg != null || e.carbMg != null || e.sodiumMg != null

/** 500 / 100 -> "5", 250 / 100 -> "2.5", 30 / 45 -> "0.67". */
private fun ratio(amount: Int, per: Int): String =
    java.math.BigDecimal(amount).divide(java.math.BigDecimal(per), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
