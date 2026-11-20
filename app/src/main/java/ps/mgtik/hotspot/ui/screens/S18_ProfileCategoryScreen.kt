package ps.mgtik.hotspot.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import ps.mgtik.hotspot.domain.model.SaleContext
import ps.mgtik.hotspot.domain.model.PricingRule
import ps.mgtik.hotspot.domain.model.OwnerStatus
import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.CategoryStatus
import ps.mgtik.hotspot.domain.model.SystemClass
import ps.mgtik.hotspot.domain.usecase.CategoryRules
import ps.mgtik.hotspot.ui.viewmodel.ProfilesViewModel
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.components.profile.UsageSummaryText
import ps.mgtik.hotspot.domain.usecase.ProfileUsageRules
import ps.mgtik.hotspot.ui.components.profile.CategoryHistorySection
import ps.mgtik.hotspot.ui.theme.Dimens

/**
 * الشاشة 18: فئات وتصنيفات الباقات (§8, §4.2)
 * تم التطهير البرمجي بالكامل للامتثال لمعايير النظام.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S18_ProfileCategoryScreen(
    viewModel: ProfilesViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ProfileCategory?>(null) }
    var query by remember { mutableStateOf("") }
    var classFilter by remember { mutableStateOf<SystemClass?>(null) }
    var statusFilter by remember { mutableStateOf<CategoryStatus?>(null) }
    val selected = state.selectedCategoryId?.let { id -> state.categories.firstOrNull { it.id == id } }
    BackHandler(enabled = selected != null) { viewModel.closeCategoryDetail() }

    // نتيجة الإنشاء (نجاح/فشل) كانت لا تظهر إلا في S17 فيبقى المستخدم هنا بلا أي رد
    val messageResId = state.successMessageResId ?: state.errorMessageResId
    val messageText = messageResId?.let { stringResource(it) }
    LaunchedEffect(messageResId) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(state.profiles.map { it.profile.id }) { viewModel.refreshUsage() }
    fun usageOf(catId: String) = if (state.usageFailed) null
        else ProfileUsageRules.sum(state.profiles.map { it.profile }.filter { it.categoryId == catId }, state.usage)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(selected?.let { it.nameAr.ifBlank { it.nameEn } } ?: stringResource(R.string.profile_cat_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) viewModel.closeCategoryDetail() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (selected != null) {
                        IconButton(onClick = { editing = selected }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.profile_cat_action_edit))
                        }
                    } else {
                        IconButton(onClick = viewModel::openCreateCategoryDialog) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.profile_cat_action_add))
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (selected != null) {
            CategoryDetail(
                modifier = Modifier.padding(padding),
                category = selected,
                profiles = state.profiles.map { it.profile }.filter { it.categoryId == selected.id },
                rules = state.categoryRules,
                owners = state.owners.filter { it.status != OwnerStatus.ARCHIVED },
                isSaving = state.isSavingPrice,
                onSaveOwnerPrice = viewModel::saveCategoryOwnerPrice,
                onToggle = viewModel::setRuleActive,
                onDeleteRule = viewModel::deleteRule,
                usage = usageOf(selected.id),
                usageLoading = state.usageLoading,
                usageFailed = state.usageFailed,
                profileUsage = state.usage,
                history = state.categoryHistory
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
        ) {
            Text(
                text = stringResource(R.string.profile_cat_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.class_search_hint)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                FilterChip(selected = classFilter == null, onClick = { classFilter = null }, label = { Text(stringResource(R.string.filter_all)) })
                SystemClass.entries.forEach { c ->
                    FilterChip(selected = classFilter == c, onClick = { classFilter = c }, label = { Text(stringResource(systemClassLabelRes(c))) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text(stringResource(R.string.filter_all)) })
                FilterChip(selected = statusFilter == CategoryStatus.ACTIVE, onClick = { statusFilter = CategoryStatus.ACTIVE }, label = { Text(stringResource(R.string.status_active)) })
                FilterChip(selected = statusFilter == CategoryStatus.DISABLED, onClick = { statusFilter = CategoryStatus.DISABLED }, label = { Text(stringResource(R.string.profile_cat_status_disabled)) })
                FilterChip(selected = statusFilter == CategoryStatus.ARCHIVED, onClick = { statusFilter = CategoryStatus.ARCHIVED }, label = { Text(stringResource(R.string.status_archived)) })
            }
            val shown = state.categories.filter { c ->
                (classFilter == null || c.systemClass == classFilter) && (statusFilter == null || c.status == statusFilter) &&
                    (query.isBlank() || c.nameAr.contains(query, true) || c.nameEn.contains(query, true))
            }
            if (shown.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.profile_cat_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)
                ) {
                    items(shown.sortedWith(compareBy({ it.status == CategoryStatus.ARCHIVED }, { it.priority }, { it.nameAr })), key = { it.id }) { cat ->
                        CategoryItemCard(
                            category = cat,
                            profilesCount = state.profiles.count { it.profile.categoryId == cat.id },
                            usage = usageOf(cat.id), usageLoading = state.usageLoading, usageFailed = state.usageFailed,
                            onOpen = { viewModel.openCategoryDetail(cat.id) },
                            onEdit = { editing = cat },
                            onStatus = { st -> viewModel.setCategoryStatus(cat, st) },
                            onDelete = { viewModel.requestDeleteCategory(cat) }
                        )
                    }
                }
            }
        }
    }

    if (state.showCreateCategoryDialog || editing != null) {
        CreateCategoryDialog(
            initial = editing,
            isSubmitting = state.isSubmitting,
            onDismiss = { editing = null; viewModel.closeCreateCategoryDialog() },
            onSubmit = { ar, en, desc, cls, cur, retail, wholesale ->
                viewModel.saveCategory(editing, ar, en, desc, cls, cur, retail, wholesale)
            }
        )
    }
    // يُغلق نموذج التعديل بعد نجاح الحفظ فقط؛ عند الفشل يبقى مفتوحاً بمدخلاته
    LaunchedEffect(state.isSubmitting, state.showCreateCategoryDialog) {
        if (!state.isSubmitting && !state.showCreateCategoryDialog && state.successMessageResId != null) editing = null
    }

    state.categoryPendingDelete?.let { cat ->
        val count = state.profiles.count { it.profile.categoryId == cat.id }
        AlertDialog(
            onDismissRequest = viewModel::dismissDeleteCategory,
            title = { Text(stringResource(R.string.profile_cat_delete_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (count > 0) stringResource(R.string.profile_cat_delete_blocked, count)
                    else stringResource(R.string.profile_cat_delete_confirm, cat.nameAr)
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeleteCategory, enabled = count == 0) {
                    Text(stringResource(R.string.profile_btn_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDeleteCategory) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun CreateCategoryDialog(
    initial: ProfileCategory?,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (nameAr: String, nameEn: String, description: String?, systemClass: SystemClass?, currency: String, retail: String, wholesale: String) -> Unit
) {
    var nameAr by remember { mutableStateOf(initial?.nameAr.orEmpty()) }
    var nameEn by remember { mutableStateOf(initial?.nameEn.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    // ثابت بعد تعيينه: يُختار عند الإنشاء (أو مرة واحدة لفئة قديمة غير مصنّفة) ثم يُقفل
    val lockedClass = initial?.systemClass
    var systemClass by remember { mutableStateOf(lockedClass) }
    // العملة تُحدَّد عند الإنشاء (افتراضياً عملة الشركة) وتثبت؛ الأسعار فارغة = غير محددة
    val lockedCurrency = initial?.currency?.takeIf { it.isNotBlank() }
    var currency by remember { mutableStateOf(lockedCurrency ?: "") }
    val defaultCurrency = LocalCurrencySymbol.current
    fun priceText(v: Long?) = v?.let { java.math.BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString() } ?: ""
    var retail by remember { mutableStateOf(priceText(initial?.retailPriceMinor)) }
    var wholesale by remember { mutableStateOf(priceText(initial?.wholesalePriceMinor)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.profile_cat_action_add else R.string.profile_cat_action_edit), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                OutlinedTextField(
                    value = nameAr,
                    onValueChange = { nameAr = it },
                    label = { Text(stringResource(R.string.profile_field_ar_name)) },
                    enabled = !isSubmitting,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                OutlinedTextField(
                    value = nameEn,
                    onValueChange = { nameEn = it },
                    label = { Text(stringResource(R.string.profile_field_en_name)) },
                    enabled = !isSubmitting,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.profile_cat_field_description)) },
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                Text(stringResource(R.string.sysclass_label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    SystemClass.entries.forEach { c ->
                        FilterChip(
                            selected = systemClass == c,
                            onClick = { systemClass = c },
                            enabled = !isSubmitting && lockedClass == null,
                            label = { Text(stringResource(systemClassLabelRes(c))) }
                        )
                    }
                }
                OutlinedTextField(
                    value = currency.ifEmpty { if (lockedCurrency == null) "" else lockedCurrency },
                    onValueChange = { currency = it.take(6) },
                    label = { Text(stringResource(R.string.cat_field_currency)) },
                    placeholder = { Text(defaultCurrency) },
                    enabled = !isSubmitting && lockedCurrency == null, singleLine = true,
                    supportingText = { Text(stringResource(if (lockedCurrency == null) R.string.cat_hint_currency else R.string.cat_hint_currency_locked)) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                OutlinedTextField(
                    value = retail, onValueChange = { v -> if (v.all { it.isDigit() || it == '.' } && v.count { it == '.' } <= 1) retail = v },
                    label = { Text(stringResource(R.string.cat_field_retail)) }, enabled = !isSubmitting, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                OutlinedTextField(
                    value = wholesale, onValueChange = { v -> if (v.all { it.isDigit() || it == '.' } && v.count { it == '.' } <= 1) wholesale = v },
                    label = { Text(stringResource(R.string.cat_field_wholesale)) }, enabled = !isSubmitting, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text(stringResource(R.string.cat_hint_prices)) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD)
                )
                if (lockedClass != null) {
                    Text(stringResource(R.string.sysclass_hint_immutable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(nameAr, nameEn, description, systemClass, currency.ifBlank { defaultCurrency }, retail, wholesale) },
                enabled = !isSubmitting && nameAr.isNotBlank() && systemClass != null
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun CategoryItemCard(
    category: ProfileCategory,
    profilesCount: Int,
    usage: ps.mgtik.hotspot.domain.model.ProfileUsage?,
    usageLoading: Boolean,
    usageFailed: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onStatus: (CategoryStatus) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val inactive = category.status != CategoryStatus.ACTIVE
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Dimens.SpaceLG).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(
                    color = if (inactive) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(Dimens.RadiusSM),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(modifier = Modifier.width(Dimens.SpaceMD))
                Column {
                    Text(category.nameAr, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Text(category.nameEn, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    category.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        stringResource(category.systemClass?.let { systemClassLabelRes(it) } ?: R.string.sysclass_unclassified),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (category.systemClass == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        categoryPriceSummary(category),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (CategoryRules.hasCommercialPrices(category)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                    // عدد البروفايلات داخل الفئة (والحالة إن لم تكن نشطة) — تظهر الفئة الفارغة بصورة طبيعية
                    Text(
                        stringResource(R.string.profile_cat_profiles_count, profilesCount) + when (category.status) {
                            CategoryStatus.ACTIVE -> ""
                            CategoryStatus.DISABLED -> " • " + stringResource(R.string.profile_cat_status_disabled)
                            CategoryStatus.ARCHIVED -> " • " + stringResource(R.string.profile_status_archived)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    UsageSummaryText(usage, usageLoading, usageFailed)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.profile_menu_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.profile_cat_action_edit)) }, onClick = { menuOpen = false; onEdit() })
                    if (category.status != CategoryStatus.ACTIVE) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.profile_menu_restore)) }, onClick = { menuOpen = false; onStatus(CategoryStatus.ACTIVE) })
                    }
                    if (category.status == CategoryStatus.ACTIVE) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.profile_menu_disable)) }, onClick = { menuOpen = false; onStatus(CategoryStatus.DISABLED) })
                    }
                    if (category.status != CategoryStatus.ARCHIVED) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.profile_menu_archive)) }, onClick = { menuOpen = false; onStatus(CategoryStatus.ARCHIVED) })
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_btn_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@androidx.annotation.StringRes
internal fun systemClassLabelRes(c: SystemClass): Int = when (c) {
    SystemClass.NORMAL -> R.string.sysclass_normal
    SystemClass.SUBSCRIPTION -> R.string.sysclass_subscription
}


@Composable
private fun categoryPriceSummary(c: ProfileCategory): String {
    if (!CategoryRules.hasCommercialPrices(c)) return stringResource(R.string.cat_price_unset)
    val cur = c.currency ?: ""
    val unset = stringResource(R.string.class_not_defined)
    fun f(v: Long?) = v?.let { java.math.BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString() + " $cur" } ?: unset
    return stringResource(R.string.cat_price_summary_fmt, f(c.retailPriceMinor), f(c.wholesalePriceMinor))
}

/** صفحة تفاصيل الفئة: النوع، الأسعار، أسعار المالكين الخاصة (بحسب السياق)، الباقات المرتبطة. */
@Composable
private fun CategoryDetail(
    modifier: Modifier,
    category: ProfileCategory,
    profiles: List<ps.mgtik.hotspot.domain.model.Profile>,
    rules: List<PricingRule>,
    owners: List<ps.mgtik.hotspot.domain.model.OwnerAccount>,
    isSaving: Boolean,
    onSaveOwnerPrice: (ownerId: String, context: SaleContext, price: String) -> Unit,
    onToggle: (PricingRule, Boolean) -> Unit,
    onDeleteRule: (PricingRule) -> Unit,
    usage: ps.mgtik.hotspot.domain.model.ProfileUsage?,
    usageLoading: Boolean,
    usageFailed: Boolean,
    profileUsage: Map<String, ps.mgtik.hotspot.domain.model.ProfileUsage>,
    history: List<ps.mgtik.hotspot.domain.model.CategoryHistoryEntry>
) {
    var selectedOwner by remember { mutableStateOf<String?>(null) }
    var ctx by remember { mutableStateOf(SaleContext.WHOLESALE) }
    var priceText by remember { mutableStateOf("") }
    var ownerMenu by remember { mutableStateOf(false) }
    val currency = category.currency ?: LocalCurrencySymbol.current
    val ownerNames = owners.associate { it.ownerId to it.name }
    fun ctxLabel(c: SaleContext) = if (c == SaleContext.WHOLESALE) R.string.pricing_ctx_wholesale else R.string.pricing_ctx_retail
    val active = category.status == CategoryStatus.ACTIVE

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.ScreenHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
    ) {
        Text(
            stringResource(category.systemClass?.let { systemClassLabelRes(it) } ?: R.string.sysclass_unclassified),
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
        )
        Text(stringResource(R.string.sysclass_hint_immutable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        category.description?.let { Text(it) }

        HorizontalDivider()
        Text(stringResource(R.string.cat_prices_title), fontWeight = FontWeight.Bold)
        Text(categoryPriceSummary(category))
        Text(stringResource(R.string.cat_currency_fmt, currency), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.cat_prices_independent_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider()
        Text(stringResource(R.string.pricing_special_title), fontWeight = FontWeight.Bold)
        if (rules.isEmpty()) {
            Text(stringResource(R.string.pricing_special_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rules.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((ownerNames[r.ownerId] ?: r.ownerId) + " • " + stringResource(ctxLabel(r.context)), fontWeight = FontWeight.Medium)
                    Text(
                        java.math.BigDecimal(r.retailPriceMinor).movePointLeft(2).stripTrailingZeros().toPlainString() + " $currency" +
                            if (r.active) "" else " — " + stringResource(R.string.pricing_special_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = r.active, onCheckedChange = { onToggle(r, it) }, enabled = !isSaving)
                IconButton(onClick = { onDeleteRule(r) }, enabled = !isSaving) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.profile_btn_delete), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (!active) {
            Text(stringResource(R.string.cat_owner_price_inactive), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Box {
            TextButton(onClick = { ownerMenu = true }, enabled = !isSaving && active && owners.isNotEmpty()) {
                Text(selectedOwner?.let { ownerNames[it] } ?: stringResource(R.string.pricing_pick_owner))
            }
            DropdownMenu(expanded = ownerMenu, onDismissRequest = { ownerMenu = false }) {
                owners.forEach { o -> DropdownMenuItem(text = { Text(o.name) }, onClick = { selectedOwner = o.ownerId; ownerMenu = false }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            SaleContext.entries.forEach { c ->
                FilterChip(selected = ctx == c, onClick = { ctx = c }, enabled = !isSaving, label = { Text(stringResource(ctxLabel(c))) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = priceText, onValueChange = { priceText = it }, singleLine = true, enabled = !isSaving && active,
                suffix = { Text(currency) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusMD)
            )
            Button(
                onClick = { selectedOwner?.let { onSaveOwnerPrice(it, ctx, priceText) }; priceText = "" },
                enabled = !isSaving && active && selectedOwner != null && priceText.isNotBlank()
            ) { Text(stringResource(R.string.action_save)) }
        }

        HorizontalDivider()
        Text(stringResource(R.string.cat_linked_profiles) + " (${profiles.size})", fontWeight = FontWeight.Bold)
        if (profiles.isEmpty()) Text(stringResource(R.string.class_no_linked), color = MaterialTheme.colorScheme.onSurfaceVariant)
        profiles.forEach { p ->
            val u = profileUsage[p.id]
            Text("• " + p.displayNameAr.ifBlank { p.linkedProfileName } + (u?.let { " — " + stringResource(R.string.usage_profile_fmt, it.users) } ?: ""))
        }
        Text(stringResource(R.string.usage_section_title), fontWeight = FontWeight.Bold)
        UsageSummaryText(usage, usageLoading, usageFailed)

        HorizontalDivider()
        CategoryHistorySection(history, ownerNames)
    }
}
