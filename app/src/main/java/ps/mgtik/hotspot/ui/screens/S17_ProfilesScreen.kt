package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.BorderStroke
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Class
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.ProfileClassification
import ps.mgtik.hotspot.domain.model.ProfileStatus
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.SettingSource
import ps.mgtik.hotspot.domain.usecase.CategoryRules
import ps.mgtik.hotspot.domain.usecase.ClassificationRules
import ps.mgtik.hotspot.domain.usecase.ProfileInputValidator
import ps.mgtik.hotspot.domain.usecase.TransferUnit
import ps.mgtik.hotspot.domain.usecase.UsageLimits
import ps.mgtik.hotspot.data.userman.UmLimitRules
import ps.mgtik.hotspot.data.userman.UmLimitation
import ps.mgtik.hotspot.domain.usecase.UptimeUnit
import ps.mgtik.hotspot.ui.util.formatLimitBytes
import ps.mgtik.hotspot.ui.util.formatLimitDuration
import androidx.compose.material3.FilterChip
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.viewmodel.ProfileField
import ps.mgtik.hotspot.ui.viewmodel.ProfileUiModel
import ps.mgtik.hotspot.ui.viewmodel.ProfilesViewModel
import ps.mgtik.hotspot.ui.viewmodel.ProfilesUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S17_ProfilesScreen - مركز إدارة الباقات (§8).
 * - الإنشاء والحذف تنفَّذان على الراوتر ثم السحابة (انظر ProfileRepositoryImpl)، والأخطاء تظهر للمستخدم.
 * - كل النصوص من strings.xml (ar/en)؛ الأرقام والسرعات تُعرض LTR داخل واجهة RTL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S17_ProfilesScreen(
    viewModel: ProfilesViewModel,
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenClassifications: () -> Unit,
    onOpenUmProfiles: () -> Unit,
    onOpenUmLimitations: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.successMessageResId) {
        state.successMessageResId?.let {
            snackbarHostState.showSnackbar(context.getString(it))
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(state.errorMessageResId) {
        state.errorMessageResId?.let {
            snackbarHostState.showSnackbar(context.getString(it))
            viewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.profile_title_speed_control),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.lastSyncTime?.let {
                            Text(
                                text = stringResource(
                                    R.string.profile_last_sync,
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenCategories) {
                        Icon(Icons.Default.Category, contentDescription = stringResource(R.string.profile_cat_title))
                    }
                    IconButton(onClick = onOpenClassifications) {
                        Icon(Icons.Default.Class, contentDescription = stringResource(R.string.profile_class_title))
                    }
                    IconButton(onClick = onOpenUmProfiles) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.um_pkg_icon_profiles))
                    }
                    IconButton(onClick = onOpenUmLimitations) {
                        Icon(Icons.Rounded.Speed, contentDescription = stringResource(R.string.um_pkg_icon_limitations))
                    }
                    IconButton(onClick = viewModel::syncWithRouter, enabled = !state.isSyncingRouter) {
                        if (state.isSyncingRouter) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Sync, contentDescription = stringResource(R.string.profile_btn_sync))
                        }
                    }
                    IconButton(onClick = viewModel::openCreateDialog) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.profile_btn_add))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading && state.profiles.isEmpty() ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                state.profiles.isEmpty() -> EmptyProfilesContent()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Dimens.ScreenHorizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)
                ) {
                    item { SummaryBanner(count = state.profiles.size, catCount = state.categories.size) }
                    items(state.profiles, key = { it.profile.id }) { uiModel ->
                        ProfileCard(
                            uiModel = uiModel,
                            category = state.categories.firstOrNull { it.id == uiModel.profile.categoryId },
                            classification = state.classifications.firstOrNull { it.key == uiModel.profile.classificationKey },
                            lastSequence = state.sequences[uiModel.profile.id] ?: 0L,
                            onDelete = viewModel::requestDelete,
                            onResetSequence = viewModel::requestSequenceReset,
                            onSetStatus = viewModel::setProfileStatus,
                            onChangeReference = viewModel::openReferenceChange,
                            onEdit = viewModel::openEdit,
                            onOpen = { onOpenProfile(it.id) }
                        )
                    }
                }
            }
        }
    }

    if (state.showCreateDialog) {
        CreateProfileDialog(
            categories = state.categories,
            classifications = state.classifications,
            validationErrors = state.validationErrors,
            isSubmitting = state.isSubmitting,
            linkableProfiles = state.linkableRouterProfiles,
            linkableLoading = state.routerProfilesLoading,
            linkableErrorResId = state.routerProfilesErrorResId,
            routerLimitationNames = state.routerLimitationNames,
            onReloadLinkable = viewModel::loadRouterProfiles,
            onFieldEdited = viewModel::clearFieldError,
            onDismiss = viewModel::closeCreateDialog,
            onSubmit = viewModel::createProfile
        )
    }

    ProfileActionDialogs(state = state, viewModel = viewModel)
}

/**
 * نوافذ إجراءات الباقة (تصفير العدّاد، التعديل، تغيير المرجع، الحذف): مصدر واحد تعرضه قائمة الباقات وصفحة تفاصيل الباقة
 * فلا يتكرر منطق الإجراءات ولا تختلف نتائجها بين الشاشتين.
 */
