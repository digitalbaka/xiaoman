package dev.xiaoman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.AppLink
import dev.xiaoman.app.R
import dev.xiaoman.app.defaultAlarmTime
import dev.xiaoman.app.nextAlarmMillis

/** Prefilled by `xiaoman://alarm/set`; confirming goes through the existing reminder scheduler. */
@Composable
internal fun AlarmSetupDialog(
    request: AppLink.AlarmSet,
    onDismiss: () -> Unit,
    onConfirm: (label: String, atMillis: Long) -> Unit,
) {
    var time by remember(request) { mutableStateOf(request.time ?: defaultAlarmTime()) }
    var label by remember(request) { mutableStateOf(request.label.orEmpty()) }
    val atMillis = remember(time) { nextAlarmMillis(time) }
    val defaultLabel = stringResource(R.string.alarm_dialog_default_label)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alarm_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text(stringResource(R.string.alarm_dialog_time)) },
                    singleLine = true,
                    isError = atMillis == null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.alarm_dialog_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                if (atMillis == null) {
                    Text(
                        stringResource(R.string.alarm_dialog_invalid_time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = atMillis != null,
                onClick = { atMillis?.let { onConfirm(label.trim().ifBlank { defaultLabel }, it) } },
            ) { Text(stringResource(R.string.alarm_dialog_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** `yunhu://post-detail`: the app has no post source, so it shows what the link parsed to. */
@Composable
internal fun PostDetailDialog(id: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.post_detail_title)) },
        text = { Text(stringResource(R.string.post_detail_message, id)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
