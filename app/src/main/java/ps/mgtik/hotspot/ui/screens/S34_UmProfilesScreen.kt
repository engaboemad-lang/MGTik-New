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
import androidx.compose.material3.Checkbox
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
import ps.mgtik.hotspot.data.userman.UmComponentType
import ps.mgtik.hotspot.data.userman.UmComponentsOverview
import ps.mgtik.hotspot.data.userman.UmProfile
import ps.mgtik.hotspot.data.userman.UmProfileSpec
import ps.mgtik.hotspot.data.userman.UmRelations
import ps.mgtik.hotspot.data.userman.UmStartMode
import ps.mgtik.hotspot.ui.components.um.UmDeleteDialog
import ps.mgtik.hotspot.ui.components.um.UmExternalChangeDialog
import ps.mgtik.hotspot.ui.components.um.UmImportDialog
import ps.mgtik.hotspot.ui.components.um.UmNameDialog
import ps.mgtik.hotspot.ui.components.um.UmNumberField
import ps.mgtik.hotspot.ui.components.um.UmRadioRow
import ps.mgtik.hotspot.ui.components.um.UmSharedEditDialog
import ps.mgtik.hotspot.ui.components.um.UmSnapshotsDialog
import ps.mgtik.hotspot.ui.components.um.UmSwitchRow
import ps.mgtik.hotspot.ui.components.um.driftLabel
import ps.mgtik.hotspot.ui.components.um.originLabel
import ps.mgtik.hotspot.ui.components.um.umMessageText
import ps.mgtik.hotspot.ui.viewmodel.UmComponentsViewModel

private data class PendingProfileEdit(val spec: UmProfileSpec, val limitations: List<String>?)

private sealed interface ProfDialog {
    data object Create : ProfDialog
    data class Edit(val item: UmProfile) : ProfDialog
    data class Shared(val spec: UmProfileSpec, val limitations: List<String>?, val item: UmProfile) : ProfDialog
    data class CopyWithEdits(val spec: UmProfileSpec, val item: UmProfile) : ProfDialog
    data class Clone(val item: UmProfile) : ProfDialog
    data class Rename(val item: UmProfile) : ProfDialog
    data class Delete(val item: UmProfile) : ProfDialog
    data class Import(val item: UmProfile) : ProfDialog
}

private enum class ValidityMode { KEEP, UNLIMITED, VALUE }
private enum class ValidityUnit(val seconds: Long) { HOURS(3_600L), DAYS(86_400L), MONTHS(2_592_000L) }