@Composable
internal fun ProfileActionDialogs(state: ps.mgtik.hotspot.ui.viewmodel.ProfilesUiState, viewModel: ProfilesViewModel) {
    state.profilePendingSequenceReset?.let { profile ->
        ResetSequenceDialog(
            profile = profile,
            lastSequence = state.sequences[profile.id] ?: 0L,
            isResetting = state.isResettingSequence,
            onConfirm = viewModel::confirmSequenceReset,
            onDismiss = viewModel::dismissSequenceReset
        )
    }

    state.editProfile?.let { p ->
        EditProfileDialog(
            profile = p,
            categories = state.categories,
            classifications = state.classifications,
            isSaving = state.isSavingEdit,
            onSave = viewModel::saveEdit,
            onDismiss = viewModel::closeEdit
        )
    }

    state.referenceProfile?.let { p ->
        ReferenceChangeDialog(
            profile = p,
            options = state.referenceOptions,
            usedNames = state.profiles.filter { it.profile.id != p.id }.map { it.profile.linkedProfileName },
            impact = state.referenceImpact,
            isChecking = state.isCheckingReference,
            isApplying = state.isApplyingReference,
            errorResId = state.referenceErrorResId,
            onInputChanged = viewModel::onReferenceInputChanged,
            onCheck = viewModel::previewReference,
            onConfirm = viewModel::confirmReferenceChange,
            onDismiss = viewModel::closeReferenceChange
        )
    }

    state.profilePendingDelete?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            isDeleting = state.isDeleting,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete
        )
    }
}

