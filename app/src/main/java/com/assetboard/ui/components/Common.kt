package com.assetboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetboard.domain.Dates
import com.assetboard.domain.Money
import com.assetboard.ui.theme.NumberStyle
import java.time.LocalDate

/** White rounded card on the paper background. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    // Glass on a page with colour behind it, like the cards in the todo and notes tabs.
    if (LocalAmbient.current != null) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(modifier.fillMaxWidth().glass(shape, glass).clip(shape).then(click).padding(contentPadding), content = content)
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(click.padding(contentPadding), content = content)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun MoneyText(
    cents: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    signed: Boolean = false,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = Money.format(cents, signed),
        modifier = modifier,
        style = style.merge(NumberStyle),
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
    )
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** A tappable row inside a [SectionCard]. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (chevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
fun RowDivider(startIndent: Dp = 20.dp) {
    HorizontalDivider(Modifier.padding(start = startIndent), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Looks like an outlined text field, opens something on tap. */
@Composable
fun SelectField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
    icon: @Composable (() -> Unit)? = { Icon(Icons.Default.UnfoldMore, null) },
) {
    val cs = MaterialTheme.colorScheme
    val well = glassFieldColors()
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        shape = FieldShape,
        label = { Text(label) },
        trailingIcon = icon,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = well.copy(
            disabledTextColor = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.45f),
            disabledLabelColor = cs.onSurfaceVariant,
            disabledTrailingIconColor = cs.onSurfaceVariant,
            disabledSupportingTextColor = cs.onSurfaceVariant,
        ),
    )
}

@Composable
fun <T> ListPickerDialog(
    title: String,
    items: List<T>,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: (T) -> String? = { null },
    leading: (@Composable (T) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(items) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onPick(item) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        leading?.invoke(item)
                        Column(Modifier.weight(1f)) {
                            Text(label(item), style = MaterialTheme.typography.bodyLarge)
                            subtitle(item)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * Date field. Picking today yields "now"; any other day yields 23:59:59 of that day,
 * so back-filled records sit after that day's flows.
 */
@Composable
fun DateField(
    label: String,
    at: Long,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxDate: LocalDate = LocalDate.now(),
    minDate: LocalDate? = null,
    supportingText: String? = null,
    showTime: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SelectField(
        label = label,
        value = if (showTime) Dates.dateTime(at) else Dates.date(at),
        onClick = { open = true },
        modifier = modifier,
        enabled = enabled,
        supportingText = supportingText,
        icon = { Icon(Icons.Default.CalendarMonth, null) },
    )
    if (open) {
        DatePickDialog(
            initial = Dates.localDate(at),
            maxDate = maxDate,
            minDate = minDate,
            onDismiss = { open = false },
            onPick = { date -> open = false; onPick(Dates.pickedAt(date)) },
        )
    }
}

@Composable
fun DatePickDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
    maxDate: LocalDate? = LocalDate.now(),
    minDate: LocalDate? = null,
) {
    val selectable = remember(maxDate, minDate) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val d = Dates.pickerToDate(utcTimeMillis)
                return (maxDate == null || !d.isAfter(maxDate)) && (minDate == null || !d.isBefore(minDate))
            }
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = Dates.dateToPicker(initial),
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(Dates.pickerToDate(it)) } ?: onDismiss() }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ErrorDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("无法完成") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/** Label + value pair used in headers ("总资产 38,482.13"). */
@Composable
fun Stat(label: String, cents: Long, modifier: Modifier = Modifier, color: Color = Color.Unspecified, labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, signed: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        MoneyText(cents, style = MaterialTheme.typography.titleMedium, color = color, signed = signed)
    }
}