/**
 * الشاشة 34: إدارة User Manager Profiles كمكوّن مستقل. تعديل Profile هنا لا يتطلب فتح أي باقة،
 * وعلاقاته (Limitations/الباقات/المستخدمون) ظاهرة مع الانتقال إلى شاشة Limitations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S34_UmProfilesScreen(
    viewModel: UmComponentsViewModel,
    initialQuery: String,
    onBack: () -> Unit,
    onOpenLimitation: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf(initialQuery) }
    var originFilter by remember { mutableStateOf<ComponentOrigin?>(null) }
    var dialog by remember { mutableStateOf<ProfDialog?>(null) }
    var pending by remember { mutableStateOf<PendingProfileEdit?>(null) }
    val overview = state.overview

    val message = state.message
    val messageText = message?.let { umMessageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) { snackbar.showSnackbar(messageText); viewModel.consumeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.um_profiles_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        if (state.busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, null)
                    }
                    IconButton(onClick = { dialog = ProfDialog.Create }, enabled = overview != null && !state.busy) { Icon(Icons.Default.Add, stringResource(R.string.um_btn_new)) }
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
                    val items = overview.graph.profiles
                        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                        .filter { originFilter == null || overview.origin(UmComponentType.PROFILE, it.name) == originFilter }
                        .sortedBy { it.name.lowercase() }
                    if (items.isEmpty()) Text(stringResource(R.string.um_empty), Modifier.padding(16.dp))
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(items, key = { it.name }) { p ->
                            ProfileCard(
                                p, overview, highlighted = p.name == state.lastResultName, busy = state.busy,
                                onOpenLimitation = onOpenLimitation,
                                onEdit = { dialog = ProfDialog.Edit(p) }, onClone = { dialog = ProfDialog.Clone(p) },
                                onRename = { dialog = ProfDialog.Rename(p) }, onDelete = { dialog = ProfDialog.Delete(p) },
                                onImport = { dialog = ProfDialog.Import(p) },
                                onHistory = { viewModel.loadSnapshots(UmComponentType.PROFILE, p.name) }
                            )
                        }
                    }
                }
            }
        }
    }

    fun submit(e: PendingProfileEdit) { pending = e; viewModel.editProfile(e.spec, e.limitations, overwrite = false) }

    when (val d = dialog) {
        null -> Unit
        ProfDialog.Create -> ProfileFormDialog(null, overview!!, onDismiss = { dialog = null }) { spec, links -> dialog = null; viewModel.createProfile(spec, links ?: emptyList()) }
        is ProfDialog.Edit -> ProfileFormDialog(d.item, overview!!, onDismiss = { dialog = null }) { spec, links ->
            dialog = null
            if (UmRelations.isSharedProfile(overview.profileUsage(d.item.name))) dialog = ProfDialog.Shared(spec, links, d.item)
            else submit(PendingProfileEdit(spec, links))
        }
        is ProfDialog.Shared -> UmSharedEditDialog(
            usage = overview!!.profileUsage(d.item.name), targetProfiles = null, onDismiss = { dialog = null },
            onDirect = { dialog = null; submit(PendingProfileEdit(d.spec, d.limitations)) },
            onCopy = { dialog = ProfDialog.CopyWithEdits(d.spec, d.item) }
        )
        is ProfDialog.CopyWithEdits -> UmNameDialog(stringResource(R.string.um_shared_edit_copy), "${d.item.name}-copy", { dialog = null }) { n ->
            dialog = null; viewModel.cloneProfile(d.item.name, n, d.spec)
        }
        is ProfDialog.Clone -> UmNameDialog(stringResource(R.string.um_btn_clone), "${d.item.name}-copy", { dialog = null }) { n -> dialog = null; viewModel.cloneProfile(d.item.name, n) }
        is ProfDialog.Rename -> UmNameDialog(stringResource(R.string.um_btn_rename), d.item.name, { dialog = null }) { n -> dialog = null; viewModel.renameProfile(d.item.name, n, syncNameForUsers = false) }
        is ProfDialog.Delete -> UmDeleteDialog(d.item.name, overview!!.profileUsage(d.item.name), askDetach = false, onDismiss = { dialog = null }) { _, _ ->
            dialog = null; viewModel.deleteProfile(d.item.name)
        }
        is ProfDialog.Import -> UmImportDialog(d.item.name, { dialog = null }) { dialog = null; viewModel.importComponent(UmComponentType.PROFILE, d.item.name) }
    }

    state.externalChangeFor?.let { name ->
        UmExternalChangeDialog(name, onDismiss = viewModel::dismissExternalChange) {
            viewModel.dismissExternalChange()
            pending?.let { viewModel.editProfile(it.spec, it.limitations, overwrite = true) }
        }
    }
    if (state.snapshotsFor?.first == UmComponentType.PROFILE) {
        UmSnapshotsDialog(state.snapshots, onDismiss = viewModel::closeSnapshots) { viewModel.restore(it.id) }
    }
}

@Composable
private fun validityText(p: UmProfile): String = when {
    p.validityUnlimited -> stringResource(R.string.um_validity_unlimited)
    p.validitySeconds != null -> {
        val s = p.validitySeconds
        when {
            s % 86_400L == 0L -> "${s / 86_400L} ${stringResource(R.string.um_unit_days)}"
            else -> "${s / 3_600L} ${stringResource(R.string.um_unit_hours)}"
        }
    }
    else -> stringResource(R.string.um_none)
}

@Composable
private fun ProfileCard(
    p: UmProfile, overview: UmComponentsOverview, highlighted: Boolean, busy: Boolean,
    onOpenLimitation: (String) -> Unit, onEdit: () -> Unit, onClone: () -> Unit, onRename: () -> Unit,
    onDelete: () -> Unit, onImport: () -> Unit, onHistory: () -> Unit
) {
    val usage = overview.profileUsage(p.name)
    val origin = overview.origin(UmComponentType.PROFILE, p.name)
    val drift = overview.drift(UmComponentType.PROFILE, p.name)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold)
            if (p.nameForUsers.isNotBlank() && p.nameForUsers != p.name) Text(p.nameForUsers, style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(R.string.um_origin_label)}: ${originLabel(origin)}", style = MaterialTheme.typography.labelMedium)
            driftLabel(drift)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = if (drift == DriftState.EXTERNAL_CHANGE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${stringResource(R.string.um_field_validity_value)}: ${validityText(p)}", style = MaterialTheme.typography.bodyMedium)
            Text("${stringResource(R.string.um_field_shared)}: ${if (p.sharedUnlimited) stringResource(R.string.um_shared_unlimited) else p.sharedUsers.toString()}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${stringResource(R.string.um_start_policy)}: " + when (p.startMode) {
                    UmStartMode.FIRST_AUTH -> stringResource(R.string.um_start_first_auth)
                    UmStartMode.ON_ASSIGN -> stringResource(R.string.um_start_assigned)
                    null -> stringResource(R.string.um_none)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(stringResource(R.string.um_section_limitations), style = MaterialTheme.typography.labelLarge)
            if (p.limitations.isEmpty()) Text(stringResource(R.string.um_none), style = MaterialTheme.typography.bodySmall)
            p.limitations.forEach { l -> TextButton(onClick = { onOpenLimitation(l) }, contentPadding = PaddingValues(0.dp)) { Text("$l  ›") } }
            Text("${stringResource(R.string.um_section_packages)}: ${usage.packages.joinToString().ifBlank { stringResource(R.string.um_none) }}", style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.um_count_users, usage.users.size), style = MaterialTheme.typography.bodySmall)
            Row {
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
 * نموذج Profile. الصلاحية: «كما هي» لا تُرسل شيئاً، «غير محدودة» تُرسل 0s صراحةً، وقيمة تُرسل بالوحدة المختارة
 * (لا 0s افتراضي). عدد الأجهزة وسياسة البداية لا تُرسلان عند التعديل إلا إن تغيّرا. الروابط: null = دون تغيير.
 */
