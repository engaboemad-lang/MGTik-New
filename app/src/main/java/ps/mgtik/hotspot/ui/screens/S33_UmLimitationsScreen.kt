package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.data.userman.ComponentOrigin
import ps.mgtik.hotspot.data.userman.DriftState
import ps.mgtik.hotspot.data.userman.SharedEditChoice
import ps.mgtik.hotspot.data.userman.UmComponentType
import ps.mgtik.hotspot.data.userman.UmComponentsOverview
import ps.mgtik.hotspot.data.userman.UmLimitation
import ps.mgtik.hotspot.data.userman.UmLimitationSpec
import ps.mgtik.hotspot.data.userman.UmRelations
import ps.mgtik.hotspot.data.userman.UmLimitRules
import ps.mgtik.hotspot.ui.components.um.UmBurstFormSection
import ps.mgtik.hotspot.ui.components.um.UmBurstFormState
import ps.mgtik.hotspot.ui.components.um.UmDeleteDialog
import ps.mgtik.hotspot.ui.components.um.UmExternalChangeDialog
import ps.mgtik.hotspot.ui.components.um.UmImportDialog
import ps.mgtik.hotspot.ui.components.um.UmNameDialog
import ps.mgtik.hotspot.ui.components.um.UmNumberField
import ps.mgtik.hotspot.ui.components.um.UmSharedEditDialog
import ps.mgtik.hotspot.ui.components.um.UmSnapshotsDialog
import ps.mgtik.hotspot.ui.components.um.driftLabel
import ps.mgtik.hotspot.ui.components.um.originLabel
import ps.mgtik.hotspot.ui.components.um.umMessageText
import ps.mgtik.hotspot.ui.viewmodel.UmComponentsViewModel

/** إجراء تعديل معلّق (لإعادة تنفيذه بعد إقرار الكتابة فوق تعديل خارجي). */
private data class PendingEdit(val spec: UmLimitationSpec, val choice: SharedEditChoice, val target: String?)

private sealed interface LimDialog {
    data object Create : LimDialog
    data class Edit(val item: UmLimitation) : LimDialog
    data class Shared(val spec: UmLimitationSpec, val item: UmLimitation) : LimDialog
    data class Clone(val item: UmLimitation) : LimDialog
    data class Rename(val item: UmLimitation) : LimDialog
    data class Delete(val item: UmLimitation) : LimDialog
    data class Import(val item: UmLimitation) : LimDialog
}

