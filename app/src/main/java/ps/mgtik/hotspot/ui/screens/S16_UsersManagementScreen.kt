package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.subscription.SubscriptionLink
import ps.mgtik.hotspot.domain.subscription.SubscriptionLinkResolver
import ps.mgtik.hotspot.ui.viewmodel.UsersViewModel
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.util.formatIsoDateTime
import ps.mgtik.hotspot.ui.util.formatLimitBytes
import ps.mgtik.hotspot.ui.util.formatLimitDuration
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors

/**
 * S16_UsersManagementScreen - إدارة المشتركين والمستخدمين (§16).
 * تم التطهير البرمجي:
 * 1. استبدال الألوان الصلبة بـ ExtendedColors و MaterialTheme.
 * 2. ربط جميع الحالات والنصوص بـ strings.xml.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S16_UsersManagementScreen(
    viewModel: UsersViewModel,
    owners: List<OwnerAccount>,
    ownerNameIndex: List<OwnerAccount> = owners,
    onOpenArchive: () -> Unit,
    onEditUser: (String) -> Unit,
    /** فتح صفحة تفاصيل المستخدم (S16D). */
    onOpenUser: (String) -> Unit,
    onCreateUser: () -> Unit,
    onOpenQueue: () -> Unit,
    subscriptions: List<Subscription>,
    subscriptionsLoaded: Boolean,
    onOpenSubscription: (String) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selectedCategory by remember { mutableStateOf("managed") } 
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<UserRecordStatus?>(null) }
    // نوع المستخدم: null = الكل، NORMAL = العاديون، SUBSCRIPTION = المشتركون الشهريون
    var classFilter by remember { mutableStateOf<SystemClass?>(null) }
    var sortByName by remember { mutableStateOf(false) }
    // مشترك بلا اشتراك مرتبط: نافذة توضيحية بدل فتح S28 فارغة
    var missingSubscriptionTarget by remember { mutableStateOf<LocalUserRecord?>(null) }
    // اسم المالك بدل معرّفه الخام في البطاقات والبحث
    val ownerNames = remember(ownerNameIndex, owners) { (ownerNameIndex + owners).associate { it.ownerId to it.name } }
    val snackbarHostState = remember { SnackbarHostState() }

    // مستخدم مكتشَف ينتظر اختيار المالك (بدل نسبته إلى مالك وهمي ثابت)
    var adoptTarget by remember { mutableStateOf<DiscoveryRecord?>(null) }
    // مستخدم ينتظر إدخال سبب الأرشفة
    var archiveTarget by remember { mutableStateOf<LocalUserRecord?>(null) }
    // تأكيد عمليات هدّامة: حذف مستخدم مُدار من الراوتر، حذف مستخدم غير معرّف
    var deleteFromRouterTarget by remember { mutableStateOf<LocalUserRecord?>(null) }
    var deleteNullTarget by remember { mutableStateOf<DiscoveryRecord?>(null) }

    // تحذير: الحفظ نجح لكن عملية الراوتر الإلزامية لم تكتمل؛ يُعرض صراحةً ولا يُدمج مع رسالة النجاح
    val messageResId = state.successMessageResId ?: state.warningMessageResId ?: state.errorMessageResId
    val messageText = messageResId?.let { stringResource(it) }
    LaunchedEffect(messageResId) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.clearMessages()
        }
    }

    val filteredManaged = remember(state.managedUsers, searchQuery, statusFilter, classFilter, sortByName, ownerNames) {
        val q = searchQuery.trim()
        state.managedUsers
            .filter { u ->
                (statusFilter == null || u.status == statusFilter) &&
                    (classFilter == null || u.systemClass == classFilter) &&
                    (q.isEmpty() ||
                        u.routerUsername.contains(q, ignoreCase = true) ||
                        u.ownerId.contains(q, ignoreCase = true) ||
                        ownerNames[u.ownerId]?.contains(q, ignoreCase = true) == true)
            }
            .let { list ->
                if (sortByName) list.sortedBy { it.routerUsername.lowercase() }
                else list.sortedByDescending { it.createdAt }
            }
    }

    val filteredNull = remember(state.nullUsers, searchQuery) {
        state.nullUsers.filter { 
            it.routerUsername.contains(searchQuery.trim(), ignoreCase = true)
        }
    }

    missingSubscriptionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { missingSubscriptionTarget = null },
            title = { Text(stringResource(R.string.sub_link_missing_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.sub_link_missing_body, target.routerUsername)) },
            confirmButton = { TextButton(onClick = { missingSubscriptionTarget = null }) { Text(stringResource(R.string.common_close)) } }
        )
    }

    archiveTarget?.let { target ->
        var reason by remember(target.recordUuid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text(stringResource(R.string.user_archive_title, target.routerUsername), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                    Text(stringResource(R.string.user_archive_body))
                    if (target.recordUuid in state.missingKeys) {
                        Text(stringResource(R.string.user_archive_body_missing), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text(stringResource(R.string.user_archive_reason)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.archiveUser(target.recordUuid, reason.trim()); archiveTarget = null },
                    enabled = reason.isNotBlank()
                ) { Text(stringResource(R.string.profile_menu_archive), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    deleteFromRouterTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteFromRouterTarget = null },
            title = { Text(stringResource(R.string.user_delete_router_title, target.routerUsername), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.user_delete_router_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteFromRouter(target.recordUuid); deleteFromRouterTarget = null }) {
                    Text(stringResource(R.string.user_action_delete_router), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteFromRouterTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    deleteNullTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteNullTarget = null },
            title = { Text(stringResource(R.string.user_delete_null_title, target.routerUsername), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.user_delete_null_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteNullUser(target.routerUsername); deleteNullTarget = null }) {
                    Text(stringResource(R.string.user_action_delete_router), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteNullTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    adoptTarget?.let { target ->
        AdoptOwnerPickerDialog(
            owners = owners.filter { it.status != OwnerStatus.ARCHIVED },
            onPick = { owner ->
                viewModel.adoptNullUser(target.routerUsername, owner.ownerId)
                adoptTarget = null
            },
            onDismiss = { adoptTarget = null }
        )
    }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.user_management_title))
                        Text(stringResource(R.string.user_management_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenArchive) {
                        Icon(Icons.Default.Archive, contentDescription = stringResource(R.string.user_btn_open_archive))
                    }
                    IconButton(onClick = { viewModel.syncWithRouter() }, enabled = !state.isSyncing) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = stringResource(R.string.user_btn_sync_registry),
                            tint = if (state.isSyncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateUser,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.user_create_button), fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
        ) {
            TabRow(
                selectedTabIndex = if (selectedCategory == "managed") 0 else 1,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedCategory == "managed",
                    onClick = { selectedCategory = "managed" },
                    text = { Text(stringResource(R.string.user_tab_managed), fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedCategory == "null",
                    onClick = { selectedCategory = "null" },
                    text = { Text(stringResource(R.string.user_tab_null), fontWeight = FontWeight.Bold) }
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.user_search_managed_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                singleLine = true
            )

            if (selectedCategory == "managed") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = classFilter == null,
                        onClick = { classFilter = null },
                        label = { Text(stringResource(R.string.sysclass_filter_all)) }
                    )
                    SystemClass.entries.forEach { c ->
                        FilterChip(
                            selected = classFilter == c,
                            onClick = { classFilter = if (classFilter == c) null else c },
                            label = { Text(stringResource(systemClassLabelRes(c))) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = statusFilter == null,
                        onClick = { statusFilter = null },
                        label = { Text(stringResource(R.string.user_filter_all)) }
                    )
                    UserRecordStatus.values().forEach { st ->
                        FilterChip(
                            selected = statusFilter == st,
                            onClick = { statusFilter = if (statusFilter == st) null else st },
                            label = {
                                Text(
                                    stringResource(
                                        when (st) {
                                            UserRecordStatus.ACTIVE -> R.string.user_status_active
                                            UserRecordStatus.DISABLED -> R.string.user_status_disabled
                                            UserRecordStatus.EXPIRED -> R.string.user_status_expired
                                        }
                                    )
                                )
                            }
                        )
                    }
                    VerticalDivider(modifier = Modifier.height(24.dp))
                    FilterChip(
                        selected = !sortByName,
                        onClick = { sortByName = false },
                        label = { Text(stringResource(R.string.user_sort_newest)) }
                    )
                    FilterChip(
                        selected = sortByName,
                        onClick = { sortByName = true },
                        label = { Text(stringResource(R.string.user_sort_name)) }
                    )
                }
            }

            if (state.isSyncing || state.isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            run {
                val listToDisplay = if (selectedCategory == "managed") filteredManaged else filteredNull
                
                if (selectedCategory == "managed" && state.isUsersLoading && state.managedUsers.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.user_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (listToDisplay.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.user_no_records), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
                        contentPadding = PaddingValues(bottom = 88.dp) // لا يغطي زر الإنشاء آخر بطاقة
                    ) {
                        if (selectedCategory == "managed") {
                            items(filteredManaged, key = { it.recordUuid }) { user ->
                                // العلاقة تُتحقَّق من بيانات الاشتراكات (لا افتراض تطابق المعرّفين)؛ null = القائمة لم تصل بعد
                                val link = if (user.systemClass == SystemClass.SUBSCRIPTION && subscriptionsLoaded)
                                    SubscriptionLinkResolver.resolve(user, subscriptions) else null
                                ManagedUserCard(
                                    user = user,
                                    subscriptionLink = link,
                                    onOpenSubscription = {
                                        when (link) {
                                            is SubscriptionLink.Found -> onOpenSubscription(link.subscription.id)
                                            else -> missingSubscriptionTarget = user
                                        }
                                    },
                                    ownerName = ownerNames[user.ownerId],
                                    pending = state.pendingOps[user.recordUuid],
                                    missing = user.recordUuid in state.missingKeys,
                                    onDeleteFromRouter = { deleteFromRouterTarget = user },
                                    onReExport = { viewModel.reExportToRouter(user.recordUuid) },
                                    onRetrySync = { viewModel.retryUserSync(user.recordUuid) },
                                    enabled = !state.isProcessing && state.pendingOps[user.recordUuid] == null,
                                    onDetails = { onOpenUser(user.recordUuid) },
                                    onEdit = { onEditUser(user.recordUuid) },
                                    onOpenQueue = onOpenQueue,
                                    onEnable = { viewModel.enableUser(user.recordUuid) },
                                    onDisable = { viewModel.disableUser(user.recordUuid) },
                                    onRenew = { viewModel.renewPackage(user.recordUuid) },
                                    onArchive = { archiveTarget = user }
                                )
                            }
                        } else {
                            items(filteredNull, key = { it.discoveryId }) { disc ->
                                NullUserCard(
                                    record = disc,
                                    enabled = !state.isProcessing,
                                    onAdopt = { adoptTarget = disc },
                                    onDisable = { viewModel.disableNullUser(disc.routerUsername) },
                                    onDelete = { deleteNullTarget = disc }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagedUserCard(
    user: LocalUserRecord,
    subscriptionLink: SubscriptionLink?,
    onOpenSubscription: () -> Unit,
    ownerName: String?,
    pending: UserPendingOperation?,
    missing: Boolean,
    onDeleteFromRouter: () -> Unit,
    onReExport: () -> Unit,
    onRetrySync: () -> Unit,
    enabled: Boolean,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onOpenQueue: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onRenew: () -> Unit,
    onArchive: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails)
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(user.routerUsername, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    if (user.systemClass == SystemClass.SUBSCRIPTION) {
                        Text(stringResource(R.string.sysclass_subscription), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Text(stringResource(R.string.user_label_owner, ownerName?.takeIf { it.isNotBlank() } ?: user.ownerId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // القيود التي أُنشئ الحساب عليها (لقطة وقت التوليد؛ لا تتبع تعديلات البروفايل اللاحقة)
                    if (user.limitUptimeSeconds != null || user.limitTransferBytes != null) {
                        Text(
                            stringResource(
                                R.string.profile_limits_summary_fmt,
                                formatLimitDuration(user.limitUptimeSeconds),
                                formatLimitBytes(user.limitTransferBytes)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                    StatusBadge(user.status)
                    SyncBadge(user.syncStatus)
                    if (missing) {
                        Text(
                            stringResource(R.string.user_badge_missing),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            // عملية أرشفة/تعديل لم تكتمل: تُعرض Pending (أو فاشلة مع رابط للطابور) ولا تُعرض مكتملة أبداً
            if (pending != null) {
                val color = if (pending.failed) MaterialTheme.colorScheme.error else LocalMgtikExtendedColors.current.warning
                Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(Dimens.RadiusSM), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = Dimens.SpaceMD, vertical = Dimens.SpaceSM),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(
                                when {
                                    pending.failed -> R.string.user_pending_failed
                                    pending.kind == UserPendingKind.ARCHIVE -> R.string.user_pending_archive
                                    pending.kind == UserPendingKind.DELETE_FROM_ROUTER -> R.string.user_pending_delete_router
                                    pending.kind == UserPendingKind.REEXPORT -> R.string.user_pending_reexport
                                    else -> R.string.user_pending_edit
                                }
                            ),
                            color = color,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (pending.failed) TextButton(onClick = onOpenQueue) { Text(stringResource(R.string.user_btn_open_queue)) }
                    }
                }
            }

            if (user.systemClass == SystemClass.SUBSCRIPTION) {
                // المشترك الشهري: لا إجراءات مستخدم عادي؛ كل العمليات (تجديد/إيقاف/استئناف/إلغاء/مزامنة) من S28
                if (subscriptionLink is SubscriptionLink.Missing) {
                    Text(
                        stringResource(R.string.sub_link_missing_inline),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                    Button(onClick = onOpenSubscription, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                        Text(stringResource(R.string.sub_btn_open_details), style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onDetails, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.user_btn_details), style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                if (user.status == UserRecordStatus.DISABLED) {
                    Button(onClick = onEnable, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                        Text(stringResource(R.string.user_action_enable), style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    OutlinedButton(onClick = onDisable, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                        Text(stringResource(R.string.user_action_disable), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Button(onClick = onRenew, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                    Text(stringResource(R.string.user_action_renew), style = MaterialTheme.typography.labelMedium)
                }
            }
            // عمليات الراوتر الإضافية: مفقود ⇒ إعادة تصدير؛ موجود ⇒ حذف من الراوتر؛ وإعادة المزامنة إن لم تكن SYNCED
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                if (missing) {
                    OutlinedButton(onClick = onReExport, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                        Text(stringResource(R.string.user_action_reexport), style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    OutlinedButton(onClick = onDeleteFromRouter, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                        Text(stringResource(R.string.user_action_delete_router), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                    if (user.syncStatus != UserSyncStatus.SYNCED) {
                        OutlinedButton(onClick = onRetrySync, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                            Text(stringResource(R.string.user_action_retry_sync), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                TextButton(onClick = onDetails, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.user_btn_details), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.user_action_edit), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onArchive, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_menu_archive), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
            }
        }
    }
}

@Composable
private fun NullUserCard(
    record: DiscoveryRecord,
    enabled: Boolean,
    onAdopt: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
            Text(record.routerUsername, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.user_null_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAdopt, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                Text(stringResource(R.string.user_action_adopt), style = MaterialTheme.typography.labelMedium)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                OutlinedButton(onClick = onDisable, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                    Text(stringResource(R.string.user_action_disable), style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = onDelete, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                    Text(stringResource(R.string.user_action_delete_router), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: UserRecordStatus) {
    val extendedColors = LocalMgtikExtendedColors.current
    val color = when(status) {
        UserRecordStatus.ACTIVE -> extendedColors.success
        UserRecordStatus.DISABLED -> MaterialTheme.colorScheme.outline
        UserRecordStatus.EXPIRED -> MaterialTheme.colorScheme.error
        else -> extendedColors.warning
    }
    Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(Dimens.RadiusXS)) {
        Text(
            text = stringResource(
                when (status) {
                    UserRecordStatus.ACTIVE -> R.string.user_status_active
                    UserRecordStatus.DISABLED -> R.string.user_status_disabled
                    UserRecordStatus.EXPIRED -> R.string.user_status_expired
                }
            ),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = Dimens.SpaceXXS)
        )
    }
}

@Composable
private fun SyncBadge(sync: UserSyncStatus) {
    val extendedColors = LocalMgtikExtendedColors.current
    val color = when (sync) {
        UserSyncStatus.SYNCED -> extendedColors.success
        UserSyncStatus.PENDING -> extendedColors.warning
        UserSyncStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    Text(
        text = stringResource(
            when (sync) {
                UserSyncStatus.SYNCED -> R.string.user_sync_synced
                UserSyncStatus.PENDING -> R.string.user_sync_pending
                UserSyncStatus.FAILED -> R.string.user_sync_failed
            }
        ),
        color = color,
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun AdoptOwnerPickerDialog(
    owners: List<OwnerAccount>,
    onPick: (OwnerAccount) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.user_adopt_pick_owner_title), fontWeight = FontWeight.Bold) },
        text = {
            if (owners.isEmpty()) {
                Text(stringResource(R.string.user_adopt_no_owners))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                    items(owners, key = { it.ownerId }) { owner ->
                        TextButton(onClick = { onPick(owner) }, modifier = Modifier.fillMaxWidth()) {
                            Text(owner.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
