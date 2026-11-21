package ps.mgtik.hotspot.ui.components.um

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.data.userman.ComponentOrigin
import ps.mgtik.hotspot.data.userman.DriftState
import ps.mgtik.hotspot.data.userman.UmSnapshot
import ps.mgtik.hotspot.data.userman.UmUsage2
import ps.mgtik.hotspot.ui.viewmodel.UmMessage
import java.text.DateFormat
import java.util.Date

@Composable
fun umMessageText(m: UmMessage): String =
    if (m.arg != null) stringResource(m.resId, m.arg) else stringResource(m.resId)

@Composable
fun originLabel(o: ComponentOrigin): String = stringResource(
    when (o) {
        ComponentOrigin.MGTIK -> R.string.um_origin_mgtik
        ComponentOrigin.EXTERNAL -> R.string.um_origin_external
        ComponentOrigin.IMPORTED -> R.string.um_origin_imported
    }
)

@Composable
fun driftLabel(d: DriftState): String? = when (d) {
    DriftState.EXTERNAL_CHANGE -> stringResource(R.string.um_drift_external_change)
    DriftState.IN_SYNC -> stringResource(R.string.um_drift_in_sync)
    DriftState.NEVER_RECORDED -> stringResource(R.string.um_drift_never)
    DriftState.MISSING_ON_ROUTER -> null
}

/** حقل رقمي صحيح غير سالب؛ النص الفارغ = null. */
@Composable
fun UmNumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(12)) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
fun UmSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun UmRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** إدخال اسم جديد (إعادة تسمية/نسخ). */
@Composable
fun UmNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.trim() }, label = { Text(stringResource(R.string.um_field_new_name)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.um_btn_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.um_btn_cancel)) } }
    )
}

@Composable
fun UmExternalChangeDialog(name: String, onDismiss: () -> Unit, onOverwrite: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.um_external_change_title)) },
        text = { Text(stringResource(R.string.um_external_change_body, name)) },
        confirmButton = { TextButton(onClick = onOverwrite) { Text(stringResource(R.string.um_overwrite)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.um_btn_cancel)) } }
    )
}

@Composable
fun UmImportDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = { Text(stringResource(R.string.um_import_note)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.um_btn_import)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.um_btn_cancel)) } }
    )
}

/** نسخ الاستعادة المحفوظة لمكوّن. */
@Composable
fun UmSnapshotsDialog(snapshots: List<UmSnapshot>, onDismiss: () -> Unit, onRestore: (UmSnapshot) -> Unit) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.um_btn_history)) },
        text = {
            if (snapshots.isEmpty()) Text(stringResource(R.string.um_history_empty))
            else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshots.forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fmt.format(Date(s.takenAtMillis)), style = MaterialTheme.typography.bodyMedium)
                            Text(s.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onRestore(s) }) { Text(stringResource(R.string.um_btn_restore)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.um_btn_cancel)) } }
    )
}

/**
 * اختيار تعديل مكوّن مشترك: مباشر (يؤثر على الكل) أو مستقل (نسخة). [needsCopyTarget] لـ Limitation: اختيار Profile
 * الذي ستُربط به النسخة من [targetProfiles] (Profiles التي تربطه).
 */
@Composable
fun UmSharedEditDialog(
    usage: UmUsage2, targetProfiles: List<String>?, onDismiss: () -> Unit,
    onDirect: () -> Unit, onCopy: (target: String?) -> Unit
) {
    var copy by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(targetProfiles?.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.um_shared_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.um_shared_edit_body, usage.profiles.joinToString().ifBlank { "-" }, usage.packages.joinToString().ifBlank { "-" }, usage.users.size))
                UmRadioRow(stringResource(R.string.um_shared_edit_direct), !copy) { copy = false }
                UmRadioRow(stringResource(R.string.um_shared_edit_copy), copy) { copy = true }
                if (copy && targetProfiles != null) {
                    Text(stringResource(R.string.um_copy_target), style = MaterialTheme.typography.labelMedium)
                    targetProfiles.forEach { p -> UmRadioRow(p, target == p) { target = p } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (copy) onCopy(target) else onDirect() }, enabled = !copy || targetProfiles == null || target != null) { Text(stringResource(R.string.um_btn_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.um_btn_cancel)) } }
    )
}

/** حذف: يعرض الأثر ويجمع قرارات الفصل والإقرار. الحجب (مستخدمون/باقات) تعرضه الشاشة قبل فتح الحوار. */
@Composable
fun UmDeleteDialog(
    name: String, usage: UmUsage2, askDetach: Boolean,
    onDismiss: () -> Unit, onConfirm: (detach: Boolean, ack: Boolean) -> Unit
) {
    var detach by remember { mutableStateOf(!askDetach) }
    var ack by remember { mutableStateOf(false) }
    val needsAck = usage.users.isNotEmpty() || usage.profiles.size > 1 || usage.packages.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.um_delete_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.um_delete_impact, usage.profiles.size, usage.packages.size, usage.users.size))
                if (usage.profiles.isNotEmpty() && askDetach) {
                    Text(usage.profiles.joinToString(), style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(detach, { detach = it }); Text(stringResource(R.string.um_delete_detach), style = MaterialTheme.typography.bodyMedium) }
                    if (needsAck) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ack, { ack = it }); Text(stringResource(R.string.um_delete_ack), style = MaterialTheme.typography.bodyMedium) }
                }
                Text(stringResource(R.string.um_delete_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(detach, ack) }, enabled = (!askDetach || usage.profiles.isEmpty() || (detach && (!needsAck || ack)))) {
                Text(stringResource(R.string.um_btn_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.um_btn_cancel)) } }
    )
}
