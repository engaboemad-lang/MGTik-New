package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import ps.mgtik.hotspot.ui.viewmodel.ValidityUnit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.PricingRule
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.ProfileClassification
import ps.mgtik.hotspot.domain.model.ProfileStatus
import ps.mgtik.hotspot.domain.model.SaleContext
import ps.mgtik.hotspot.domain.model.SettingSource
import ps.mgtik.hotspot.domain.model.SystemClass
import ps.mgtik.hotspot.domain.usecase.ClassificationRules
import ps.mgtik.hotspot.domain.usecase.ProfileDetailRules
import ps.mgtik.hotspot.domain.usecase.SystemClassRules
import ps.mgtik.hotspot.domain.usecase.TransferUnit
import ps.mgtik.hotspot.domain.usecase.UptimeUnit
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import ps.mgtik.hotspot.ui.util.formatLimitBytes
import ps.mgtik.hotspot.ui.util.formatLimitDuration
import ps.mgtik.hotspot.ui.viewmodel.ProfileSection
import ps.mgtik.hotspot.ui.viewmodel.ProfilesUiState
import ps.mgtik.hotspot.ui.viewmodel.ProfilesViewModel
import java.math.BigDecimal

/**
 * S17b_ProfileDetailScreen - صفحة تفاصيل مستقلة للباقة (تُفتح بالضغط على بطاقتها في S17).
 *
 * ثلاثة أقسام عرضاً وتعديلاً، كلٌّ يُحفظ مستقلاً: البيانات الإدارية، الإعدادات التشغيلية، البيانات التجارية.
 * كل إجراءات البطاقة السابقة (تعديل، تغيير المرجع، تعطيل/أرشفة/استعادة، تصفير العدّاد، حذف) متاحة هنا بنفس الوظائف
 * (نوافذها هي [ProfileActionDialogs] المشتركة مع القائمة).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S17b_ProfileDetailScreen(
    profileId: String,
    viewModel: ProfilesViewModel,
    onBack: () -> Unit,
    onOpenUmProfile: (String) -> Unit = {},
    onOpenUmLimitation: (String) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val profile = state.profiles.firstOrNull { it.profile.id == profileId }?.profile
    val category = profile?.categoryId?.let { id -> state.categories.firstOrNull { it.id == id } }
    val classification = profile?.classificationKey?.let { k -> state.classifications.firstOrNull { it.key == k } }

    LaunchedEffect(state.successMessageResId) {
        state.successMessageResId?.let { snackbarHostState.showSnackbar(context.getString(it)); viewModel.clearMessages() }
    }
    LaunchedEffect(state.errorMessageResId) {
        state.errorMessageResId?.let { snackbarHostState.showSnackbar(context.getString(it)); viewModel.clearMessages() }
    }
    LaunchedEffect(profile?.categoryId) { viewModel.observeDetailCategory(profile?.categoryId) }
    LaunchedEffect(profileId, state.profiles.size) { viewModel.refreshUsage() }
    // السعر النهائي يُعاد حسابه عند تغيّر أي مدخل سعر (سعر الباقة أو أسعار فئتها)
    LaunchedEffect(profileId, profile?.priceMinor, category?.retailPriceMinor, category?.wholesalePriceMinor, category?.currency, state.detailRules) {
        if (profile != null) viewModel.loadDetailQuotes(profileId)
    }
    // أي أقسام تعديل مفتوحة لا تبقى عند مغادرة الصفحة
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewModel.closeSection() } }
    // حُذفت الباقة (من هنا): نعود إلى القائمة بدلاً من صفحة فارغة
    var existed by remember { mutableStateOf(false) }
    LaunchedEffect(profile != null) {
        if (profile != null) existed = true else if (existed) onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.profile_detail_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                profile == null && (state.isLoading || state.profiles.isEmpty()) ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                profile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.profile_detail_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> DetailContent(profile, category, classification, state, viewModel, onOpenUmProfile, onOpenUmLimitation)
            }
        }
    }

    if (profile != null) {
        ProfileActionDialogs(state = state, viewModel = viewModel)
        SectionDialogs(profile, category, classification, state, viewModel)
    }
}

// ============================================================================================ المحتوى

@Composable
private fun DetailContent(
    profile: Profile,
    category: ProfileCategory?,
    classification: ProfileClassification?,
    state: ProfilesUiState,
    vm: ProfilesViewModel,
    onOpenUmProfile: (String) -> Unit,
    onOpenUmLimitation: (String) -> Unit
) {
    val archived = profile.status == ProfileStatus.ARCHIVED
    val editable = !archived && !state.isSavingSection
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val title = (if (isRtl) profile.displayNameAr else profile.displayNameEn)
        .ifBlank { profile.displayNameAr.ifBlank { profile.displayNameEn } }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.ScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    profile.linkedProfileName,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusBadge(profile.status)
        }
        if (archived) {
            Text(stringResource(R.string.profile_detail_archived_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        BasicSection(profile, category, classification, state, editable) { vm.openSection(ProfileSection.ADMIN) }
        OperationalSection(profile, classification, state.sequences[profile.id] ?: 0L, editable) { vm.openSection(ProfileSection.OPERATIONAL) }
        CommercialSection(profile, category, state, editable) { vm.openSection(ProfileSection.COMMERCIAL) }
        SectionCard(R.string.usage_section_title, null, {}) {
            ps.mgtik.hotspot.ui.components.profile.UsageSummaryText(
                if (state.usageFailed) null else state.usage[profile.id], state.usageLoading, state.usageFailed
            )
            if (!state.usageFailed) state.usage[profile.id]?.let { u ->
                InfoRow(stringResource(R.string.usage_total_users), u.users.toString(), ltr = true)
            }
            Text(stringResource(R.string.usage_source_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (profile.linkedProfileName.isNotBlank() && profile.status != ProfileStatus.MISSING) {
            ps.mgtik.hotspot.ui.components.um.UmPackageComponentsSection(
                routerProfileName = profile.linkedProfileName,
                viewModel = androidx.hilt.navigation.compose.hiltViewModel<ps.mgtik.hotspot.ui.viewmodel.UmComponentsViewModel>(),
                onOpenUmProfile = onOpenUmProfile,
                onOpenUmLimitation = onOpenUmLimitation
            )
        }
        ActionsSection(profile, state.sequences[profile.id] ?: 0L, vm)
    }
}

@Composable
private fun SectionCard(
    titleRes: Int,
    editEnabled: Boolean?,
    onEdit: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (editEnabled != null) {
                    IconButton(onClick = onEdit, enabled = editEnabled) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.profile_detail_edit_section))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, ltr: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.let { if (ltr) it.copy(textDirection = TextDirection.Ltr) else it },
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.55f)
        )
    }
}

private fun dateText(raw: String): String? = raw.takeIf { it.isNotBlank() }?.replace('T', ' ')?.take(16)

@Composable
private fun BasicSection(
    profile: Profile,
    category: ProfileCategory?,
    classification: ProfileClassification?,
    state: ProfilesUiState,
    editable: Boolean,
    onEdit: () -> Unit
) {
    val unset = stringResource(R.string.profile_detail_unset)
    val sysClass = SystemClassRules.classOf(profile, state.categories)
    SectionCard(R.string.profile_detail_section_basic, editable, onEdit) {
        InfoRow(stringResource(R.string.profile_field_ar_name), profile.displayNameAr.ifBlank { unset })
        InfoRow(stringResource(R.string.profile_field_en_name), profile.displayNameEn.ifBlank { unset })
        InfoRow(stringResource(R.string.profile_field_router_name), profile.linkedProfileName, ltr = true)
        InfoRow(
            stringResource(R.string.profile_detail_type),
            when (sysClass) {
                SystemClass.NORMAL -> stringResource(R.string.sysclass_normal)
                SystemClass.SUBSCRIPTION -> stringResource(R.string.sysclass_subscription)
                null -> stringResource(R.string.sysclass_unclassified)
            }
        )
        InfoRow(stringResource(R.string.profile_field_classification), classification?.nameAr ?: unset)
        InfoRow(stringResource(R.string.profile_field_category), category?.nameAr ?: unset)
        InfoRow(stringResource(R.string.profile_detail_description), profile.description?.takeIf { it.isNotBlank() } ?: unset)
        InfoRow(stringResource(R.string.profile_detail_status), stringResource(statusRes(profile.status)))
        InfoRow(stringResource(R.string.profile_detail_created), dateText(profile.createdAt) ?: unset, ltr = true)
        InfoRow(stringResource(R.string.profile_detail_updated), dateText(profile.updatedAt) ?: unset, ltr = true)
    }
}

private fun statusRes(s: ProfileStatus): Int = when (s) {
    ProfileStatus.ACTIVE -> R.string.profile_detail_status_active
    ProfileStatus.ARCHIVED -> R.string.profile_status_archived
    ProfileStatus.MISSING -> R.string.profile_status_missing
    ProfileStatus.DISABLED -> R.string.profile_status_disabled
}

private fun kbpsText(kbps: Int): String = when {
    kbps <= 0 -> ""
    kbps % 1_000 == 0 -> "${kbps / 1_000}M"
    else -> "${kbps}k"
}

@Composable
private fun OperationalSection(
    profile: Profile,
    classification: ProfileClassification?,
    lastSequence: Long,
    editable: Boolean,
    onEdit: () -> Unit
) {
    val e = ClassificationRules.effective(profile, classification)
    fun src(s: OperationalSetting) = if (e.sources[s] == SettingSource.INHERITED) R.string.setting_source_inherited else R.string.setting_source_profile
    val unlimited = stringResource(R.string.profile_rate_unlimited)
    val pending = ClassificationRules.pendingChanges(profile, classification)
    SectionCard(R.string.profile_detail_section_operational, editable, onEdit) {
        InfoRow(
            stringResource(R.string.profile_detail_download),
            (kbpsText(e.txKbps).ifEmpty { unlimited }) + " · " + stringResource(src(OperationalSetting.SPEED)), ltr = true
        )
        InfoRow(
            stringResource(R.string.profile_detail_upload),
            (kbpsText(e.rxKbps).ifEmpty { unlimited }) + " · " + stringResource(src(OperationalSetting.SPEED)), ltr = true
        )
        InfoRow(
            stringResource(R.string.profile_detail_shared_users),
            (if (profile.sharedUnlimited) stringResource(R.string.profile_shared_users_unlimited) else "${e.sharedUsers}") +
                " · " + stringResource(src(OperationalSetting.SHARED_USERS))
        )
        InfoRow(
            stringResource(R.string.ud_limit_uptime),
            formatLimitDuration(e.limitUptimeSeconds) + " · " + stringResource(src(OperationalSetting.LIMIT_UPTIME))
        )
        InfoRow(
            stringResource(R.string.ud_limit_transfer),
            formatLimitBytes(e.limitTransferBytes) + " · " + stringResource(src(OperationalSetting.LIMIT_TRANSFER))
        )
        InfoRow(stringResource(R.string.profile_detail_router_profile), profile.linkedProfileName, ltr = true)
        if (profile.status == ProfileStatus.MISSING) {
            Text(stringResource(R.string.profile_detail_router_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // حقول محفوظة في الباقة ولا يطبّقها التطبيق على المستخدمين تلقائياً: تُعرض كما هي إن كانت محددة، دون ادّعاء تطبيقها
        if (profile.validityUnlimited) InfoRow(stringResource(R.string.profile_detail_validity_hours), stringResource(R.string.profile_validity_unlimited))
        else if (profile.validityHours > 0) InfoRow(stringResource(R.string.profile_detail_validity_hours), profile.validityHours.toString(), ltr = true)
        if (profile.validityUnlimited || profile.validityHours > 0) {
            InfoRow(
                stringResource(R.string.profile_detail_validity_start),
                stringResource(if (profile.validityStart == "assigned") R.string.profile_start_assigned else R.string.profile_start_first_auth)
            )
        }
        if (profile.quotaMb > 0) InfoRow(stringResource(R.string.profile_detail_quota_mb), profile.quotaMb.toString(), ltr = true)
        if (profile.quotaMb > 0) {
            Text(stringResource(R.string.profile_detail_legacy_fields_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InfoRow(stringResource(R.string.profile_detail_last_sequence), lastSequence.toString(), ltr = true)
        if (pending.isNotEmpty()) {
            Text(stringResource(R.string.profile_detail_pending_classification), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun CommercialSection(
    profile: Profile,
    category: ProfileCategory?,
    state: ProfilesUiState,
    editable: Boolean,
    onEdit: () -> Unit
) {
    val unset = stringResource(R.string.profile_detail_unset)
    val currency = category?.currency?.takeIf { it.isNotBlank() } ?: LocalCurrencySymbol.current
    fun money(minor: Long?) = minor?.let { "${formatMoney(it)} $currency" } ?: unset
    val retail = if (category != null) category.retailPriceMinor else profile.priceMinor.takeIf { it > 0 }
    val rules = state.detailRules.filter { it.active && it.ownerId.isNotBlank() && category != null && it.categoryId == category.id }
    SectionCard(R.string.profile_detail_section_commercial, editable, onEdit) {
        InfoRow(stringResource(R.string.profile_detail_retail), money(retail), ltr = true)
        InfoRow(
            stringResource(R.string.profile_detail_wholesale),
            if (category != null) money(category.wholesalePriceMinor) else unset, ltr = true
        )
        InfoRow(stringResource(R.string.profile_detail_currency), category?.currency?.takeIf { it.isNotBlank() } ?: currency)
        Text(stringResource(R.string.profile_detail_final_price), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        SaleContext.entries.forEach { ctx ->
            val q = state.detailQuotes[ctx]
            InfoRow(
                stringResource(if (ctx == SaleContext.WHOLESALE) R.string.profile_detail_ctx_wholesale else R.string.profile_detail_ctx_retail),
                if (q == null) stringResource(R.string.profile_detail_no_valid_price)
                else "${formatMoney(q.unitMinor)} ${q.currency} · " + stringResource(priceSourceRes(q.source)),
                ltr = true
            )
        }
        Text(stringResource(R.string.profile_detail_owner_rules), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (rules.isEmpty()) {
            Text(stringResource(R.string.profile_detail_owner_rules_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else rules.forEach { r -> RuleRow(r, state, currency) }
        Text(
            if (category != null) stringResource(R.string.profile_detail_price_scope_category, category.nameAr)
            else stringResource(R.string.profile_detail_price_scope_profile),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun priceSourceRes(s: ps.mgtik.hotspot.domain.usecase.PricingPolicy.PriceSource): Int = when (s) {
    ps.mgtik.hotspot.domain.usecase.PricingPolicy.PriceSource.OWNER_SPECIAL -> R.string.profile_detail_src_owner
    ps.mgtik.hotspot.domain.usecase.PricingPolicy.PriceSource.LEGACY_OWNER_SPECIAL -> R.string.profile_detail_src_legacy_owner
    ps.mgtik.hotspot.domain.usecase.PricingPolicy.PriceSource.WHOLESALE -> R.string.profile_detail_src_wholesale
    ps.mgtik.hotspot.domain.usecase.PricingPolicy.PriceSource.RETAIL -> R.string.profile_detail_src_retail
    ps.mgtik.hotspot.domain.usecase.PricingPolicy.PriceSource.LEGACY_PROFILE -> R.string.profile_detail_src_legacy_profile
}

@Composable
private fun RuleRow(rule: PricingRule, state: ProfilesUiState, currency: String) {
    val owner = state.owners.firstOrNull { it.ownerId == rule.ownerId }?.name ?: rule.ownerId
    val ctx = stringResource(if (rule.context == SaleContext.WHOLESALE) R.string.profile_detail_ctx_wholesale else R.string.profile_detail_ctx_retail)
    InfoRow("$owner · $ctx", "${formatMoney(rule.retailPriceMinor)} ${rule.currency.ifBlank { currency }}", ltr = true)
}

@Composable
private fun ActionsSection(profile: Profile, lastSequence: Long, vm: ProfilesViewModel) {
    val archived = profile.status == ProfileStatus.ARCHIVED
    SectionCard(R.string.profile_detail_section_actions, null, {}) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            if (!archived) {
                ActionButton(R.string.profile_menu_edit) { vm.openSection(ProfileSection.ADMIN) }
                ActionButton(R.string.profile_menu_change_reference) { vm.openReferenceChange(profile) }
            }
            if (profile.status == ProfileStatus.ARCHIVED || profile.status == ProfileStatus.DISABLED) {
                ActionButton(R.string.profile_menu_restore) { vm.setProfileStatus(profile, ProfileStatus.ACTIVE) }
            }
            if (profile.status == ProfileStatus.ACTIVE || profile.status == ProfileStatus.MISSING) {
                ActionButton(R.string.profile_menu_disable) { vm.setProfileStatus(profile, ProfileStatus.DISABLED) }
            }
            if (!archived) ActionButton(R.string.profile_menu_archive) { vm.setProfileStatus(profile, ProfileStatus.ARCHIVED) }
            ActionButton(R.string.profile_sequence_reset_btn, enabled = lastSequence > 0L) { vm.requestSequenceReset(profile) }
            ActionButton(R.string.profile_btn_delete, destructive = true) { vm.requestDelete(profile) }
        }
    }
}

@Composable
private fun ActionButton(labelRes: Int, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
    ) {
        Text(stringResource(labelRes), color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

// ============================================================================================ نوافذ التعديل

@Composable
private fun SectionDialogs(
    profile: Profile,
    category: ProfileCategory?,
    classification: ProfileClassification?,
    state: ProfilesUiState,
    vm: ProfilesViewModel
) {
    when (state.detailEditor) {
        ProfileSection.ADMIN -> AdminEditDialog(profile, state, vm)
        ProfileSection.OPERATIONAL -> {
            OperationalEditDialog(profile, classification, state, vm)
            state.pendingOperationalPlan?.let { OperationalImpactDialog(profile, it, state.isSavingSection, vm) }
        }
        ProfileSection.COMMERCIAL -> CommercialEditDialog(profile, category, state, vm)
        null -> Unit
    }
}

@Composable
private fun errorText(state: ProfilesUiState, field: String): String? = state.detailErrors[field]?.let { stringResource(it) }

@Composable
private fun AdminEditDialog(profile: Profile, state: ProfilesUiState, vm: ProfilesViewModel) {
    var nameAr by rememberSaveable(profile.id) { mutableStateOf(profile.displayNameAr) }
    var nameEn by rememberSaveable(profile.id) { mutableStateOf(profile.displayNameEn) }
    var desc by rememberSaveable(profile.id) { mutableStateOf(profile.description.orEmpty()) }
    var categoryId by rememberSaveable(profile.id) { mutableStateOf(profile.categoryId) }
    var classKey by rememberSaveable(profile.id) { mutableStateOf(profile.classificationKey) }
    val busy = state.isSavingSection
    val err = errorText(state, ProfileDetailRules.Field.NAME)
    AlertDialog(
        onDismissRequest = { vm.closeSection() },
        title = { Text(stringResource(R.string.profile_detail_edit_admin_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                OutlinedTextField(
                    value = nameAr, onValueChange = { nameAr = it }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.profile_field_ar_name)) },
                    isError = err != null, supportingText = err?.let { { Text(it) } },
                    shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nameEn, onValueChange = { nameEn = it }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.profile_field_en_name)) },
                    shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it }, enabled = !busy, minLines = 2,
                    label = { Text(stringResource(R.string.profile_detail_description)) },
                    shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.fillMaxWidth()
                )
                OptionalDropdownField(
                    label = stringResource(R.string.profile_field_category),
                    // لا نقل إلى فئة بتصنيف نظامي مختلف (يُتحقَّق منه أيضاً في المستودع)
                    options = state.categories.filter { c ->
                        val current = state.categories.firstOrNull { it.id == profile.categoryId }?.systemClass
                        (c.status == ps.mgtik.hotspot.domain.model.CategoryStatus.ACTIVE || c.id == profile.categoryId) &&
                            (current == null || c.systemClass == null || c.systemClass == current)
                    }.map { it.id to it.nameAr },
                    selectedKey = categoryId, enabled = !busy, onSelected = { categoryId = it }
                )
                OptionalDropdownField(
                    label = stringResource(R.string.profile_field_classification),
                    options = state.classifications
                        .filter { it.status == ps.mgtik.hotspot.domain.model.CategoryStatus.ACTIVE || it.key == profile.classificationKey }
                        .map { it.key to it.nameAr },
                    selectedKey = classKey, enabled = !busy, onSelected = { classKey = it }
                )
                Text(stringResource(R.string.profile_detail_admin_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = { vm.saveAdminSection(profile, ProfileDetailRules.AdminInput(nameAr, nameEn, desc, categoryId, classKey)) }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = { vm.closeSection() }, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/** المدة بثوانٍ ← (نص، وحدة) إن كانت تُمثَّل بعدد صحيح من الوحدات؛ null = لا تُمثَّل (تبقى كما هي عند الحفظ). */