/**
 * الشاشة 33: إدارة User Manager Limitations كمكوّن مستقل عن Profile والباقة. تعرض كل ما على الراوتر فعلاً
 * (بما فيه الخارجي)، وتدير العلاقات (Profiles/الباقات/المستخدمون) والتعديل المشترك والحذف الآمن والاستعادة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S33_UmLimitationsScreen(
    viewModel: UmComponentsViewModel,
    initialQuery: String,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf(initialQuery) }
    var originFilter by remember { mutableStateOf<ComponentOrigin?>(null) }
    var dialog by remember { mutableStateOf<LimDialog?>(null) }
    var pending by remember { mutableStateOf<PendingEdit?>(null) }
    val overview = state.overview

    val message = state.message
    val messageText = message?.let { ps.mgtik.hotspot.ui.components.um.umMessageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) { snackbar.showSnackbar(messageText); viewModel.consumeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.um_limitations_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        if (state.busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, null)
                    }
                    IconButton(onClick = { dialog = LimDialog.Create }, enabled = overview != null && !state.busy) { Icon(Icons.Default.Add, stringResource(R.string.um_btn_new)) }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.um_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = originFilter == null, onClick = { originFilter = null }, label = { Text(stringResource(R.string.um_filter_all)) })
                ComponentOrigin.entries.forEach { o ->
                    FilterChip(selected = originFilter == o, onClick = { originFilter = o }, label = { Text(originLabel(o)) })
                }
            }
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                overview == null -> Text(
                    stringResource(state.loadErrorRes ?: R.string.um_err_generic, state.loadErrorArg ?: ""),
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error
                )
                else -> {
                    val items = overview.graph.limitations
                        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                        .filter { originFilter == null || overview.origin(UmComponentType.LIMITATION, it.name) == originFilter }
                        .sortedBy { it.name.lowercase() }
                    if (items.isEmpty()) Text(stringResource(R.string.um_empty), Modifier.padding(16.dp))
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(items, key = { it.name }) { lim ->
                            LimitationCard(
                                lim, overview, highlighted = lim.name == state.lastResultName, busy = state.busy,
                                onOpenProfile = onOpenProfile,
                                onEdit = { dialog = LimDialog.Edit(lim) }, onClone = { dialog = LimDialog.Clone(lim) },
                                onRename = { dialog = LimDialog.Rename(lim) }, onDelete = { dialog = LimDialog.Delete(lim) },
                                onImport = { dialog = LimDialog.Import(lim) },
                                onHistory = { viewModel.loadSnapshots(UmComponentType.LIMITATION, lim.name) }
                            )
                        }
                    }
                }
            }
        }
    }

    fun submit(p: PendingEdit) { pending = p; viewModel.editLimitation(p.spec, p.choice, p.target, overwrite = false) }

    when (val d = dialog) {
        null -> Unit
        LimDialog.Create -> LimitationFormDialog(null, onDismiss = { dialog = null }) { spec -> dialog = null; viewModel.createLimitation(spec) }
        is LimDialog.Edit -> LimitationFormDialog(d.item, onDismiss = { dialog = null }) { spec ->
            dialog = null
            val usage = overview?.limitationUsage(d.item.name)
            if (usage != null && UmRelations.isSharedLimitation(usage)) dialog = LimDialog.Shared(spec, d.item)
            else submit(PendingEdit(spec, SharedEditChoice.DIRECT, null))
        }
        is LimDialog.Shared -> UmSharedEditDialog(
            usage = overview!!.limitationUsage(d.item.name), targetProfiles = overview.limitationUsage(d.item.name).profiles,
            onDismiss = { dialog = null },
            onDirect = { dialog = null; submit(PendingEdit(d.spec, SharedEditChoice.DIRECT, null)) },
            onCopy = { target -> dialog = null; submit(PendingEdit(d.spec, SharedEditChoice.INDEPENDENT_COPY, target)) }
        )
        is LimDialog.Clone -> UmNameDialog(stringResource(R.string.um_btn_clone), "${d.item.name}-copy", { dialog = null }) { n -> dialog = null; viewModel.cloneLimitation(d.item.name, n) }
        is LimDialog.Rename -> UmNameDialog(stringResource(R.string.um_btn_rename), d.item.name, { dialog = null }) { n -> dialog = null; viewModel.renameLimitation(d.item.name, n) }
        is LimDialog.Delete -> {
            val usage = overview!!.limitationUsage(d.item.name)
            UmDeleteDialog(d.item.name, usage, askDetach = usage.profiles.isNotEmpty(), onDismiss = { dialog = null }) { detach, ack ->
                dialog = null; viewModel.deleteLimitation(d.item.name, detach, ack)
            }
        }
        is LimDialog.Import -> UmImportDialog(d.item.name, { dialog = null }) { dialog = null; viewModel.importComponent(UmComponentType.LIMITATION, d.item.name) }
    }

    state.externalChangeFor?.let { name ->
        UmExternalChangeDialog(name, onDismiss = viewModel::dismissExternalChange) {
            viewModel.dismissExternalChange()
            pending?.let { viewModel.editLimitation(it.spec, it.choice, it.target, overwrite = true) }
        }
    }
    if (state.snapshotsFor?.first == UmComponentType.LIMITATION) {
        UmSnapshotsDialog(state.snapshots, onDismiss = viewModel::closeSnapshots) { viewModel.restore(it.id) }
    }
}

@Composable
private fun LimitationCard(
    lim: UmLimitation, overview: UmComponentsOverview, highlighted: Boolean, busy: Boolean,
    onOpenProfile: (String) -> Unit, onEdit: () -> Unit, onClone: () -> Unit, onRename: () -> Unit,
    onDelete: () -> Unit, onImport: () -> Unit, onHistory: () -> Unit
) {
    val usage = overview.limitationUsage(lim.name)
    val origin = overview.origin(UmComponentType.LIMITATION, lim.name)
    val drift = overview.drift(UmComponentType.LIMITATION, lim.name)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(lim.name, style = MaterialTheme.typography.titleMedium, fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold)
            Text("${stringResource(R.string.um_origin_label)}: ${originLabel(origin)}", style = MaterialTheme.typography.labelMedium)
            driftLabel(drift)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = if (drift == DriftState.EXTERNAL_CHANGE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // rx = رفع، tx = تنزيل (اتفاقية طبقة User Manager)
            Text("↓ ${lim.txKbps} Kbps   ↑ ${lim.rxKbps} Kbps", style = MaterialTheme.typography.bodyMedium)
            if (lim.burstTx.enabled || lim.burstRx.enabled) {
                Text(
                    "${stringResource(R.string.um_burst_label)}: ↓ ${lim.burstTx.rateKbps}/${lim.burstTx.thresholdKbps} Kbps ${lim.burstTx.timeSeconds}s   ↑ ${lim.burstRx.rateKbps}/${lim.burstRx.thresholdKbps} Kbps ${lim.burstRx.timeSeconds}s",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text("${stringResource(R.string.um_field_uptime_hours)}: ${lim.uptimeSeconds?.let { "${it / 3600.0}" } ?: stringResource(R.string.um_none)}", style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(R.string.um_field_transfer_mb)}: ${lim.transferBytes?.let { "${it / 1_048_576L}" } ?: stringResource(R.string.um_none)}", style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(R.string.um_field_download_limit_mb)}: ${lim.downloadBytes?.let { "${it / 1_048_576L}" } ?: stringResource(R.string.um_none)}", style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(R.string.um_field_upload_limit_mb)}: ${lim.uploadBytes?.let { "${it / 1_048_576L}" } ?: stringResource(R.string.um_none)}", style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.um_section_profiles), style = MaterialTheme.typography.labelLarge)
            if (usage.profiles.isEmpty()) Text(stringResource(R.string.um_none), style = MaterialTheme.typography.bodySmall)
            usage.profiles.forEach { p -> TextButton(onClick = { onOpenProfile(p) }, contentPadding = PaddingValues(0.dp)) { Text("$p  ›") } }
            Text("${stringResource(R.string.um_section_packages)}: ${usage.packages.joinToString().ifBlank { stringResource(R.string.um_none) }}", style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.um_count_users, usage.users.size), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Default.Edit, stringResource(R.string.um_btn_edit)) }
                IconButton(onClick = onClone, enabled = !busy) { Icon(Icons.Default.ContentCopy, stringResource(R.string.um_btn_clone)) }
                IconButton(onClick = onRename, enabled = !busy) { Icon(Icons.Default.DriveFileRenameOutline, stringResource(R.string.um_btn_rename)) }
                IconButton(onClick = onHistory) { Icon(Icons.Default.History, stringResource(R.string.um_btn_history)) }
                if (origin == ComponentOrigin.EXTERNAL) IconButton(onClick = onImport, enabled = !busy) { Icon(Icons.Default.FileDownload, stringResource(R.string.um_btn_import)) }
                IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Default.Delete, stringResource(R.string.um_btn_delete), tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/**
 * نموذج إنشاء/تعديل Limitation. عند التعديل لا يُرسل الوقت/النقل إلا إن غيّره المستخدم (الحقل يعرض أرقاماً مقرَّبة
 * بالساعة/الميغابايت فلا نعيد كتابة قيمة دقيقة بقيمة مقرّبة). فراغ الحقل = لا تغيير (وللإنشاء: بلا حد).
 */
