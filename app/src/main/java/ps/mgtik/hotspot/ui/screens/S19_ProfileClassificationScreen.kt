package ps.mgtik.hotspot.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.components.profile.UsageSummaryText
import ps.mgtik.hotspot.domain.usecase.ProfileUsageRules
import ps.mgtik.hotspot.domain.model.CategoryStatus
import ps.mgtik.hotspot.domain.model.ClassificationApplyPlan
import ps.mgtik.hotspot.domain.model.ClassificationApplyResult
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileClassification
import ps.mgtik.hotspot.domain.usecase.ProfileInputValidator
import ps.mgtik.hotspot.domain.usecase.TransferUnit
import ps.mgtik.hotspot.domain.usecase.UptimeUnit
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.viewmodel.ProfilesViewModel
import java.math.BigDecimal

/**
 * الشاشة 19: تصنيفات الباقات — التصنيف تشغيلي/فني فقط (سرعة، جلسات، مدة، حجم) ولا يحمل أي سعر.
 * قائمة (بحث + فلتر حالة) ← صفحة تفاصيل (الإعدادات، الباقات المرتبطة، الأرشفة، تطبيق على الموجودين بخطوة صريحة).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S19_ProfileClassificationScreen(
    viewModel: ProfilesViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ProfileClassification?>(null) }
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<CategoryStatus?>(null) }
    var pendingStatus by remember { mutableStateOf<Pair<ProfileClassification, CategoryStatus>?>(null) }

    val messageResId = state.successMessageResId ?: state.errorMessageResId
    val messageText = messageResId?.let { stringResource(it) }
    LaunchedEffect(messageResId) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.clearMessages()
        }
    }

    val selected = state.selectedClassificationKey?.let { k -> state.classifications.firstOrNull { it.key == k } }
    BackHandler(enabled = selected != null) { viewModel.closeClassificationDetail() }
    fun linked(c: ProfileClassification) = state.profiles.map { it.profile }.filter { it.classificationKey == c.key }
    // العدّ الفعلي من الخادم عند الدخول وعند تغيّر مجموعة الباقات
    LaunchedEffect(state.profiles.map { it.profile.id }) { viewModel.refreshUsage() }
    fun usageOf(c: ProfileClassification) = if (state.usageFailed) null else ProfileUsageRules.sum(linked(c), state.usage)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(selected?.let { it.nameAr.ifBlank { it.nameEn } } ?: stringResource(R.string.profile_class_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) viewModel.closeClassificationDetail() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (selected != null) {
                        IconButton(onClick = { editing = selected }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.class_action_edit))
                        }
                    } else {
                        IconButton(onClick = viewModel::openCreateClassificationDialog) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.profile_class_action_add))
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (selected != null) {
            ClassificationDetail(
                modifier = Modifier.padding(padding),
                c = selected,
                profiles = linked(selected),
                usage = usageOf(selected),
                state = state,
                onPreview = viewModel::previewApply,
                onStatus = { pendingStatus = selected to it }
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(Dimens.ScreenHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
            ) {
                Text(
                    text = stringResource(R.string.profile_class_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.class_search_hint)) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text(stringResource(R.string.filter_all)) })
                    FilterChip(selected = statusFilter == CategoryStatus.ACTIVE, onClick = { statusFilter = CategoryStatus.ACTIVE }, label = { Text(stringResource(R.string.status_active)) })
                    FilterChip(selected = statusFilter == CategoryStatus.ARCHIVED, onClick = { statusFilter = CategoryStatus.ARCHIVED }, label = { Text(stringResource(R.string.status_archived)) })
                }
                val shown = state.classifications.filter { c ->
                    (statusFilter == null || c.status == statusFilter) &&
                        (query.isBlank() || c.nameAr.contains(query, true) || c.nameEn.contains(query, true))
                }
                if (shown.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.profile_class_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                        items(shown, key = { it.key }) { c ->
                            ClassificationCard(c, linked(c).size, usageOf(c), state.usageLoading, state.usageFailed) { viewModel.openClassificationDetail(c.key) }
                        }
                    }
                }
            }
        }
    }

    if (state.showCreateClassificationDialog || editing != null) {
        ClassificationFormDialog(
            existing = editing,
            isSubmitting = state.isSubmitting,
            onDismiss = { editing = null; viewModel.closeCreateClassificationDialog() },
            onSubmit = { args ->
                viewModel.saveClassification(editing, args.nameAr, args.nameEn, args.description, args.download, args.upload,
                    args.shared, args.uptime, args.uptimeUnit, args.transfer, args.transferUnit)
            }
        )
    }
    // بعد نجاح الحفظ يُغلق نموذج التعديل
    LaunchedEffect(state.isSubmitting, state.showCreateClassificationDialog) {
        if (!state.isSubmitting && !state.showCreateClassificationDialog && state.successMessageResId != null) editing = null
    }

    pendingStatus?.let { (c, target) ->
        val count = linked(c).size
        AlertDialog(
            onDismissRequest = { pendingStatus = null },
            title = { Text(stringResource(if (target == CategoryStatus.ARCHIVED) R.string.class_archive_title else R.string.class_restore_title)) },
            text = {
                Text(
                    if (target == CategoryStatus.ARCHIVED) stringResource(R.string.class_archive_msg, count)
                    else stringResource(R.string.class_restore_msg)
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.setClassificationStatus(c, target); pendingStatus = null }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { pendingStatus = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    state.applyPlan?.let { plan ->
        ApplyPlanDialog(plan, state.isApplying, viewModel::confirmApply, viewModel::dismissApplyPlan)
    }
    state.applyResult?.let { ApplyResultDialog(it, viewModel::dismissApplyResult) }
}

@Composable
private fun settingsLines(c: ProfileClassification): List<Pair<String, String>> {
    val notDefined = stringResource(R.string.class_not_defined)
    fun kbps(v: Int?) = v?.let { ProfileInputValidator.formatRateLimit(it, it)?.substringBefore('/') } ?: notDefined
    return listOf(
        stringResource(R.string.class_field_download) to kbps(c.downloadKbps),
        stringResource(R.string.class_field_upload) to kbps(c.uploadKbps),
        stringResource(R.string.class_field_shared) to (c.sharedUsers?.toString() ?: notDefined),
        stringResource(R.string.class_field_uptime) to (c.limitUptimeSeconds?.let { s ->
            when {
                s % 86_400L == 0L -> "${s / 86_400L} " + stringResource(R.string.profile_limit_unit_days)
                s % 3_600L == 0L -> "${s / 3_600L} " + stringResource(R.string.profile_limit_unit_hours)
                else -> "${s / 60L} " + stringResource(R.string.profile_limit_unit_minutes)
            }
        } ?: notDefined),
        stringResource(R.string.class_field_transfer) to (c.limitTransferBytes?.let { b ->
            if (b % TransferUnit.GB.bytes == 0L) "${b / TransferUnit.GB.bytes} " + stringResource(R.string.profile_limit_unit_gb)
            else "${BigDecimal(b).divide(BigDecimal(TransferUnit.MB.bytes), 0, java.math.RoundingMode.HALF_UP)} " + stringResource(R.string.profile_limit_unit_mb)
        } ?: notDefined)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassificationCard(c: ProfileClassification, linkedCount: Int, usage: ps.mgtik.hotspot.domain.model.ProfileUsage?, loading: Boolean, failed: Boolean, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(c.nameAr.ifBlank { c.nameEn }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                AssistChip(onClick = onOpen, label = {
                    Text(stringResource(if (c.status == CategoryStatus.ACTIVE) R.string.status_active else R.string.status_archived))
                })
            }
            c.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.class_linked_count, linkedCount), style = MaterialTheme.typography.bodySmall)
            UsageSummaryText(usage, loading, failed)
        }
    }
}

@Composable
private fun ClassificationDetail(
    modifier: Modifier,
    c: ProfileClassification,
    profiles: List<Profile>,
    usage: ps.mgtik.hotspot.domain.model.ProfileUsage?,
    state: ps.mgtik.hotspot.ui.viewmodel.ProfilesUiState,
    onPreview: () -> Unit,
    onStatus: (CategoryStatus) -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.ScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
    ) {
        Text(
            stringResource(if (c.status == CategoryStatus.ACTIVE) R.string.status_active else R.string.status_archived),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary
        )
        c.description?.let { Text(it) }

        Text(stringResource(R.string.class_settings_title), fontWeight = FontWeight.Bold)
        settingsLines(c).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, fontWeight = FontWeight.Medium)
            }
        }
        Text(stringResource(R.string.class_blank_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        Text(stringResource(R.string.class_linked_profiles) + " (${profiles.size})", fontWeight = FontWeight.Bold)
        if (profiles.isEmpty()) Text(stringResource(R.string.class_no_linked), color = MaterialTheme.colorScheme.onSurfaceVariant)
        profiles.forEach { p ->
            val u = state.usage[p.id]
            Text("• " + p.displayNameAr.ifBlank { p.linkedProfileName } + (u?.let { " — " + stringResource(R.string.usage_profile_fmt, it.users) } ?: ""))
        }
        Text(stringResource(R.string.usage_section_title), fontWeight = FontWeight.Bold)
        UsageSummaryText(usage, state.usageLoading, state.usageFailed)

        HorizontalDivider()
        Text(stringResource(R.string.class_apply_title), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.class_apply_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onPreview, enabled = !state.isPreviewingApply && !state.isApplying && profiles.isNotEmpty()) {
            if (state.isPreviewingApply) CircularProgressIndicator(Modifier.size(Dimens.IconSizeSmall), strokeWidth = 2.dp)
            else Text(stringResource(R.string.class_apply_preview_btn))
        }

        HorizontalDivider()
        if (c.status == CategoryStatus.ACTIVE) {
            OutlinedButton(onClick = { onStatus(CategoryStatus.ARCHIVED) }) { Text(stringResource(R.string.class_archive_title)) }
        } else {
            OutlinedButton(onClick = { onStatus(CategoryStatus.ACTIVE) }) { Text(stringResource(R.string.class_restore_title)) }
        }
    }
}

@Composable
private fun ApplyPlanDialog(plan: ClassificationApplyPlan, isApplying: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val changing = plan.items.filter { it.changed.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.class_apply_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                if (changing.isEmpty()) {
                    Text(stringResource(R.string.class_apply_nothing))
                } else {
                    Text(stringResource(R.string.class_apply_profiles_fmt, changing.size))
                    Text(
                        plan.affectedUsers?.let { stringResource(R.string.class_apply_users_fmt, it) }
                            ?: stringResource(R.string.class_apply_users_unknown)
                    )
                    changing.forEach { Text("• " + it.profile.displayNameAr.ifBlank { it.profile.linkedProfileName }) }
                    if (changing.any { it.userLimitsManagedBySubscription }) {
                        Text(stringResource(R.string.class_apply_subscription_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (changing.isNotEmpty()) {
                Button(onClick = onConfirm, enabled = !isApplying) {
                    if (isApplying) CircularProgressIndicator(Modifier.size(Dimens.IconSizeSmall), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.class_apply_confirm_btn))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isApplying) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun ApplyResultDialog(r: ClassificationApplyResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (r.hasFailures) R.string.class_apply_result_partial else R.string.class_apply_result_ok)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                Text(stringResource(R.string.class_apply_result_fmt, r.profilesUpdated, r.usersUpdated))
                if (r.usersSkippedSubscription > 0) Text(stringResource(R.string.class_apply_result_skipped_fmt, r.usersSkippedSubscription))
                if (r.hasFailures) {
                    Text(stringResource(R.string.class_apply_failures), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    (r.profileFailures + r.userFailures).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pricing_close)) } }
    )
}

private data class ClassificationForm(
    val nameAr: String, val nameEn: String, val description: String, val download: String, val upload: String,
    val shared: String, val uptime: String, val uptimeUnit: UptimeUnit, val transfer: String, val transferUnit: TransferUnit
)

@Composable
private fun ClassificationFormDialog(
    existing: ProfileClassification?,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (ClassificationForm) -> Unit
) {
    fun kbpsText(v: Int?) = v?.let { ProfileInputValidator.formatRateLimit(it, it)?.substringBefore('/') } ?: ""
    var nameAr by remember { mutableStateOf(existing?.nameAr ?: "") }
    var nameEn by remember { mutableStateOf(existing?.nameEn ?: "") }
    var desc by remember { mutableStateOf(existing?.description ?: "") }
    var download by remember { mutableStateOf(kbpsText(existing?.downloadKbps)) }
    var upload by remember { mutableStateOf(kbpsText(existing?.uploadKbps)) }
    var shared by remember { mutableStateOf(existing?.sharedUsers?.toString() ?: "") }
    val upSecs = existing?.limitUptimeSeconds
    var uptimeUnit by remember { mutableStateOf(when { upSecs == null -> UptimeUnit.HOURS; upSecs % 86_400L == 0L -> UptimeUnit.DAYS; upSecs % 3_600L == 0L -> UptimeUnit.HOURS; else -> UptimeUnit.MINUTES }) }
    var uptime by remember { mutableStateOf(upSecs?.let { (it / uptimeUnit.seconds).toString() } ?: "") }
    val trBytes = existing?.limitTransferBytes
    var transferUnit by remember { mutableStateOf(if (trBytes != null && trBytes % TransferUnit.GB.bytes == 0L) TransferUnit.GB else if (trBytes != null) TransferUnit.MB else TransferUnit.GB) }
    var transfer by remember { mutableStateOf(trBytes?.let { (it / transferUnit.bytes).toString() } ?: "") }

    val ltr = LocalTextStyleLtr()
    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text(stringResource(if (existing == null) R.string.profile_class_action_add else R.string.class_action_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                OutlinedTextField(nameAr, { nameAr = it }, label = { Text(stringResource(R.string.class_field_name_ar)) }, singleLine = true, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(nameEn, { nameEn = it }, label = { Text(stringResource(R.string.class_field_name_en)) }, singleLine = true, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text(stringResource(R.string.class_field_desc)) }, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.class_blank_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(download, { download = it.filterNot(Char::isWhitespace) }, label = { Text(stringResource(R.string.class_field_download)) }, placeholder = { Text("5M") }, singleLine = true, textStyle = ltr, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(upload, { upload = it.filterNot(Char::isWhitespace) }, label = { Text(stringResource(R.string.class_field_upload)) }, placeholder = { Text("2M") }, singleLine = true, textStyle = ltr, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(shared, { v -> if (v.all(Char::isDigit)) shared = v }, label = { Text(stringResource(R.string.class_field_shared)) }, singleLine = true, textStyle = ltr, enabled = !isSubmitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(uptime, { v -> if (v.all(Char::isDigit)) uptime = v }, label = { Text(stringResource(R.string.class_field_uptime)) }, singleLine = true, textStyle = ltr, enabled = !isSubmitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    UptimeUnit.values().forEach { u ->
                        FilterChip(selected = uptimeUnit == u, onClick = { uptimeUnit = u }, enabled = !isSubmitting, label = {
                            Text(stringResource(when (u) {
                                UptimeUnit.MINUTES -> R.string.profile_limit_unit_minutes
                                UptimeUnit.HOURS -> R.string.profile_limit_unit_hours
                                UptimeUnit.DAYS -> R.string.profile_limit_unit_days
                            }))
                        })
                    }
                }
                OutlinedTextField(transfer, { v -> if (v.all(Char::isDigit)) transfer = v }, label = { Text(stringResource(R.string.class_field_transfer)) }, singleLine = true, textStyle = ltr, enabled = !isSubmitting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    TransferUnit.values().forEach { u ->
                        FilterChip(selected = transferUnit == u, onClick = { transferUnit = u }, enabled = !isSubmitting, label = {
                            Text(stringResource(if (u == TransferUnit.MB) R.string.profile_limit_unit_mb else R.string.profile_limit_unit_gb))
                        })
                    }
                }
                Text(stringResource(R.string.class_edit_no_auto_apply), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(ClassificationForm(nameAr, nameEn, desc, download, upload, shared, uptime, uptimeUnit, transfer, transferUnit)) },
                enabled = !isSubmitting
            ) {
                if (isSubmitting) CircularProgressIndicator(Modifier.size(Dimens.IconSizeSmall), strokeWidth = 2.dp)
                else Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun LocalTextStyleLtr() = LocalTextStyle.current.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