private fun uptimeFields(seconds: Long?): Triple<String?, UptimeUnit, Boolean> = when {
    seconds == null -> Triple("", UptimeUnit.HOURS, true)
    seconds % UptimeUnit.DAYS.seconds == 0L -> Triple((seconds / UptimeUnit.DAYS.seconds).toString(), UptimeUnit.DAYS, true)
    seconds % UptimeUnit.HOURS.seconds == 0L -> Triple((seconds / UptimeUnit.HOURS.seconds).toString(), UptimeUnit.HOURS, true)
    seconds % UptimeUnit.MINUTES.seconds == 0L -> Triple((seconds / UptimeUnit.MINUTES.seconds).toString(), UptimeUnit.MINUTES, true)
    else -> Triple(null, UptimeUnit.MINUTES, false)
}

private fun transferFields(bytes: Long?): Triple<String?, TransferUnit, Boolean> = when {
    bytes == null -> Triple("", TransferUnit.GB, true)
    bytes % TransferUnit.GB.bytes == 0L -> Triple((bytes / TransferUnit.GB.bytes).toString(), TransferUnit.GB, true)
    bytes % TransferUnit.MB.bytes == 0L -> Triple((bytes / TransferUnit.MB.bytes).toString(), TransferUnit.MB, true)
    else -> Triple(null, TransferUnit.MB, false)
}