@Composable
private fun LimitationFormDialog(existing: UmLimitation?, onDismiss: () -> Unit, onSave: (UmLimitationSpec) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var rx by remember { mutableStateOf(existing?.rxKbps?.toString() ?: "") }
    var tx by remember { mutableStateOf(existing?.txKbps?.toString() ?: "") }
    val initUptime = existing?.uptimeSeconds?.let { (it / 3600L).toString() } ?: ""
    val initTransfer = existing?.transferBytes?.let { (it / 1_048_576L).toString() } ?: ""
    var uptime by remember { mutableStateOf(initUptime) }
    var transfer by remember { mutableStateOf(initTransfer) }
    val initDown = existing?.downloadBytes?.let { (it / 1_048_576L).toString() } ?: ""
    val initUp = existing?.uploadBytes?.let { (it / 1_048_576L).toString() } ?: ""
    var download by remember { mutableStateOf(initDown) }
    var upload by remember { mutableStateOf(initUp) }
    val burst = remember { UmBurstFormState(existing) }
    val adv = remember { ps.mgtik.hotspot.ui.components.um.UmAdvancedFormState(existing) }
    fun mb(s: String) = (s.toLongOrNull() ?: 0L) * 1_048_576L
    fun buildSpec() = UmLimitationSpec(
        name = name,
        rxKbps = rx.toIntOrNull() ?: 0, txKbps = tx.toIntOrNull() ?: 0,
        uptimeSeconds = if (uptime == initUptime) null else (uptime.toLongOrNull() ?: 0L) * 3600L,
        transferBytes = if (transfer == initTransfer) null else mb(transfer),
        downloadBytes = if (download == initDown) null else mb(download),
        uploadBytes = if (upload == initUp) null else mb(upload),
        burstRx = burst.changedRx, burstTx = burst.changedTx,
        priority = adv.changedPriority, resetInterval = adv.changedReset
    )
    // التحقق المسبق بنفس قواعد طبقة الراوتر: لا يُرسل شيء غير صالح أو متعارض
    val issues = UmLimitRules.validate(existing, buildSpec())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.um_btn_new else R.string.um_btn_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.trim() }, label = { Text(stringResource(R.string.um_field_name)) }, singleLine = true, enabled = existing == null, modifier = Modifier.fillMaxWidth())
                UmNumberField(stringResource(R.string.um_field_rx), rx) { rx = it }
                UmNumberField(stringResource(R.string.um_field_tx), tx) { tx = it }
                UmNumberField(stringResource(R.string.um_field_uptime_hours), uptime) { uptime = it }
                UmNumberField(stringResource(R.string.um_field_transfer_mb), transfer) { transfer = it }
                UmNumberField(stringResource(R.string.um_field_download_limit_mb), download) { download = it }
                UmNumberField(stringResource(R.string.um_field_upload_limit_mb), upload) { upload = it }
                Text(stringResource(R.string.um_limits_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                UmBurstFormSection(burst, maxRxKbps = rx.toIntOrNull() ?: 0, maxTxKbps = tx.toIntOrNull() ?: 0, issues = issues)
                ps.mgtik.hotspot.ui.components.um.UmAdvancedFormSection(adv, issues.filter { it.field == "rate-limit-priority" || it.field == "reset-counters-interval" }, showSchedule = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && rx.isNotBlank() && tx.isNotBlank() && issues.isEmpty(),
                onClick = { onSave(buildSpec()) }
            ) { Text(stringResource(R.string.um_btn_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.um_btn_cancel)) } }
    )
}