@Composable
private fun SummaryBanner(count: Int, catCount: Int) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusMD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Dimens.SpaceMD),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.IconSizeMedium))
            Text(
                text = stringResource(R.string.profile_summary_banner, count, catCount),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ProfileCard(
    uiModel: ProfileUiModel,
    category: ProfileCategory?,
    classification: ProfileClassification?,
    lastSequence: Long,
    onDelete: (Profile) -> Unit,
    onResetSequence: (Profile) -> Unit,
    onSetStatus: (Profile, ProfileStatus) -> Unit,
    onChangeReference: (Profile) -> Unit,
    onEdit: (Profile) -> Unit,
    onOpen: (Profile) -> Unit
) {
    val profile = uiModel.profile
    var menuOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val displayName = (if (isRtl) profile.displayNameAr else profile.displayNameEn)
        .ifBlank { profile.displayNameAr.ifBlank { profile.displayNameEn } }
    val ltr = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)
    // السرعة/الحدود المعروضة هي الفعلية (موروثة من التصنيف أو خاصة بالباقة) مع بيان المصدر
    val effective = ClassificationRules.effective(profile, classification)
    val rate = ProfileInputValidator.formatRateLimit(effective.rxKbps, effective.txKbps)
    val srcInherited = stringResource(R.string.setting_source_inherited)
    val srcProfile = stringResource(R.string.setting_source_profile)
    fun src(s: OperationalSetting) = if (effective.sources[s] == SettingSource.INHERITED) srcInherited else srcProfile
    val retail = category?.retailPriceMinor ?: profile.priceMinor.takeIf { (category == null || !CategoryRules.hasCommercialPrices(category)) && it > 0 }

    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.profile_detail_open)) { onOpen(profile) }
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = profile.linkedProfileName,
                        style = ltr,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(shape = RoundedCornerShape(Dimens.RadiusSM), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        text = retail?.let { "${formatMoney(it)} ${category?.currency ?: LocalCurrencySymbol.current}" }
                            ?: stringResource(R.string.profile_price_unset),
                        style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = Dimens.SpaceSM, vertical = Dimens.SpaceXS)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXXS)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                        Icon(Icons.Rounded.Speed, null, modifier = Modifier.size(Dimens.IconSizeSmall), tint = MaterialTheme.colorScheme.primary)
                        if (rate != null) {
                            Text(text = "$rate · ${src(OperationalSetting.SPEED)}", style = ltr, fontWeight = FontWeight.Medium)
                        } else {
                            Text(text = stringResource(R.string.profile_rate_unlimited), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                        Icon(Icons.Rounded.People, null, modifier = Modifier.size(Dimens.IconSizeSmall), tint = MaterialTheme.colorScheme.secondary)
                        Text(
                            text = (if (profile.sharedUnlimited) stringResource(R.string.profile_devices_unlimited)
                                    else stringResource(R.string.profile_devices_count, effective.sharedUsers)) + " · " + src(OperationalSetting.SHARED_USERS),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // العدّاد التسلسلي الدائم لأسماء المستخدمين (يملكه البروفايل): آخر رقم مستخدم + تصفير يدوي بعد تأكيد
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                        Text(
                            text = stringResource(R.string.profile_sequence_last_fmt, lastSequence),
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { onResetSequence(profile) }, enabled = lastSequence > 0L) {
                            Text(stringResource(R.string.profile_sequence_reset_btn), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    // قيود المدة/الحجم المطبَّقة على كل حساب جديد (تظهر فقط إن وُجد قيد)
                    if (profile.limitUptimeSeconds != null || profile.limitTransferBytes != null) {
                        Text(
                            text = stringResource(
                                R.string.profile_limits_summary_fmt,
                                formatLimitDuration(profile.limitUptimeSeconds),
                                formatLimitBytes(profile.limitTransferBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                    StatusBadge(status = profile.status)
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.profile_menu_actions))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (profile.status != ProfileStatus.ARCHIVED) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.profile_menu_edit)) },
                                    onClick = { menuOpen = false; onEdit(profile) }
                                )
                            }
                            if (profile.status != ProfileStatus.ARCHIVED) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.profile_menu_change_reference)) },
                                    onClick = { menuOpen = false; onChangeReference(profile) }
                                )
                            }
                            when (profile.status) {
                                ProfileStatus.ARCHIVED, ProfileStatus.DISABLED -> DropdownMenuItem(
                                    text = { Text(stringResource(R.string.profile_menu_restore)) },
                                    onClick = { menuOpen = false; onSetStatus(profile, ProfileStatus.ACTIVE) }
                                )
                                else -> Unit
                            }
                            if (profile.status == ProfileStatus.ACTIVE || profile.status == ProfileStatus.MISSING) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.profile_menu_disable)) },
                                    onClick = { menuOpen = false; onSetStatus(profile, ProfileStatus.DISABLED) }
                                )
                            }
                            if (profile.status != ProfileStatus.ARCHIVED) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.profile_menu_archive)) },
                                    onClick = { menuOpen = false; onSetStatus(profile, ProfileStatus.ARCHIVED) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profile_btn_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; onDelete(profile) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatusBadge(status: ProfileStatus) {
    val color = when (status) {
        ProfileStatus.ACTIVE -> LocalMgtikExtendedColors.current.success
        ProfileStatus.MISSING -> MaterialTheme.colorScheme.error
        ProfileStatus.ARCHIVED -> MaterialTheme.colorScheme.outline
        ProfileStatus.DISABLED -> MaterialTheme.colorScheme.tertiary
    }
    val text = stringResource(
        when (status) {
            ProfileStatus.ACTIVE -> R.string.profile_status_synced
            ProfileStatus.MISSING -> R.string.profile_status_missing
            ProfileStatus.ARCHIVED -> R.string.profile_status_archived
            ProfileStatus.DISABLED -> R.string.profile_status_disabled
        }
    )

    Surface(
        shape = RoundedCornerShape(Dimens.RadiusPill),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.SpaceSM, vertical = Dimens.SpaceXXS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)
        ) {
            Box(modifier = Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CreateProfileDialog(
    categories: List<ProfileCategory>,
    classifications: List<ProfileClassification>,
    validationErrors: Map<String, Int>,
    isSubmitting: Boolean,
    linkableProfiles: List<ps.mgtik.hotspot.domain.model.RouterProfileOption>,
    linkableLoading: Boolean,
    linkableErrorResId: Int?,
    routerLimitationNames: List<String>,
    onReloadLinkable: () -> Unit,
    onFieldEdited: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (
        routerName: String, displayNameAr: String, rateLimitText: String,
        categoryId: String?, classificationKey: String?,
        limitUptimeText: String, limitUptimeUnit: UptimeUnit, limitTransferText: String, limitTransferUnit: TransferUnit,
        linkExistingName: String?, ops: ps.mgtik.hotspot.ui.viewmodel.PackageOps,
        limitationNames: List<String>, limitationsReference: Boolean,
        limitExtras: ps.mgtik.hotspot.domain.model.PackageLimitExtras?
    ) -> Unit
) {
    // §Burst وحدّا التنزيل/الرفع المنفصلان (للـ Limitation الجديدة فقط)
    val burstForm = remember { ps.mgtik.hotspot.ui.components.um.UmBurstFormState() }
    val advForm = remember { ps.mgtik.hotspot.ui.components.um.UmAdvancedFormState() }
    var downloadMb by rememberSaveable { mutableStateOf("") }
    var uploadMb by rememberSaveable { mutableStateOf("") }
    // §7: Limitation جديدة باسم يدوي (MANAGE) أو مكوّنات موجودة/مشتركة (REFERENCE: تُربط ولا تُعدَّل)
    var limUseExisting by rememberSaveable { mutableStateOf(false) }
    var limNewName by rememberSaveable { mutableStateOf("") }
    var limPicked by rememberSaveable { mutableStateOf(setOf<String>()) }
    val limNameTaken = !limUseExisting && limNewName.isNotBlank() && routerLimitationNames.any { it.equals(limNewName.trim(), ignoreCase = true) }
    var validityText by rememberSaveable { mutableStateOf("") }
    var validityUnitName by rememberSaveable { mutableStateOf(ps.mgtik.hotspot.ui.viewmodel.ValidityUnit.DAYS.name) }
    var validityUnlimited by rememberSaveable { mutableStateOf(false) }
    var sharedText by rememberSaveable { mutableStateOf("") }
    var sharedUnlimited by rememberSaveable { mutableStateOf(false) }
    var startAssigned by rememberSaveable { mutableStateOf(false) }
    // وضع الإنشاء: إنشاء على الراوتر (الافتراضي) أو ربط بروفايل موجود عليه (محلي فقط)
    var linkMode by rememberSaveable { mutableStateOf(false) }
    var linkedName by rememberSaveable { mutableStateOf<String?>(null) }
    var limitUptime by rememberSaveable { mutableStateOf("") }
    var limitUptimeUnitName by rememberSaveable { mutableStateOf(UptimeUnit.HOURS.name) }
    var limitTransfer by rememberSaveable { mutableStateOf("") }
    var limitTransferUnitName by rememberSaveable { mutableStateOf(TransferUnit.GB.name) }
    val limitUptimeUnit = UptimeUnit.valueOf(limitUptimeUnitName)
    val limitTransferUnit = TransferUnit.valueOf(limitTransferUnitName)
    var rName by rememberSaveable { mutableStateOf("") }
    var arName by rememberSaveable { mutableStateOf("") }
    var rateLimit by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var classificationKey by rememberSaveable { mutableStateOf<String?>(null) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val focusManager = LocalFocusManager.current
    val ltrText = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
    val parsedRate = ProfileInputValidator.parseRateLimit(rateLimit)
    fun mbToBytes(s: String): Long? = s.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1_048_576L }
    val extras = advForm.toExtras(ps.mgtik.hotspot.domain.model.PackageLimitExtras(
        downloadBytes = mbToBytes(downloadMb), uploadBytes = mbToBytes(uploadMb),
        burstRxKbps = burstForm.burstRx.rateKbps, burstRxThresholdKbps = burstForm.burstRx.thresholdKbps, burstRxTimeSeconds = burstForm.burstRx.timeSeconds,
        burstTxKbps = burstForm.burstTx.rateKbps, burstTxThresholdKbps = burstForm.burstTx.thresholdKbps, burstTxTimeSeconds = burstForm.burstTx.timeSeconds
    ))
    val extrasUsed = !linkMode && !limUseExisting
    val transferBytes = (UsageLimits.parseTransfer(limitTransfer, limitTransferUnit) as? UsageLimits.Parsed.Value)?.amount
    // نفس قواعد طبقة الراوتر؛ السرعة القصوى المنفوذة هنا من حقل السرعة (القيمة الموروثة من تصنيف تُتحقق في الراوتر)
    val limitIssues = if (!extrasUsed) emptyList() else if (parsedRate == null) advForm.scheduleIssues() else UmLimitRules.validate(
        UmLimitation("new", parsedRate.rxKbps, parsedRate.txKbps, null, transferBytes,
            downloadBytes = extras.downloadBytes, uploadBytes = extras.uploadBytes, burstRx = burstForm.burstRx, burstTx = burstForm.burstTx)
    ) + UmLimitRules.validate(null, ps.mgtik.hotspot.data.userman.UmLimitationSpec(
        "new", rxKbps = parsedRate.rxKbps, txKbps = parsedRate.txKbps, transferBytes = transferBytes,
        downloadBytes = extras.downloadBytes, uploadBytes = extras.uploadBytes,
        priority = advForm.priority.takeIf { it != UmLimitRules.PRIORITY_DEFAULT },
        resetInterval = advForm.reset.takeIf { it != ps.mgtik.hotspot.data.userman.UmResetInterval.DISABLED },
        uptimeSeconds = (UsageLimits.parseUptime(limitUptime, limitUptimeUnit) as? UsageLimits.Parsed.Value)?.amount
    )).filter { it.code in setOf(UmLimitRules.PRIORITY_INVALID, UmLimitRules.PRIORITY_NEEDS_RATE, UmLimitRules.RESET_NEEDS_LIMIT) } + advForm.scheduleIssues()
    val moveNext = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_dialog_new_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    FilterChip(
                        selected = !linkMode,
                        enabled = !isSubmitting,
                        onClick = { linkMode = false; onFieldEdited(ProfileField.LINK_PROFILE) },
                        label = { Text(stringResource(R.string.profile_mode_create)) }
                    )
                    FilterChip(
                        selected = linkMode,
                        enabled = !isSubmitting,
                        onClick = { linkMode = true; onFieldEdited(ProfileField.LINK_PROFILE) },
                        label = { Text(stringResource(R.string.profile_mode_link)) }
                    )
                }
                if (linkMode) {
                    RouterProfileDropdown(
                        options = linkableProfiles,
                        selected = linkedName,
                        loading = linkableLoading,
                        enabled = !isSubmitting,
                        isError = validationErrors.containsKey(ProfileField.LINK_PROFILE),
                        onSelected = { linkedName = it; onFieldEdited(ProfileField.LINK_PROFILE) }
                    )
                    (validationErrors[ProfileField.LINK_PROFILE] ?: linkableErrorResId)?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!linkableLoading && linkableErrorResId == null && linkableProfiles.isEmpty()) {
                        Text(stringResource(R.string.profile_link_none_available), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onReloadLinkable, enabled = !isSubmitting && !linkableLoading) {
                        Text(stringResource(R.string.profile_link_reload))
                    }
                    Text(stringResource(R.string.profile_link_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // الإعدادات التي ستُحفظ هي إعدادات الراوتر الفعلية (للقراءة فقط): لا تُدخَل ولا تُعدَّل هنا
                    linkableProfiles.firstOrNull { it.name == linkedName }?.let { RouterSettingsSummary(it) }
                }
                OutlinedTextField(
                    value = arName,
                    onValueChange = { arName = it; onFieldEdited(ProfileField.DISPLAY_NAME_AR) },
                    label = { Text(stringResource(R.string.profile_field_ar_name)) },
                    isError = validationErrors.containsKey(ProfileField.DISPLAY_NAME_AR),
                    supportingText = { validationErrors[ProfileField.DISPLAY_NAME_AR]?.let { Text(stringResource(it)) } },
                    enabled = !isSubmitting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = moveNext,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                if (!linkMode) OutlinedTextField(
                    value = rName,
                    onValueChange = { rName = it.filterNot(Char::isWhitespace); onFieldEdited(ProfileField.ROUTER_NAME) },
                    label = { Text(stringResource(R.string.profile_field_router_name)) },
                    isError = validationErrors.containsKey(ProfileField.ROUTER_NAME),
                    supportingText = { validationErrors[ProfileField.ROUTER_NAME]?.let { Text(stringResource(it)) } },
                    enabled = !isSubmitting,
                    singleLine = true,
                    textStyle = ltrText,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = moveNext,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                if (!linkMode) OutlinedTextField(
                    value = rateLimit,
                    onValueChange = { rateLimit = it.filterNot(Char::isWhitespace); onFieldEdited(ProfileField.RATE_LIMIT) },
                    label = { Text(stringResource(R.string.profile_field_rate_limit)) },
                    isError = validationErrors.containsKey(ProfileField.RATE_LIMIT),
                    supportingText = { Text(stringResource(validationErrors[ProfileField.RATE_LIMIT] ?: R.string.profile_rate_hint)) },
                    enabled = !isSubmitting,
                    singleLine = true,
                    textStyle = ltrText,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                // حدود الاستخدام لكل فترة (اختيارية): تُحفظ في الباقة وتُدفع إلى الراوتر معها. في وضع الربط تُقرأ من الراوتر ولا تُدخَل
                if (!linkMode) {
                    OutlinedTextField(
                        value = limitUptime,
                        onValueChange = { new ->
                            if (new.all(Char::isDigit)) { limitUptime = new.take(10); onFieldEdited(ProfileField.LIMIT_UPTIME) }
                        },
                        label = { Text(stringResource(R.string.profile_limit_uptime_label)) },
                        isError = validationErrors.containsKey(ProfileField.LIMIT_UPTIME),
                        supportingText = { validationErrors[ProfileField.LIMIT_UPTIME]?.let { Text(stringResource(it)) } },
                        enabled = !isSubmitting,
                        singleLine = true,
                        textStyle = ltrText,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = moveNext,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                        UptimeUnit.values().forEach { unit ->
                            FilterChip(
                                selected = limitUptimeUnit == unit,
                                onClick = { limitUptimeUnitName = unit.name },
                                enabled = !isSubmitting,
                                label = {
                                    Text(
                                        stringResource(
                                            when (unit) {
                                                UptimeUnit.MINUTES -> R.string.profile_limit_unit_minutes
                                                UptimeUnit.HOURS -> R.string.profile_limit_unit_hours
                                                UptimeUnit.DAYS -> R.string.profile_limit_unit_days
                                            }
                                        )
                                    )
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = limitTransfer,
                        onValueChange = { new ->
                            if (new.all { it.isDigit() || it == '.' } && new.count { it == '.' } <= 1) {
                                limitTransfer = new.take(12); onFieldEdited(ProfileField.LIMIT_TRANSFER)
                            }
                        },
                        label = { Text(stringResource(R.string.profile_limit_transfer_label)) },
                        isError = validationErrors.containsKey(ProfileField.LIMIT_TRANSFER),
                        supportingText = {
                            Text(stringResource(validationErrors[ProfileField.LIMIT_TRANSFER] ?: R.string.profile_limit_hint))
                        },
                        enabled = !isSubmitting,
                        singleLine = true,
                        textStyle = ltrText,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        keyboardActions = moveNext,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                        TransferUnit.values().forEach { unit ->
                            FilterChip(
                                selected = limitTransferUnit == unit,
                                onClick = { limitTransferUnitName = unit.name },
                                enabled = !isSubmitting,
                                label = {
                                    Text(
                                        stringResource(
                                            if (unit == TransferUnit.MB) R.string.profile_limit_unit_mb else R.string.profile_limit_unit_gb
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
                // الإعدادات التشغيلية (تُنشأ على الراوتر فقط؛ في وضع الربط تُقرأ من الراوتر)
                if (!linkMode) {
                    OutlinedTextField(
                        value = validityText,
                        onValueChange = { new -> if (new.all(Char::isDigit)) { validityText = new.take(5); onFieldEdited(ProfileField.VALIDITY) } },
                        label = { Text(stringResource(R.string.profile_validity_label)) },
                        isError = validationErrors.containsKey(ProfileField.VALIDITY),
                        supportingText = { validationErrors[ProfileField.VALIDITY]?.let { Text(stringResource(it)) } },
                        enabled = !isSubmitting && !validityUnlimited,
                        singleLine = true,
                        textStyle = ltrText,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = moveNext,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                        ps.mgtik.hotspot.ui.viewmodel.ValidityUnit.values().forEach { unit ->
                            FilterChip(
                                selected = validityUnitName == unit.name,
                                onClick = { validityUnitName = unit.name },
                                enabled = !isSubmitting && !validityUnlimited,
                                label = {
                                    Text(stringResource(when (unit) {
                                        ps.mgtik.hotspot.ui.viewmodel.ValidityUnit.HOURS -> R.string.profile_limit_unit_hours
                                        ps.mgtik.hotspot.ui.viewmodel.ValidityUnit.DAYS -> R.string.profile_limit_unit_days
                                        ps.mgtik.hotspot.ui.viewmodel.ValidityUnit.MONTHS -> R.string.profile_validity_unit_months
                                    }))
                                }
                            )
                        }
                        FilterChip(
                            selected = validityUnlimited,
                            onClick = { validityUnlimited = !validityUnlimited; onFieldEdited(ProfileField.VALIDITY) },
                            enabled = !isSubmitting,
                            label = { Text(stringResource(R.string.profile_validity_unlimited)) }
                        )
                    }
                    OutlinedTextField(
                        value = sharedText,
                        onValueChange = { new -> if (new.all(Char::isDigit)) { sharedText = new.take(4); onFieldEdited(ProfileField.SHARED_USERS) } },
                        label = { Text(stringResource(R.string.profile_shared_users_label)) },
                        isError = validationErrors.containsKey(ProfileField.SHARED_USERS),
                        supportingText = { validationErrors[ProfileField.SHARED_USERS]?.let { Text(stringResource(it)) } },
                        enabled = !isSubmitting && !sharedUnlimited,
                        singleLine = true,
                        textStyle = ltrText,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = moveNext,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    )
                    FilterChip(
                        selected = sharedUnlimited,
                        onClick = { sharedUnlimited = !sharedUnlimited; onFieldEdited(ProfileField.SHARED_USERS) },
                        enabled = !isSubmitting,
                        label = { Text(stringResource(R.string.profile_shared_users_unlimited)) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                        FilterChip(
                            selected = !startAssigned, onClick = { startAssigned = false }, enabled = !isSubmitting,
                            label = { Text(stringResource(R.string.profile_start_first_auth)) }
                        )
                        FilterChip(
                            selected = startAssigned, onClick = { startAssigned = true }, enabled = !isSubmitting,
                            label = { Text(stringResource(R.string.profile_start_assigned)) }
                        )
                    }
                }
                // الفئة إلزامية (الباقة ترث تصنيفها النظامي منها): تُعرض الفئات المصنّفة والنشطة فقط. التصنيف التسعيري اختياري
                if (categories.isNotEmpty()) {
                    OptionalDropdownField(
                        label = stringResource(R.string.profile_field_category),
                        options = categories
                            .filter { it.systemClass != null && it.status == ps.mgtik.hotspot.domain.model.CategoryStatus.ACTIVE }
                            .map { it.id to ((if (isRtl) it.nameAr else it.nameEn).ifBlank { it.nameAr } + " — " + stringResource(systemClassLabelRes(it.systemClass!!))) },
                        selectedKey = categoryId,
                        enabled = !isSubmitting,
                        onSelected = { categoryId = it }
                    )
                }
                if (!linkMode) {
                    Text(stringResource(R.string.um_pick_lim_title), style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = !limUseExisting, onClick = { limUseExisting = false }, enabled = !isSubmitting)
                        Text(stringResource(R.string.um_pick_lim_new), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = limUseExisting, onClick = { limUseExisting = true }, enabled = !isSubmitting)
                        Text(stringResource(R.string.um_pick_lim_existing), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!limUseExisting) {
                        OutlinedTextField(
                            value = limNewName,
                            onValueChange = { limNewName = it.filterNot(Char::isWhitespace) },
                            label = { Text(stringResource(R.string.um_pick_lim_new_name)) },
                            isError = limNameTaken,
                            supportingText = { if (limNameTaken) Text(stringResource(R.string.um_pick_lim_name_exists)) },
                            enabled = !isSubmitting, singleLine = true, textStyle = ltrText,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(Dimens.RadiusMD)
                        )
                    } else {
                        if (routerLimitationNames.isEmpty()) Text(stringResource(R.string.um_pick_lim_none), style = MaterialTheme.typography.bodySmall)
                        routerLimitationNames.forEach { n ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(
                                    checked = n in limPicked, enabled = !isSubmitting,
                                    onCheckedChange = { on -> limPicked = if (on) limPicked + n else limPicked - n }
                                )
                                Text(n, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (extrasUsed) {
                    OutlinedTextField(
                        value = downloadMb, onValueChange = { downloadMb = it.filter(Char::isDigit).take(9) },
                        label = { Text(stringResource(R.string.um_field_download_limit_mb)) },
                        enabled = !isSubmitting, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    )
                    OutlinedTextField(
                        value = uploadMb, onValueChange = { uploadMb = it.filter(Char::isDigit).take(9) },
                        label = { Text(stringResource(R.string.um_field_upload_limit_mb)) },
                        enabled = !isSubmitting, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    )
                    Text(stringResource(R.string.um_limits_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ps.mgtik.hotspot.ui.components.um.UmBurstFormSection(burstForm, parsedRate?.rxKbps ?: 0, parsedRate?.txKbps ?: 0, limitIssues.filter { it.code !in setOf(UmLimitRules.PRIORITY_INVALID, UmLimitRules.PRIORITY_NEEDS_RATE, UmLimitRules.RESET_NEEDS_LIMIT, UmLimitRules.SCHEDULE_TIME_INVALID, UmLimitRules.SCHEDULE_NO_DAYS) })
                    ps.mgtik.hotspot.ui.components.um.UmAdvancedFormSection(advForm, limitIssues.filter { it.code in setOf(UmLimitRules.PRIORITY_INVALID, UmLimitRules.PRIORITY_NEEDS_RATE, UmLimitRules.RESET_NEEDS_LIMIT, UmLimitRules.SCHEDULE_TIME_INVALID, UmLimitRules.SCHEDULE_NO_DAYS) }, !isSubmitting)
                }
                if (classifications.isNotEmpty()) {
                    OptionalDropdownField(
                        label = stringResource(R.string.profile_field_classification),
                        options = classifications.filter { it.status == ps.mgtik.hotspot.domain.model.CategoryStatus.ACTIVE }.map { it.key to it.nameAr },
                        selectedKey = classificationKey,
                        enabled = !isSubmitting,
                        onSelected = { classificationKey = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        rName, arName, rateLimit, categoryId, classificationKey,
                        limitUptime, limitUptimeUnit, limitTransfer, limitTransferUnit,
                        if (linkMode) (linkedName ?: "") else null,
                        ps.mgtik.hotspot.ui.viewmodel.PackageOps(
                            validityText, ps.mgtik.hotspot.ui.viewmodel.ValidityUnit.valueOf(validityUnitName), validityUnlimited,
                            sharedText, sharedUnlimited, startAssigned
                        ),
                        if (limUseExisting) limPicked.toList() else listOfNotNull(limNewName.trim().takeIf { it.isNotEmpty() }),
                        limUseExisting,
                        extras.takeIf { extrasUsed && (it.downloadBytes != null || it.uploadBytes != null || it.burstRxKbps > 0 || it.burstTxKbps > 0 ||
                            it.priority != null || it.resetInterval != null || it.scheduleFromSeconds != null) }
                    )
                },
                enabled = !isSubmitting && categoryId != null && !limNameTaken && limitIssues.isEmpty() && (!limUseExisting || limPicked.isNotEmpty()),
                shape = RoundedCornerShape(Dimens.RadiusMD)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconSizeSmall),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.profile_btn_submit_queue))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ResetSequenceDialog(
    profile: Profile,
    lastSequence: Long,
    isResetting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_sequence_reset_title)) },
        text = {
            Text(
                stringResource(
                    R.string.profile_sequence_reset_message,
                    profile.displayNameAr.ifBlank { profile.linkedProfileName },
                    lastSequence
                )
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isResetting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                if (isResetting) CircularProgressIndicator(modifier = Modifier.size(Dimens.IconSizeSmall), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                else Text(stringResource(R.string.profile_sequence_reset_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isResetting) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouterProfileDropdown(
    options: List<ps.mgtik.hotspot.domain.model.RouterProfileOption>,
    selected: String?,
    loading: Boolean,
    enabled: Boolean,
    isError: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            isError = isError,
            label = { Text(stringResource(R.string.profile_link_pick_label)) },
            trailingIcon = {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(Dimens.IconSizeSmall), strokeWidth = 2.dp)
                else ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled)
            },
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            singleLine = true,
            shape = RoundedCornerShape(Dimens.RadiusMD),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { o ->
                val rate = ProfileInputValidator.formatRateLimit(o.rateLimitRxKbps, o.rateLimitTxKbps)
                DropdownMenuItem(
                    text = { Text(if (rate != null) "${o.name}  •  $rate" else o.name, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)) },
                    onClick = { onSelected(o.name); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun DeleteProfileDialog(
    profile: Profile,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_delete_title)) },
        text = {
            Text(stringResource(R.string.profile_delete_message, profile.displayNameAr.ifBlank { profile.linkedProfileName }))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(Dimens.RadiusMD)
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.IconSizeSmall),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                } else {
                    Text(stringResource(R.string.action_delete))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun EmptyProfilesContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.Speed, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(Dimens.SpaceLG))
        Text(
            text = stringResource(R.string.profile_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** تعديل بيانات الباقة غير الحساسة: الاسم العربي والفئة والتصنيف. */
@Composable
private fun EditProfileDialog(
    profile: Profile,
    categories: List<ProfileCategory>,
    classifications: List<ps.mgtik.hotspot.domain.model.ProfileClassification>,
    isSaving: Boolean,
    onSave: (Profile, String, String?, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var arName by rememberSaveable(profile.id) { mutableStateOf(profile.displayNameAr) }
    var categoryId by rememberSaveable(profile.id) { mutableStateOf(profile.categoryId) }
    var classificationKey by rememberSaveable(profile.id) { mutableStateOf(profile.classificationKey) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.profile_edit_title), fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.SpaceMD)) {
                OutlinedTextField(
                    value = arName,
                    onValueChange = { arName = it },
                    label = { Text(stringResource(R.string.profile_field_ar_name)) },
                    singleLine = true,
                    enabled = !isSaving,
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    modifier = Modifier.fillMaxWidth()
                )
                OptionalDropdownField(
                    label = stringResource(R.string.profile_field_category),
                    // لا نقل إلى فئة بتصنيف نظامي مختلف عن تصنيف الفئة الحالية (يُتحقَّق منه أيضاً في المستودع)
                    options = categories
                        .filter { c ->
                            val current = categories.firstOrNull { it.id == profile.categoryId }?.systemClass
                            (c.status == ps.mgtik.hotspot.domain.model.CategoryStatus.ACTIVE || c.id == profile.categoryId) &&
                                (current == null || c.systemClass == null || c.systemClass == current)
                        }
                        .map { it.id to it.nameAr },
                    selectedKey = categoryId,
                    enabled = !isSaving,
                    onSelected = { categoryId = it }
                )
                OptionalDropdownField(
                    label = stringResource(R.string.profile_field_classification),
                    options = classifications
                        .filter { it.status == ps.mgtik.hotspot.domain.model.CategoryStatus.ACTIVE || it.key == profile.classificationKey }
                        .map { it.key to it.nameAr },
                    selectedKey = classificationKey,
                    enabled = !isSaving,
                    onSelected = { classificationKey = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(profile, arName, categoryId, classificationKey) }, enabled = !isSaving) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** قائمة منسدلة اختيارية: أول عنصر «بدون» (null) ثم الخيارات (مفتاح ← نص معروض). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OptionalDropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedKey: String?,
    enabled: Boolean,
    onSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val noneText = stringResource(R.string.profile_option_none)
    val selectedText = options.firstOrNull { it.first == selectedKey }?.second ?: noneText

    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            singleLine = true,
            shape = RoundedCornerShape(Dimens.RadiusMD),
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(noneText) },
                onClick = { onSelected(null); expanded = false }
            )
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelected(key); expanded = false }
                )
            }
        }
    }
}


/**
 * تغيير المرجع التقني (بروفايل MikroTik): اختيار/كتابة المرجع الجديد ← «فحص الأثر» (يتحقق من الراوتر فعلياً) ← اعتماد.
 * لا يُعتمد إلا أثر محسوب للاسم المكتوب حالياً. فتح النافذة وإغلاقها لا يغيّران شيئاً.
 */
@Composable
private fun ReferenceChangeDialog(
    profile: Profile,
    options: List<ps.mgtik.hotspot.domain.model.RouterProfileOption>,
    usedNames: List<String>,
    impact: ps.mgtik.hotspot.domain.model.ReferenceChangeImpact?,
    isChecking: Boolean,
    isApplying: Boolean,
    errorResId: Int?,
    onInputChanged: () -> Unit,
    onCheck: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var newName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val busy = isChecking || isApplying
    val suggestions = options.filter { o -> o.name != profile.linkedProfileName && usedNames.none { it.equals(o.name, true) } }
    val current = impact?.takeIf { it.newName == newName.trim() }
    val canConfirm = current != null && current.action != ps.mgtik.hotspot.domain.model.ReferenceChangeAction.BLOCKED && current.importProblem == null && !busy

    AlertDialog(
        onDismissRequest = { if (!isApplying) onDismiss() },
        title = { Text(stringResource(R.string.profile_ref_title), fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
            ) {
                Text(stringResource(R.string.profile_ref_current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(profile.linkedProfileName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr))
                Text(stringResource(R.string.profile_ref_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (suggestions.isNotEmpty()) {
                    Text(stringResource(R.string.profile_ref_existing_on_router), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                        suggestions.forEach { o ->
                            FilterChip(selected = newName.trim() == o.name, onClick = { newName = o.name; onInputChanged() }, enabled = !busy, label = { Text(o.name) })
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; onInputChanged() },
                    label = { Text(stringResource(R.string.profile_ref_new_label)) },
                    singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                Button(onClick = { onCheck(newName) }, enabled = newName.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                    if (isChecking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(stringResource(R.string.profile_ref_check))
                }
                errorResId?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                current?.let { im ->
                    androidx.compose.material3.Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    ) {
                        androidx.compose.foundation.layout.Column(Modifier.padding(Dimens.SpaceMD), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                            Text(stringResource(R.string.profile_ref_impact_title), fontWeight = FontWeight.Bold)
                            when (im.action) {
                                ps.mgtik.hotspot.domain.model.ReferenceChangeAction.BLOCKED -> Text(
                                    stringResource(when (im.block) {
                                        ps.mgtik.hotspot.domain.model.ReferenceChangeBlock.INVALID_NAME -> R.string.profile_ref_blocked_invalid
                                        ps.mgtik.hotspot.domain.model.ReferenceChangeBlock.SAME_NAME -> R.string.profile_ref_blocked_same
                                        ps.mgtik.hotspot.domain.model.ReferenceChangeBlock.USED_BY_OTHER_PROFILE -> R.string.profile_ref_blocked_used
                                        else -> R.string.profile_ref_blocked_not_on_router
                                    }),
                                    color = MaterialTheme.colorScheme.error
                                )
                                ps.mgtik.hotspot.domain.model.ReferenceChangeAction.RENAME_ROUTER_PROFILE ->
                                    Text(stringResource(R.string.profile_ref_impact_rename, im.oldName, im.newName))
                                ps.mgtik.hotspot.domain.model.ReferenceChangeAction.REPOINT_TO_EXISTING ->
                                    Text(stringResource(R.string.profile_ref_impact_repoint, im.oldName, im.newName))
                            }
                            if (im.action != ps.mgtik.hotspot.domain.model.ReferenceChangeAction.BLOCKED) {
                                Text(
                                    im.usersOnOldProfile?.let { stringResource(R.string.profile_ref_users, it) } ?: stringResource(R.string.profile_ref_users_unknown),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    im.batchesCount?.let { stringResource(R.string.profile_ref_batches, it) } ?: stringResource(R.string.profile_ref_batches_unknown),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (im.action == ps.mgtik.hotspot.domain.model.ReferenceChangeAction.REPOINT_TO_EXISTING) {
                                    im.targetSettings?.let { t ->
                                        if (im.importProblem != null) {
                                            Text(
                                                stringResource(
                                                    if (im.importProblem == ps.mgtik.hotspot.domain.usecase.RouterProfileImport.ERR_VALIDITY_NOT_REPRESENTABLE) R.string.profile_error_router_validity_unsupported
                                                    else R.string.profile_error_router_limit_unsupported
                                                ),
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                                            )
                                        } else if (im.settingsDiffer) {
                                            Text(
                                                stringResource(R.string.profile_ref_differs_fmt, im.differingSettings.map { stringResource(diffLabelRes(it)) }.joinToString("، ")),
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                        RouterSettingsSummary(t)
                                    }
                                }
                                Text(stringResource(R.string.profile_ref_no_retro), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = canConfirm) {
                if (isApplying) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.profile_ref_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isApplying) { Text(stringResource(R.string.pricing_close)) } }
    )
}


/** مفتاح الإعداد المختلف ← نصه (مفاتيح [ps.mgtik.hotspot.domain.usecase.RouterProfileImport]). */
private fun diffLabelRes(key: String): Int = when (key) {
    ps.mgtik.hotspot.domain.usecase.RouterProfileImport.DIFF_SPEED -> R.string.profile_diff_speed
    ps.mgtik.hotspot.domain.usecase.RouterProfileImport.DIFF_SHARED -> R.string.profile_diff_devices
    ps.mgtik.hotspot.domain.usecase.RouterProfileImport.DIFF_VALIDITY -> R.string.profile_diff_validity
    ps.mgtik.hotspot.domain.usecase.RouterProfileImport.DIFF_START -> R.string.profile_diff_start
    ps.mgtik.hotspot.domain.usecase.RouterProfileImport.DIFF_UPTIME -> R.string.profile_diff_uptime
    else -> R.string.profile_diff_transfer
}

/** مدة بالثواني بأكبر وحدة صحيحة (يوم/ساعة/دقيقة)؛ ما لا يقبل القسمة يُعرض بالدقائق مقرَّباً لأعلى للعرض فقط. */
@Composable
private fun durationText(seconds: Long): String = when {
    seconds % 86_400L == 0L -> "${seconds / 86_400L} ${stringResource(R.string.profile_limit_unit_days)}"
    seconds % 3_600L == 0L -> "${seconds / 3_600L} ${stringResource(R.string.profile_limit_unit_hours)}"
    else -> "${(seconds + 59L) / 60L} ${stringResource(R.string.profile_limit_unit_minutes)}"
}

@Composable
private fun bytesText(bytes: Long): String {
    val gb = 1_073_741_824L
    val mb = 1_048_576L
    return if (bytes >= gb) "${java.math.BigDecimal(bytes).divide(java.math.BigDecimal(gb), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()} ${stringResource(R.string.profile_limit_unit_gb)}"
    else "${java.math.BigDecimal(bytes).divide(java.math.BigDecimal(mb), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()} ${stringResource(R.string.profile_limit_unit_mb)}"
}

/** إعدادات بروفايل الراوتر الفعلية، للقراءة فقط (الربط وإعادة التوجيه): هي ما سيُحفظ في الباقة. */
@Composable
private fun RouterSettingsSummary(o: ps.mgtik.hotspot.domain.model.RouterProfileOption) {
    val none = stringResource(R.string.profile_link_imp_none)
    val unspecified = stringResource(R.string.profile_link_imp_unspecified)
    androidx.compose.material3.Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(Dimens.RadiusMD)
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(Dimens.SpaceMD), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
            Text(stringResource(R.string.profile_link_imported_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            val st = MaterialTheme.typography.bodySmall
            Text(stringResource(R.string.profile_link_imp_speed, ProfileInputValidator.formatRateLimit(o.rateLimitRxKbps, o.rateLimitTxKbps) ?: none), style = st)
            Text(stringResource(R.string.profile_link_imp_devices, if (o.sharedUnlimited) stringResource(R.string.profile_shared_users_unlimited) else o.sharedUsers.toString()), style = st)
            Text(
                stringResource(
                    R.string.profile_link_imp_validity,
                    if (o.validityUnlimited) stringResource(R.string.profile_validity_unlimited) else o.validitySeconds?.let { durationText(it) } ?: unspecified
                ), style = st
            )
            Text(
                stringResource(
                    R.string.profile_link_imp_start,
                    when (o.validityStart) {
                        "assigned" -> stringResource(R.string.profile_start_assigned)
                        "first_auth" -> stringResource(R.string.profile_start_first_auth)
                        else -> unspecified
                    }
                ), style = st
            )
            Text(stringResource(R.string.profile_link_imp_uptime, o.limitUptimeSeconds?.let { durationText(it) } ?: none), style = st)
            Text(stringResource(R.string.profile_link_imp_transfer, o.limitTransferBytes?.let { bytesText(it) } ?: none), style = st)
        }
    }
}