/** المدة المخزَّنة بالساعات ← نص ووحدة للتعديل (أكبر وحدة تقسمها تماماً؛ وإلا ساعات). 0 = غير محددة. */
private fun validityFields(hours: Int): Pair<String, ValidityUnit> = when {
    hours <= 0 -> "" to ValidityUnit.DAYS
    hours % ValidityUnit.MONTHS.hours == 0 -> (hours / ValidityUnit.MONTHS.hours).toString() to ValidityUnit.MONTHS
    hours % ValidityUnit.DAYS.hours == 0 -> (hours / ValidityUnit.DAYS.hours).toString() to ValidityUnit.DAYS
    else -> hours.toString() to ValidityUnit.HOURS
}

@Composable
private fun OperationalEditDialog(
    profile: Profile,
    classification: ProfileClassification?,
    state: ProfilesUiState,
    vm: ProfilesViewModel
) {
    val busy = state.isSavingSection
    val own = profile.ownSettings
    fun initiallyOwn(s: OperationalSetting) = own == null || s in own || classification == null || !classification.defines(s)
    fun canInherit(s: OperationalSetting) = classification != null && classification.defines(s)

    var ownSpeed by rememberSaveable(profile.id) { mutableStateOf(initiallyOwn(OperationalSetting.SPEED)) }
    var ownShared by rememberSaveable(profile.id) { mutableStateOf(initiallyOwn(OperationalSetting.SHARED_USERS)) }
    var ownUptime by rememberSaveable(profile.id) { mutableStateOf(initiallyOwn(OperationalSetting.LIMIT_UPTIME)) }
    var ownTransfer by rememberSaveable(profile.id) { mutableStateOf(initiallyOwn(OperationalSetting.LIMIT_TRANSFER)) }

    // القيم المخزَّنة في الباقة هي المطبَّقة فعلياً؛ تُعرض كأساس للتعديل
    var download by rememberSaveable(profile.id) { mutableStateOf(kbpsText(profile.rateLimitTxKbps)) }
    var upload by rememberSaveable(profile.id) { mutableStateOf(kbpsText(profile.rateLimitRxKbps)) }
    var shared by rememberSaveable(profile.id) { mutableStateOf(profile.sharedUsers.toString()) }
    val up0 = remember(profile.id) { uptimeFields(profile.limitUptimeSeconds) }
    val tr0 = remember(profile.id) { transferFields(profile.limitTransferBytes) }
    var uptime by rememberSaveable(profile.id) { mutableStateOf(up0.first) }
    var uptimeUnit by rememberSaveable(profile.id) { mutableStateOf(up0.second) }
    var transfer by rememberSaveable(profile.id) { mutableStateOf(tr0.first) }
    var transferUnit by rememberSaveable(profile.id) { mutableStateOf(tr0.second) }
    // غير المحدود والصلاحية وسياسة بدايتها: تُعدَّل هنا بعد الإنشاء كما تُنشأ (تُدفع إلى بروفايل User Manager)
    var sharedUnl by rememberSaveable(profile.id) { mutableStateOf(profile.sharedUnlimited) }
    var validityUnl by rememberSaveable(profile.id) { mutableStateOf(profile.validityUnlimited) }
    val v0 = remember(profile.id) { validityFields(profile.validityHours) }
    var validityText by rememberSaveable(profile.id) { mutableStateOf(v0.first) }
    var validityUnitName by rememberSaveable(profile.id) { mutableStateOf(v0.second.name) }
    var startAssigned by rememberSaveable(profile.id) { mutableStateOf(profile.validityStart == "assigned") }

    @Composable
    fun OwnSwitch(setting: OperationalSetting, value: Boolean, inheritedText: String, onChange: (Boolean) -> Unit) {
        if (!canInherit(setting)) return
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (value) stringResource(R.string.profile_detail_own_setting) else stringResource(R.string.profile_detail_inherited_fmt, inheritedText),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
            )
            Switch(checked = value, onCheckedChange = onChange, enabled = !busy)
        }
    }

    val unlimited = stringResource(R.string.limit_unlimited)
    val c = classification
    AlertDialog(
        onDismissRequest = { vm.closeSection() },
        title = { Text(stringResource(R.string.profile_detail_edit_operational_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                // السرعة
                OwnSwitch(OperationalSetting.SPEED, ownSpeed, "${kbpsText(c?.downloadKbps ?: 0).ifEmpty { unlimited }} / ${kbpsText(c?.uploadKbps ?: 0).ifEmpty { unlimited }}") { ownSpeed = it }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    OutlinedTextField(
                        value = download, onValueChange = { download = it }, singleLine = true, enabled = !busy && ownSpeed,
                        label = { Text(stringResource(R.string.profile_detail_download)) }, placeholder = { Text("5M") },
                        isError = state.detailErrors.containsKey(ProfileDetailRules.Field.SPEED),
                        shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = upload, onValueChange = { upload = it }, singleLine = true, enabled = !busy && ownSpeed,
                        label = { Text(stringResource(R.string.profile_detail_upload)) }, placeholder = { Text("2M") },
                        isError = state.detailErrors.containsKey(ProfileDetailRules.Field.SPEED),
                        shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.weight(1f)
                    )
                }
                errorText(state, ProfileDetailRules.Field.SPEED)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                // عدد الجلسات
                OwnSwitch(OperationalSetting.SHARED_USERS, ownShared, (c?.sharedUsers ?: 1).toString()) { ownShared = it }
                OutlinedTextField(
                    value = shared, onValueChange = { shared = it.filter(Char::isDigit) }, singleLine = true, enabled = !busy && ownShared && !sharedUnl,
                    label = { Text(stringResource(R.string.profile_detail_shared_users)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = state.detailErrors.containsKey(ProfileDetailRules.Field.SHARED_USERS),
                    supportingText = errorText(state, ProfileDetailRules.Field.SHARED_USERS)?.let { { Text(it) } },
                    shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = sharedUnl, onClick = { sharedUnl = !sharedUnl }, enabled = !busy,
                    label = { Text(stringResource(R.string.profile_shared_users_unlimited)) }
                )
                HorizontalDivider()
                // Limit Uptime
                OwnSwitch(OperationalSetting.LIMIT_UPTIME, ownUptime, formatLimitDuration(c?.limitUptimeSeconds)) { ownUptime = it }
                if (uptime == null) {
                    Text(stringResource(R.string.profile_detail_value_kept_fmt, formatLimitDuration(profile.limitUptimeSeconds)), style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(
                        value = uptime.orEmpty(), onValueChange = { uptime = it.filter(Char::isDigit) }, singleLine = true, enabled = !busy && ownUptime,
                        label = { Text(stringResource(R.string.ud_limit_uptime)) },
                        placeholder = { Text(stringResource(R.string.limit_unlimited)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.detailErrors.containsKey(ProfileDetailRules.Field.UPTIME),
                        supportingText = errorText(state, ProfileDetailRules.Field.UPTIME)?.let { { Text(it) } },
                        shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                        UptimeUnit.entries.forEach { u ->
                            FilterChip(
                                selected = uptimeUnit == u, onClick = { uptimeUnit = u }, enabled = !busy && ownUptime,
                                label = { Text(stringResource(when (u) {
                                    UptimeUnit.MINUTES -> R.string.profile_limit_unit_minutes
                                    UptimeUnit.HOURS -> R.string.profile_limit_unit_hours
                                    UptimeUnit.DAYS -> R.string.profile_limit_unit_days
                                })) }
                            )
                        }
                    }
                }
                HorizontalDivider()
                // Limit Transfer
                OwnSwitch(OperationalSetting.LIMIT_TRANSFER, ownTransfer, formatLimitBytes(c?.limitTransferBytes)) { ownTransfer = it }
                if (transfer == null) {
                    Text(stringResource(R.string.profile_detail_value_kept_fmt, formatLimitBytes(profile.limitTransferBytes)), style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(
                        value = transfer.orEmpty(), onValueChange = { transfer = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true,
                        enabled = !busy && ownTransfer,
                        label = { Text(stringResource(R.string.ud_limit_transfer)) },
                        placeholder = { Text(stringResource(R.string.limit_unlimited)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = state.detailErrors.containsKey(ProfileDetailRules.Field.TRANSFER),
                        supportingText = errorText(state, ProfileDetailRules.Field.TRANSFER)?.let { { Text(it) } },
                        shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                        TransferUnit.entries.forEach { u ->
                            FilterChip(
                                selected = transferUnit == u, onClick = { transferUnit = u }, enabled = !busy && ownTransfer,
                                label = { Text(stringResource(if (u == TransferUnit.MB) R.string.profile_limit_unit_mb else R.string.profile_limit_unit_gb)) }
                            )
                        }
                    }
                }
                HorizontalDivider()
                // الصلاحية وسياسة بدايتها
                OutlinedTextField(
                    value = validityText, onValueChange = { if (it.all(Char::isDigit)) validityText = it.take(5) }, singleLine = true,
                    enabled = !busy && !validityUnl,
                    label = { Text(stringResource(R.string.profile_validity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = state.detailErrors.containsKey(ProfileDetailRules.Field.VALIDITY),
                    supportingText = errorText(state, ProfileDetailRules.Field.VALIDITY)?.let { { Text(it) } },
                    shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                    ValidityUnit.entries.forEach { u ->
                        FilterChip(
                            selected = validityUnitName == u.name, onClick = { validityUnitName = u.name }, enabled = !busy && !validityUnl,
                            label = { Text(stringResource(when (u) {
                                ValidityUnit.HOURS -> R.string.profile_limit_unit_hours
                                ValidityUnit.DAYS -> R.string.profile_limit_unit_days
                                ValidityUnit.MONTHS -> R.string.profile_validity_unit_months
                            })) }
                        )
                    }
                    FilterChip(
                        selected = validityUnl, onClick = { validityUnl = !validityUnl }, enabled = !busy,
                        label = { Text(stringResource(R.string.profile_validity_unlimited)) }
                    )
                }
                Text(stringResource(R.string.profile_detail_validity_start), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                    FilterChip(selected = !startAssigned, onClick = { startAssigned = false }, enabled = !busy,
                        label = { Text(stringResource(R.string.profile_start_first_auth)) })
                    FilterChip(selected = startAssigned, onClick = { startAssigned = true }, enabled = !busy,
                        label = { Text(stringResource(R.string.profile_start_assigned)) })
                }
                Text(stringResource(R.string.profile_detail_operational_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    vm.reviewOperationalSection(
                        profile,
                        ProfileDetailRules.OperationalInput(
                            ownSpeed, download, upload, ownShared, shared,
                            ownUptime, uptime, uptimeUnit, ownTransfer, transfer, transferUnit,
                            sharedUnlimited = sharedUnl,
                            validityUnlimited = validityUnl,
                            validityText = validityText,
                            validityUnitHours = ValidityUnit.valueOf(validityUnitName).hours,
                            startAssigned = startAssigned
                        )
                    )
                }
            ) { Text(stringResource(R.string.profile_detail_review_changes)) }
        },
        dismissButton = { TextButton(onClick = { vm.closeSection() }, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/** خطوة التأكيد: ما الذي سيتأثر قبل أي كتابة. لا يُعدَّل أي مستخدم قائم تلقائياً من هنا؛ الراوتر يطبّق بروفايله على مستخدميه بطبيعته. */
@Composable
private fun OperationalImpactDialog(
    profile: Profile,
    plan: ProfileDetailRules.OperationalPlan,
    saving: Boolean,
    vm: ProfilesViewModel
) {
    AlertDialog(
        onDismissRequest = { if (!saving) vm.cancelOperationalConfirm() },
        title = { Text(stringResource(R.string.profile_detail_impact_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                if (plan.changed.isEmpty()) Text(stringResource(R.string.profile_detail_impact_source_only))
                if (plan.touchesRouterProfile) Text(stringResource(R.string.profile_detail_impact_router_fmt, profile.linkedProfileName))
                if (plan.touchesUserLimits) Text(stringResource(R.string.profile_detail_impact_limits))
                if (plan.validityChanged) Text(stringResource(R.string.profile_detail_impact_validity))
                Text(stringResource(R.string.profile_detail_impact_no_auto), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = vm::confirmOperationalSection, enabled = !saving) { Text(stringResource(R.string.profile_detail_impact_confirm)) }
        },
        dismissButton = { TextButton(onClick = vm::cancelOperationalConfirm, enabled = !saving) { Text(stringResource(R.string.common_cancel)) } }
    )
}

private fun minorToText(minor: Long?): String =
    minor?.let { BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString() }.orEmpty()

@Composable
private fun CommercialEditDialog(profile: Profile, category: ProfileCategory?, state: ProfilesUiState, vm: ProfilesViewModel) {
    val busy = state.isSavingSection
    val retail0 = if (category != null) category.retailPriceMinor else profile.priceMinor.takeIf { it > 0 }
    var retail by rememberSaveable(profile.id) { mutableStateOf(minorToText(retail0)) }
    var wholesale by rememberSaveable(profile.id) { mutableStateOf(minorToText(category?.wholesalePriceMinor)) }
    val currency = category?.currency?.takeIf { it.isNotBlank() } ?: LocalCurrencySymbol.current
    val siblings = if (category != null) state.profiles.count { it.profile.categoryId == category.id } else 0
    AlertDialog(
        onDismissRequest = { vm.closeSection() },
        title = { Text(stringResource(R.string.profile_detail_edit_commercial_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                OutlinedTextField(
                    value = retail, onValueChange = { retail = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.profile_detail_retail) + " ($currency)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = state.detailErrors.containsKey(ProfileDetailRules.Field.RETAIL),
                    supportingText = errorText(state, ProfileDetailRules.Field.RETAIL)?.let { { Text(it) } },
                    shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.fillMaxWidth()
                )
                if (category != null) {
                    OutlinedTextField(
                        value = wholesale, onValueChange = { wholesale = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.profile_detail_wholesale) + " ($currency)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = state.detailErrors.containsKey(ProfileDetailRules.Field.WHOLESALE),
                        supportingText = errorText(state, ProfileDetailRules.Field.WHOLESALE)?.let { { Text(it) } },
                        shape = RoundedCornerShape(Dimens.RadiusMD), modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.profile_detail_commercial_category_warning_fmt, category.nameAr, siblings),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary
                    )
                } else {
                    Text(stringResource(R.string.profile_detail_commercial_profile_note), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.profile_detail_commercial_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = { vm.saveCommercialSection(profile, category, ProfileDetailRules.CommercialInput(retail, wholesale)) }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = { vm.closeSection() }, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } }
    )
}