@Composable
private fun ProfileFormDialog(
    existing: UmProfile?, overview: UmComponentsOverview, onDismiss: () -> Unit,
    onSave: (UmProfileSpec, List<String>?) -> Unit
) {
    val isNew = existing == null
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var nameForUsers by remember { mutableStateOf(existing?.nameForUsers ?: "") }
    var vMode by remember { mutableStateOf(ValidityMode.KEEP) }
    var vValue by remember { mutableStateOf("") }
    var vUnit by remember { mutableStateOf(ValidityUnit.DAYS) }
    val initShared = existing?.takeIf { !it.sharedUnlimited }?.sharedUsers?.toString() ?: if (isNew) "1" else ""
    var shared by remember { mutableStateOf(initShared) }
    var sharedUnlimited by remember { mutableStateOf(existing?.sharedUnlimited ?: false) }
    var start by remember { mutableStateOf<UmStartMode?>(null) }
    val initLinks = existing?.limitations.orEmpty()
    var links by remember { mutableStateOf(initLinks.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.um_btn_new else R.string.um_btn_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.trim() }, label = { Text(stringResource(R.string.um_field_name)) }, singleLine = true, enabled = isNew, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(nameForUsers, { nameForUsers = it }, label = { Text(stringResource(R.string.um_field_name_for_users)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.um_field_validity_value), style = MaterialTheme.typography.labelLarge)
                UmRadioRow(stringResource(R.string.um_validity_keep), vMode == ValidityMode.KEEP) { vMode = ValidityMode.KEEP }
                UmRadioRow(stringResource(R.string.um_validity_unlimited), vMode == ValidityMode.UNLIMITED) { vMode = ValidityMode.UNLIMITED }
                UmRadioRow(stringResource(R.string.um_field_validity_value), vMode == ValidityMode.VALUE) { vMode = ValidityMode.VALUE }
                if (vMode == ValidityMode.VALUE) {
                    UmNumberField(stringResource(R.string.um_field_validity_value), vValue) { vValue = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ValidityUnit.entries.forEach { u ->
                            FilterChip(selected = vUnit == u, onClick = { vUnit = u }, label = {
                                Text(stringResource(when (u) { ValidityUnit.HOURS -> R.string.um_unit_hours; ValidityUnit.DAYS -> R.string.um_unit_days; ValidityUnit.MONTHS -> R.string.um_unit_months }))
                            })
                        }
                    }
                }
                UmSwitchRow(stringResource(R.string.um_shared_unlimited), sharedUnlimited) { sharedUnlimited = it }
                if (!sharedUnlimited) UmNumberField(stringResource(R.string.um_field_shared), shared) { shared = it }
                Text(stringResource(R.string.um_start_policy), style = MaterialTheme.typography.labelLarge)
                UmRadioRow(stringResource(R.string.um_start_keep), start == null) { start = null }
                UmRadioRow(stringResource(R.string.um_start_first_auth), start == UmStartMode.FIRST_AUTH) { start = UmStartMode.FIRST_AUTH }
                UmRadioRow(stringResource(R.string.um_start_assigned), start == UmStartMode.ON_ASSIGN) { start = UmStartMode.ON_ASSIGN }
                Text(stringResource(R.string.um_section_limitations), style = MaterialTheme.typography.labelLarge)
                overview.graph.limitations.sortedBy { it.name.lowercase() }.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = l.name in links, onCheckedChange = { on -> links = if (on) links + l.name else links - l.name })
                        Text(l.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (vMode != ValidityMode.VALUE || (vValue.toLongOrNull() ?: 0L) > 0L) && (sharedUnlimited || shared.isNotBlank() || !isNew),
                onClick = {
                    val spec = UmProfileSpec(
                        name = name,
                        nameForUsers = nameForUsers.takeIf { it.isNotBlank() && it != (existing?.nameForUsers ?: "") },
                        validityUnlimited = vMode == ValidityMode.UNLIMITED,
                        validitySeconds = if (vMode == ValidityMode.VALUE) (vValue.toLongOrNull() ?: 0L) * vUnit.seconds else null,
                        sharedUnlimited = sharedUnlimited && existing?.sharedUnlimited != true,
                        sharedUsers = if (sharedUnlimited || shared == initShared) null else shared.toIntOrNull()?.coerceAtLeast(1),
                        startMode = start
                    )
                    // إنشاء: القائمة كما اختيرت. تعديل: لا تغيير إلا إن اختلفت الروابط
                    onSave(spec, if (isNew || links != initLinks.toSet()) links.toList() else null)
                }
            ) { Text(stringResource(R.string.um_btn_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.um_btn_cancel)) } }
    )
}
