package com.assetboard.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assetboard.data.CalendarChoice

/** Which of the phone's calendars receives the events; only calendars this app may write to. */
@Composable
fun CalendarPickerDialog(
    calendars: List<CalendarChoice>,
    selected: Long?,
    onPick: (CalendarChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("写入哪个日历") },
        text = {
            if (calendars.isEmpty()) {
                Text("手机里没有可以写入的日历。先在一个日历应用里建一个，或者检查系统日历权限。", color = cs.onSurfaceVariant)
            } else {
                Column(Modifier.heightIn(max = 360.dp).verticalScrollWithIndicator(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    calendars.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onPick(c) }.padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = c.id == selected, onClick = { onPick(c) })
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (c.account.isNotBlank()) {
                                    Text(c.account, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
