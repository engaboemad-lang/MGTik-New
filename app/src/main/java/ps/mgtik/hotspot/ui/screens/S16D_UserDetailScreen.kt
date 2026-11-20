package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.subscription.SubscriptionLink
import ps.mgtik.hotspot.domain.subscription.SubscriptionLinkResolver
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.util.formatIsoDateTime
import ps.mgtik.hotspot.ui.util.formatLimitBytes
import ps.mgtik.hotspot.ui.util.formatLimitDuration
import ps.mgtik.hotspot.ui.viewmodel.LiveSessionsUiState
import ps.mgtik.hotspot.ui.viewmodel.SectionState
import ps.mgtik.hotspot.ui.viewmodel.UserDetailViewModel
import ps.mgtik.hotspot.ui.viewmodel.UsersViewModel
import java.text.DateFormat
import java.util.Date

/**
 * S16D_UserDetailScreen - صفحة تفاصيل مستخدم مستقلة (تُفتح بالضغط على المستخدم في S16).
 *
 * مصادر البيانات (لا منطق مكرر ولا قيم افتراضية معروضة كأنها فعلية — غير المتاح يُعرض «غير متاح»):
 *  - الإداري: سجل المستخدم (UsersViewModel) + الاشتراك + المالك + الباقة/الفئة/التصنيف من الباقات السحابية.
 *  - التشغيلي: الجلسة الحية (LiveSessionsViewModel المشترك) + قراءة حساب الراوتر (UserDetailViewModel، للعرض فقط).
 *  - التعديل: شاشة التعديل الحالية (S16E: المالك والقيود فقط، بتحقق وعرض نتيجة)؛ البيانات التشغيلية لا تُعدَّل من هنا.
 *  - الإجراءات: نفس إجراءات بطاقة S16 عبر UsersViewModel نفسه.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S16_UserDetailScreen(
    recordUuid: String,
    usersViewModel: UsersViewModel,
    detailViewModel: UserDetailViewModel,
    liveSessions: LiveSessionsUiState,
    owners: List<OwnerAccount>,
    ownerNameIndex: List<OwnerAccount> = owners,
    subscriptions: List<Subscription>,
    subscriptionsLoaded: Boolean,
    profiles: List<Profile>,
    categories: List<ProfileCategory>,
    classifications: List<ProfileClassification>,
    onEdit: (String) -> Unit,
    onOpenSubscription: (String) -> Unit,
    onOpenQueue: () -> Unit,
    onBack: () -> Unit
) {
    val usersState by usersViewModel.uiState.collectAsState()
    val detail by detailViewModel.uiState.collectAsState()
    val record = usersState.managedUsers.firstOrNull { it.recordUuid == recordUuid }
    val snackbarHostState = remember { SnackbarHostState() }
    var showArchive by remember { mutableStateOf(false) }
    var showDeleteRouter by remember { mutableStateOf(false) }
    var showPassword by remember(recordUuid) { mutableStateOf(false) }

    // تحميل الجزء التشغيلي، ويُعاد بعد تغيّر حالة السجل (تفعيل/تعطيل/مزامنة) كي لا تبقى لقطة الراوتر قديمة
    LaunchedEffect(recordUuid, record?.routerUsername, record?.status, record?.syncStatus, record?.updatedAt) {
        record?.routerUsername?.let { detailViewModel.open(recordUuid, it) }
    }

    // اختفاء السجل بعد ظهوره (أُرشف فعلاً) ⇒ العودة للقائمة
    var seen by remember(recordUuid) { mutableStateOf(false) }
    LaunchedEffect(record != null) {
        if (record != null) seen = true else if (seen) onBack()
    }

    val messageResId = usersState.successMessageResId ?: usersState.warningMessageResId ?: usersState.errorMessageResId
    val messageText = messageResId?.let { stringResource(it) }
    LaunchedEffect(messageResId) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            usersViewModel.clearMessages()
        }
    }

    if (showArchive && record != null) {
        var reason by remember(record.recordUuid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showArchive = false },
            title = { Text(stringResource(R.string.user_archive_title, record.routerUsername), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                    Text(stringResource(R.string.user_archive_body))
                    if (record.recordUuid in usersState.missingKeys) {
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
                    onClick = { usersViewModel.archiveUser(record.recordUuid, reason.trim()); showArchive = false },
                    enabled = reason.isNotBlank()
                ) { Text(stringResource(R.string.profile_menu_archive), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showArchive = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    if (showDeleteRouter && record != null) {
        AlertDialog(
            onDismissRequest = { showDeleteRouter = false },
            title = { Text(stringResource(R.string.user_delete_router_title, record.routerUsername), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.user_delete_router_body)) },
            confirmButton = {
                TextButton(onClick = { usersViewModel.deleteFromRouter(record.recordUuid); showDeleteRouter = false }) {
                    Text(stringResource(R.string.user_action_delete_router), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteRouter = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.ud_title))
                        record?.let { Text(it.routerUsername, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { detailViewModel.refresh() }, enabled = record != null) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ud_btn_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (record == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (usersState.isUsersLoading) CircularProgressIndicator()
                else Text(stringResource(R.string.ud_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        val link = if (record.systemClass == SystemClass.SUBSCRIPTION && subscriptionsLoaded)
            SubscriptionLinkResolver.resolve(record, subscriptions) else null
        val sub: Subscription? = (link as? SubscriptionLink.Found)?.subscription
        val router: RouterUserSnapshot? = detail.router.valueOrNull()
        val storedPassword: String? = detail.storedPassword.valueOrNull()
        val liveSession = liveSessions.sessions.firstOrNull { it.username == record.routerUsername }
        val isAr = java.util.Locale.getDefault().language == "ar"

        // الباقة/الفئة/التصنيف: ربط الاشتراك بمعرّف الباقة، وغيره باسم بروفايل الراوتر (لا تخمين عند عدم التطابق)
        val routerProfileName = router?.profile ?: sub?.routerProfileName?.takeIf { it.isNotBlank() }
        val profile = profiles.firstOrNull { sub != null && sub.profileId.isNotBlank() && it.id == sub.profileId }
            ?: profiles.firstOrNull { routerProfileName != null && it.linkedProfileName == routerProfileName }
        val category = profile?.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
        val classification = profile?.classificationKey?.let { k -> classifications.firstOrNull { it.key == k } }
        val ownerName = (ownerNameIndex.firstOrNull { it.ownerId == record.ownerId } ?: owners.firstOrNull { it.ownerId == record.ownerId })?.name?.takeIf { it.isNotBlank() }
            ?: sub?.ownerName?.takeIf { it.isNotBlank() }

        val password = router?.password ?: storedPassword ?: sub?.routerPassword?.takeIf { it.isNotBlank() }
        val pending = usersState.pendingOps[record.recordUuid]
        val missing = record.recordUuid in usersState.missingKeys
        val actionsEnabled = !usersState.isProcessing && pending == null

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
        ) {
            if (usersState.isProcessing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            // ───────── البيانات الأساسية والإدارية ─────────
            item {
                DetailSection(stringResource(R.string.ud_section_admin)) {
                    DetailRow(stringResource(R.string.ud_customer_name), sub?.customerName?.takeIf { it.isNotBlank() })
                    DetailRow(stringResource(R.string.ud_login_name), record.routerUsername, mono = true)
                    // كلمة المرور: مخفية افتراضياً وتُكشف بطلب صريح؛ لا نسخ ولا تسجيل
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            DetailRow(
                                stringResource(R.string.ud_password),
                                password?.let { if (showPassword) it else "••••••••" },
                                mono = true
                            )
                        }
                        if (password != null) {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password)
                                )
                            }
                        }
                    }
                    DetailRow(
                        stringResource(R.string.ud_profile),
                        profile?.let { p ->
                            (if (isAr) p.displayNameAr.ifBlank { p.displayNameEn } else p.displayNameEn.ifBlank { p.displayNameAr })
                                .ifBlank { p.linkedProfileName }
                        } ?: routerProfileName
                    )
                    DetailRow(
                        stringResource(R.string.ud_category),
                        category?.let { c -> if (isAr) c.nameAr.ifBlank { c.nameEn } else c.nameEn.ifBlank { c.nameAr } }
                    )
                    DetailRow(
                        stringResource(R.string.ud_classification),
                        classification?.let { it.nameAr.ifBlank { it.key } }
                    )
                    DetailRow(stringResource(R.string.sysclass_label), stringResource(systemClassLabelRes(record.systemClass)))
                    DetailRow(stringResource(R.string.ud_owner_agent), ownerName ?: record.ownerId.takeIf { it.isNotBlank() })
                    DetailRow(stringResource(R.string.user_detail_created), record.createdAt.takeIf { it.isNotBlank() }?.let { formatIsoDateTime(it) })
                    DetailRow(
                        stringResource(R.string.user_detail_status),
                        stringResource(
                            when (record.status) {
                                UserRecordStatus.ACTIVE -> R.string.user_status_active
                                UserRecordStatus.DISABLED -> R.string.user_status_disabled
                                UserRecordStatus.EXPIRED -> R.string.user_status_expired
                            }
                        )
                    )
                    DetailRow(stringResource(R.string.ud_expiry), sub?.expiresAt?.takeIf { it.isNotBlank() }?.let { formatIsoDateTime(it) })
                    DetailRow(
                        stringResource(R.string.ud_notes),
                        listOfNotNull(sub?.notes?.takeIf { it.isNotBlank() }, router?.comment).joinToString("\n").takeIf { it.isNotBlank() }
                    )
                }
            }

            // ───────── البيانات التشغيلية ─────────
            item {
                DetailSection(stringResource(R.string.ud_section_ops)) {
                    when (val r = detail.router) {
                        is SectionState.Loading, is SectionState.NotLoaded ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.ud_router_loading), style = MaterialTheme.typography.bodySmall)
                            }
                        is SectionState.Failed ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(r.errorResId), color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { detailViewModel.refresh() }) { Text(stringResource(R.string.ud_retry)) }
                            }
                        is SectionState.Loaded -> Unit
                    }
                    Text(stringResource(R.string.ud_ops_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // حالة الاتصال: تُحسم من الجلسات الحية فقط إن نجحت قراءتها؛ وإلا «غير معروف» (لا نفترض غير متصل)
                    DetailRow(
                        stringResource(R.string.ud_connection),
                        stringResource(
                            when {
                                !liveSessions.hasData -> R.string.ud_conn_unknown
                                liveSession != null -> R.string.ud_conn_online
                                else -> R.string.ud_conn_offline
                            }
                        )
                    )
                    DetailRow(stringResource(R.string.ud_ip), liveSession?.address?.takeIf { it.isNotBlank() }, mono = true)
                    DetailRow(stringResource(R.string.ud_mac), liveSession?.macAddress?.takeIf { it.isNotBlank() }, mono = true)
                    DetailRow(
                        stringResource(R.string.ud_session_start),
                        liveSession?.uptimeSeconds?.let { up -> liveSessions.lastSuccessAt?.let { t -> formatEpochMs(t - up * 1000) } }
                    )
                    DetailRow(
                        stringResource(R.string.ud_session_duration),
                        liveSession?.uptimeSeconds?.let { formatLimitDuration(it) }
                    )

                    // المتبقي من الفترة = حد الباقة − استهلاك الفترة الحالية (لا الإجمالي التاريخي)؛ بلا حد مضبوط ⇒ «غير محدد»، وغير مقروء ⇒ غير متاح
                    val limitUptime = router?.limitUptimeSeconds ?: if (router == null) record.limitUptimeSeconds else null
                    val limitTransfer = router?.limitTransferBytes ?: if (router == null) record.limitTransferBytes else null
                    DetailRow(
                        stringResource(R.string.ud_remaining_time),
                        when {
                            router == null -> null
                            router.limitUptimeSeconds == null -> stringResource(R.string.limit_unlimited)
                            router.usedUptimeSeconds == null -> null
                            else -> formatLimitDuration((router.limitUptimeSeconds - router.usedUptimeSeconds).coerceAtLeast(0L))
                        }
                    )
                    DetailRow(stringResource(R.string.ud_uptime_used), router?.usedUptimeSeconds?.let { formatLimitDuration(it) })
                    DetailRow(stringResource(R.string.ud_data_used), router?.usedBytes?.let { formatLimitBytes(it) })
                    DetailRow(stringResource(R.string.ud_uptime_total), router?.totalUptimeSeconds?.let { formatLimitDuration(it) })
                    DetailRow(stringResource(R.string.ud_data_total), router?.totalBytes?.let { formatLimitBytes(it) })
                    DetailRow(
                        stringResource(R.string.ud_limit_uptime),
                        if (router != null || record.limitUptimeSeconds != null) formatLimitDuration(limitUptime) else null
                    )
                    DetailRow(
                        stringResource(R.string.ud_limit_transfer),
                        if (router != null || record.limitTransferBytes != null) formatLimitBytes(limitTransfer) else null
                    )
                    DetailRow(
                        stringResource(R.string.ud_speed),
                        profile?.takeIf { it.rateLimitRxKbps > 0 || it.rateLimitTxKbps > 0 }?.let {
                            stringResource(R.string.ud_speed_fmt, rateText(it.rateLimitRxKbps), rateText(it.rateLimitTxKbps))
                        }
                    )
                    DetailRow(stringResource(R.string.ud_shared_users), profile?.sharedUsers?.toString())
                    DetailRow(
                        stringResource(R.string.ud_router_account),
                        when {
                            missing || (detail.router is SectionState.Loaded && router == null) -> stringResource(R.string.ud_router_missing)
                            router != null -> stringResource(R.string.ud_router_found)
                            else -> null
                        }
                    )
                    DetailRow(
                        stringResource(R.string.ud_sync_state),
                        stringResource(
                            when (record.syncStatus) {
                                UserSyncStatus.SYNCED -> R.string.user_sync_synced
                                UserSyncStatus.PENDING -> R.string.user_sync_pending
                                UserSyncStatus.FAILED -> R.string.user_sync_failed
                            }
                        )
                    )
                }
            }

            // عملية أرشفة/تعديل لم تكتمل: تُعرض Pending أو فاشلة مع رابط الطابور، ولا تُعرض مكتملة أبداً
            if (pending != null) item {
                val color = if (pending.failed) MaterialTheme.colorScheme.error else LocalMgtikExtendedColors.current.warning
                Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(Dimens.RadiusSM), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = Dimens.SpaceMD, vertical = Dimens.SpaceSM), verticalAlignment = Alignment.CenterVertically) {
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
                            color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (pending.failed) TextButton(onClick = onOpenQueue) { Text(stringResource(R.string.user_btn_open_queue)) }
                    }
                }
            }

            // ───────── الإجراءات: كل إجراءات بطاقة S16 متاحة هنا ─────────
            item {
                DetailSection(stringResource(R.string.ud_section_actions)) {
                    if (record.systemClass == SystemClass.SUBSCRIPTION) {
                        // المشترك الشهري: كل العمليات (تجديد/إيقاف/استئناف/إلغاء/مزامنة) من S28
                        if (link is SubscriptionLink.Missing) {
                            Text(stringResource(R.string.sub_link_missing_inline), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { if (sub != null) onOpenSubscription(sub.id) },
                            enabled = sub != null,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(Dimens.RadiusSM)
                        ) { Text(stringResource(R.string.sub_btn_open_details)) }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                            if (record.status == UserRecordStatus.DISABLED) {
                                Button(onClick = { usersViewModel.enableUser(record.recordUuid) }, enabled = actionsEnabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                                    Text(stringResource(R.string.user_action_enable), style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                OutlinedButton(onClick = { usersViewModel.disableUser(record.recordUuid) }, enabled = actionsEnabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                                    Text(stringResource(R.string.user_action_disable), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Button(onClick = { usersViewModel.renewPackage(record.recordUuid) }, enabled = actionsEnabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                                Text(stringResource(R.string.user_action_renew), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                            if (missing) {
                                OutlinedButton(onClick = { usersViewModel.reExportToRouter(record.recordUuid) }, enabled = actionsEnabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                                    Text(stringResource(R.string.user_action_reexport), style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                OutlinedButton(onClick = { showDeleteRouter = true }, enabled = actionsEnabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                                    Text(stringResource(R.string.user_action_delete_router), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                }
                                if (record.syncStatus != UserSyncStatus.SYNCED) {
                                    OutlinedButton(onClick = { usersViewModel.retryUserSync(record.recordUuid) }, enabled = actionsEnabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                                        Text(stringResource(R.string.user_action_retry_sync), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                            Button(onClick = { onEdit(record.recordUuid) }, enabled = actionsEnabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                                Text(stringResource(R.string.ud_btn_edit), style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = { showArchive = true }, enabled = actionsEnabled, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.profile_menu_archive), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun <T> SectionState<T>.valueOrNull(): T? = when (this) {
    is SectionState.Loaded -> value
    else -> null
}

private fun formatEpochMs(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

@Composable
private fun rateText(kbps: Int): String =
    if (kbps >= 1000) stringResource(R.string.ud_rate_mbps_fmt, java.util.Locale.US.let { String.format(it, "%.1f", kbps / 1000.0).trimEnd('0').trimEnd('.') })
    else stringResource(R.string.ud_rate_kbps_fmt, kbps)

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** صف بيانات: value = null يُعرض «غير متاح» (لا قيمة افتراضية تُعرض كأنها فعلية). */
@Composable
private fun DetailRow(label: String, value: String?, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value == null) {
            Text(stringResource(R.string.ud_unavailable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else null
            )
        }
    }
}
