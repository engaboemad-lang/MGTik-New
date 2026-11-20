package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.BorderStroke
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.domain.model.RouterLinkState
import ps.mgtik.hotspot.domain.model.RouterOverview
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import ps.mgtik.hotspot.ui.util.formatIsoDateTime
import ps.mgtik.hotspot.ui.viewmodel.DataLoad
import androidx.compose.ui.text.style.TextOverflow
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.components.cards.MgtikKpiCard
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.viewmodel.MgtikUiState

/**
 * S3_OverviewScreen - لوحة المعلومات المركزية (§3).
 * تم التطهير البرمجي:
 * 1. Resource Bound: ربط كافة النصوص والوحدات (Currency, MB) بـ strings.xml.
 * 2. Layout Consistency: استخدام Dimens الموحدة.
 * 3. حالة الراوتر من اتصال RouterOS المركزي مباشرة ([MgtikUiState.routerOverview])؛ القياسات غير المتاحة تُعرض «غير متاح» لا صفراً.
 * 4. أقسام Firestore (الاشتراكات/المدفوعات/دخل اليوم) لا تُعرض أرقامها إلا بعد تحميل فعلي ناجح؛ وفشلها يظهر صراحةً.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S3_OverviewScreen(
    state: MgtikUiState,
    onNavigateToRouterSetup: () -> Unit,
    onNavigateToGenerator: () -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenQueue: () -> Unit = {},
    /** تحديث يدوي: يعيد تشغيل مراقبات السحابة ويجلب المتصلين من الراوتر فوراً. */
    onRefresh: () -> Unit = {},
    // ── المراقبة العامة ──
    onOpenActiveUsers: () -> Unit = {},
    /** حالة المتصلين الآن الحية من RouterOS (LiveSessionsViewModel). */
    liveSessions: ps.mgtik.hotspot.ui.viewmodel.LiveSessionsUiState = ps.mgtik.hotspot.ui.viewmodel.LiveSessionsUiState(),
    onOpenSubscriptionsOverview: () -> Unit = {},
    onOpenFinancials: () -> Unit = {},
    // ── الوصول السريع (وجهات Navigation الموجودة) ──
    onOpenUsersManagement: () -> Unit = {},
    onOpenMonthlySubscriptions: () -> Unit = {},
    onOpenInvoices: () -> Unit = {},
    onOpenReports: () -> Unit = {},
    // ── إجراءات سريعة (تفتح نموذج التنفيذ أو تستدعي العملية الفعلية) ──
    onCreateSingleUser: () -> Unit = {},
    onCreateMonthlySubscription: () -> Unit = {},
    onAddProfile: () -> Unit = {},
    onAddCategory: () -> Unit = {},
    onIssueInvoice: () -> Unit = {},
    onSyncUsers: () -> Unit = {},
    /** مزامنة المستخدمين مع MikroTik جارية (تعطّل الزر وتمنع الضغط المتكرر). */
    isSyncingUsers: Boolean = false,
    /** نتيجة آخر مزامنة (نجاح/خطأ) لتُعرض مرة واحدة ثم تُستهلك عبر [onSyncMessageShown]. */
    @androidx.annotation.StringRes syncSuccessResId: Int? = null,
    @androidx.annotation.StringRes syncErrorResId: Int? = null,
    onSyncMessageShown: () -> Unit = {},
    /** عدد الإشعارات غير المقروءة الفعلي (من NotificationViewModel؛ MgtikUiState.notifications لا تُملأ). */
    unreadNotificationsCount: Int
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // العدد من الجلسات الفعلية على الراوتر فقط: بلا نتيجة ناجحة بعد يُعرض «—» لا صفر. المخزَّن يُعلَّم ولا يُقدَّم كمباشر.
    val liveCountText = if (liveSessions.hasData) liveSessions.sessions.size.toString() else "—"
    val liveStatus = ps.mgtik.hotspot.ui.components.live.liveStatusText(liveSessions)
    val liveStatusColor = if (liveSessions.source == ps.mgtik.hotspot.ui.viewmodel.LiveDataSource.LIVE)
        MaterialTheme.colorScheme.primary else if (liveSessions.errorResId != null) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    // الرسالة تخص مزامنة بدأت من هنا فقط: 0 خامل، 1 طُلبت، 2 رُصد تنفيذها؛ فلا تظهر رسائل عمليات أخرى متبقية
    var syncPhase by remember { mutableIntStateOf(0) }
    val syncSuccessText = syncSuccessResId?.let { stringResource(it) }
    val syncErrorText = syncErrorResId?.let { stringResource(it) }
    LaunchedEffect(isSyncingUsers) {
        if (syncPhase == 1 && isSyncingUsers) syncPhase = 2
    }
    LaunchedEffect(syncPhase, isSyncingUsers, syncSuccessText, syncErrorText) {
        if (syncPhase == 2 && !isSyncingUsers) {
            val text = syncErrorText ?: syncSuccessText
            syncPhase = 0
            if (text != null) {
                snackbarHostState.showSnackbar(text)
                onSyncMessageShown()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.routerOverview.model ?: stringResource(R.string.app_name)
                        )
                        Text(
                            text = stringResource(R.string.dashboard_app_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    if (state.pendingCommandsCount > 0) {
                        IconButton(onClick = onOpenQueue) {
                            BadgedBox(badge = { Badge { Text("${state.pendingCommandsCount}") } }) {
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = stringResource(R.string.dashboard_pending_sync),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }

                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.overview_refresh))
                    }
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    IconButton(onClick = onOpenNotifications) {
                        BadgedBox(
                            badge = {
                                if (unreadNotificationsCount > 0) {
                                    Badge { Text("$unreadNotificationsCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.action_notifications))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
        ) {
            item { RouterStatusCard(state.routerOverview) }

            // ───────── المراقبة العامة ─────────
            item { OverviewSectionTitle(stringResource(R.string.overview_monitor_title)) }
            item {
                val currency = LocalCurrencySymbol.current
                // رقم القسم السحابي لا يُعرض إلا بعد تحميل ناجح؛ وإلا «—» (والفشل يُعلَّم صراحةً تحت القيمة)
                val failedText = stringResource(R.string.overview_data_unavailable)
                val failedColor = MaterialTheme.colorScheme.error
                val neutralColor = MaterialTheme.colorScheme.onSurfaceVariant
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                        MgtikKpiCard(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.overview_monitor_active_users),
                            value = liveCountText,
                            icon = Icons.Default.Wifi,
                            color = MaterialTheme.colorScheme.primary,
                            onClick = onOpenActiveUsers,
                            supportingText = liveStatus,
                            supportingColor = liveStatusColor
                        )
                        MgtikKpiCard(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.overview_monitor_total_subs),
                            value = if (state.subscriptionsLoad == DataLoad.READY) state.subscriptions.count { it.status == ps.mgtik.hotspot.domain.model.SubscriptionStatus.ACTIVE }.toString() else "—",
                            icon = Icons.Default.CardMembership,
                            color = MaterialTheme.colorScheme.secondary,
                            onClick = onOpenSubscriptionsOverview,
                            supportingText = if (state.subscriptionsLoad == DataLoad.FAILED) failedText else null,
                            supportingColor = if (state.subscriptionsLoad == DataLoad.FAILED) failedColor else neutralColor
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                        MgtikKpiCard(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.overview_monitor_total_payments),
                            value = if (state.financialLoad == DataLoad.READY) "${formatMoney(state.financialDashboard.totalPaidMinor)} $currency" else "—",
                            icon = Icons.Default.AccountBalanceWallet,
                            color = MaterialTheme.colorScheme.tertiary,
                            onClick = onOpenFinancials,
                            supportingText = if (state.financialLoad == DataLoad.FAILED) failedText else null,
                            supportingColor = if (state.financialLoad == DataLoad.FAILED) failedColor else neutralColor
                        )
                        MgtikKpiCard(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.overview_monitor_today_income),
                            value = if (state.todayIncomeLoad == DataLoad.READY) "${formatMoney(state.todayIncomeMinor)} $currency" else "—",
                            icon = Icons.Default.Today,
                            color = MaterialTheme.colorScheme.primary,
                            onClick = onOpenFinancials,
                            supportingText = if (state.todayIncomeLoad == DataLoad.FAILED) failedText else null,
                            supportingColor = if (state.todayIncomeLoad == DataLoad.FAILED) failedColor else neutralColor
                        )
                    }
                    OverviewFreshnessRow(
                        lastCloudUpdateMillis = state.lastCloudUpdateMillis,
                        anyFailed = state.subscriptionsLoad == DataLoad.FAILED ||
                            state.financialLoad == DataLoad.FAILED || state.todayIncomeLoad == DataLoad.FAILED,
                        liveSessions = liveSessions,
                        onRetry = onRefresh
                    )
                }
            }

            // ───────── الوصول السريع (انتقال) ─────────
            item { OverviewSectionTitle(stringResource(R.string.overview_quick_access_title)) }
            item {
                ActionTileGrid(
                    tiles = listOf(
                        ActionTile(Icons.Default.ManageAccounts, stringResource(R.string.overview_qa_users), onClick = onOpenUsersManagement),
                        ActionTile(Icons.Default.CardMembership, stringResource(R.string.overview_qa_subscriptions), onClick = onOpenMonthlySubscriptions),
                        ActionTile(Icons.Default.RequestQuote, stringResource(R.string.overview_qa_billing), onClick = onOpenInvoices),
                        ActionTile(Icons.Default.BarChart, stringResource(R.string.overview_qa_reports), onClick = onOpenReports)
                    )
                )
            }

            // ───────── إجراءات سريعة (تنفيذ) ─────────
            item { OverviewSectionTitle(stringResource(R.string.overview_quick_actions_title)) }
            item {
                ActionTileGrid(
                    tiles = listOf(
                        ActionTile(Icons.Default.PersonAdd, stringResource(R.string.overview_act_create_user), onClick = onCreateSingleUser),
                        ActionTile(Icons.Default.Autorenew, stringResource(R.string.overview_act_create_subscription), onClick = onCreateMonthlySubscription),
                        ActionTile(Icons.Default.Speed, stringResource(R.string.overview_act_add_profile), onClick = onAddProfile),
                        ActionTile(Icons.Default.Category, stringResource(R.string.overview_act_add_category), onClick = onAddCategory),
                        ActionTile(Icons.Default.Receipt, stringResource(R.string.overview_act_issue_invoice), hint = stringResource(R.string.overview_act_issue_invoice_hint), onClick = onIssueInvoice),
                        ActionTile(
                            Icons.Default.Sync,
                            stringResource(R.string.overview_act_sync_users),
                            hint = if (isSyncingUsers) stringResource(R.string.overview_act_syncing) else null,
                            loading = isSyncingUsers,
                            enabled = !isSyncingUsers,
                            onClick = { syncPhase = 1; onSyncUsers() }
                        )
                    )
                )
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                    OutlinedButton(onClick = onNavigateToGenerator, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusMD)) {
                        Icon(Icons.Default.Bolt, null, modifier = Modifier.size(Dimens.IconSizeSmall))
                        Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                        Text(stringResource(R.string.dashboard_action_generate))
                    }
                    OutlinedButton(onClick = onNavigateToProfiles, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusMD)) {
                        Icon(Icons.Default.Speed, null, modifier = Modifier.size(Dimens.IconSizeSmall))
                        Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                        Text(stringResource(R.string.dashboard_action_profiles))
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.dashboard_recent_subs_title), 
                    style = MaterialTheme.typography.titleMedium, 
                    fontWeight = FontWeight.Bold
                )
            }

            // المصدر (CloudSubscriptionDataSource) يرتّب من الأحدث حسب تاريخ الإنشاء الفعلي؛ هنا أحدث خمسة فقط
            when (state.subscriptionsLoad) {
                DataLoad.LOADING -> item {
                    Text(text = stringResource(R.string.overview_data_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DataLoad.FAILED -> item {
                    Text(text = stringResource(R.string.overview_data_unavailable), color = MaterialTheme.colorScheme.error)
                }
                DataLoad.READY -> if (state.subscriptions.isEmpty()) {
                    item { Text(text = stringResource(R.string.dashboard_no_subs), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(state.subscriptions.take(5), key = { it.id }) { sub ->
                        SubscriptionCard(sub)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouterStatusCard(overview: RouterOverview) {
    val unavailable = stringResource(R.string.dashboard_value_unavailable)
    val statusText = when (overview.link) {
        RouterLinkState.CONNECTED -> stringResource(R.string.dashboard_router_connected)
        RouterLinkState.CONNECTING -> stringResource(R.string.dashboard_router_connecting)
        RouterLinkState.DISCONNECTED -> stringResource(R.string.dashboard_router_disconnected)
        RouterLinkState.NOT_CONFIGURED -> stringResource(R.string.dashboard_router_not_configured)
    }
    val statusColor = when (overview.link) {
        RouterLinkState.CONNECTED -> MaterialTheme.colorScheme.primary
        RouterLinkState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        RouterLinkState.DISCONNECTED -> MaterialTheme.colorScheme.error
        RouterLinkState.NOT_CONFIGURED -> MaterialTheme.colorScheme.outline
    }
    val mbUnit = stringResource(R.string.profile_limit_unit_mb)
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = statusColor, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                Spacer(modifier = Modifier.width(Dimens.SpaceMD))
                Text(text = statusText, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(Dimens.SpaceLG))
            // القياسات (CPU/الذاكرة/الإصدار) من اتصال ناجح فقط؛ بلا قياس = «غير متاح» وليس صفراً
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                RouterStatColumn(
                    Modifier.weight(1f), stringResource(R.string.dashboard_cpu_label),
                    overview.cpuLoadPercent?.let { "$it%" } ?: unavailable
                )
                RouterStatColumn(
                    Modifier.weight(1f), stringResource(R.string.dashboard_ram_label),
                    overview.freeMemoryMb?.let { "$it $mbUnit" } ?: unavailable
                )
                RouterStatColumn(
                    Modifier.weight(1f), stringResource(R.string.dashboard_os_label),
                    overview.osVersion ?: unavailable
                )
            }
            Spacer(modifier = Modifier.height(Dimens.SpaceMD))
            // الموديل (من الراوتر) والعنوان (من الإعداد) حقلان مستقلان
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                RouterStatColumn(
                    Modifier.weight(1f), stringResource(R.string.dashboard_model_label),
                    overview.model ?: unavailable
                )
                RouterStatColumn(
                    Modifier.weight(1f), stringResource(R.string.dashboard_ip_label),
                    overview.host.ifBlank { unavailable }
                )
            }
        }
    }
}

@Composable
private fun RouterStatColumn(modifier: Modifier, label: String, value: String) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SubscriptionCard(sub: Subscription) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.RadiusLG)
    ) {
        Row(modifier = Modifier.padding(Dimens.SpaceLG), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sub.customerUsername.ifBlank { sub.customerName },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sub.profileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (sub.createdAt.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.overview_recent_subs_created, formatIsoDateTime(sub.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(Dimens.SpaceMD))
            Column(horizontalAlignment = Alignment.End) {
                // سعر الفترة الفعلي بعد الخصم (لا السعر الأصلي)
                Text(
                    text = "${formatMoney(sub.effectivePriceMinor)} ${LocalCurrencySymbol.current}",
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(Dimens.SpaceXS))
                SubscriptionStatusChip(sub.status)
            }
        }
    }
}

@Composable
private fun SubscriptionStatusChip(status: SubscriptionStatus) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        SubscriptionStatus.ACTIVE -> scheme.primaryContainer to scheme.onPrimaryContainer
        SubscriptionStatus.SCHEDULED -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SubscriptionStatus.SUSPENDED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED -> scheme.errorContainer to scheme.onErrorContainer
        SubscriptionStatus.DRAFT -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text = stringResource(subscriptionStatusRes(status)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = Dimens.SpaceSM, vertical = 2.dp)
        )
    }
}

@Composable
private fun OverviewSectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

private data class ActionTile(
    val icon: ImageVector,
    val label: String,
    val hint: String? = null,
    val loading: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/** شبكة بطاقات إجراء: عمودان على الهاتف وثلاثة على الأجهزة اللوحية؛ كل بطاقة تنفّذ onClick حقيقياً. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionTileGrid(tiles: List<ActionTile>) {
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 3 else 2
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
        tiles.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                row.forEach { tile ->
                    Card(
                        onClick = tile.onClick,
                        enabled = tile.enabled,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Dimens.RadiusLG),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(Dimens.SpaceMD),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (tile.loading) {
                                CircularProgressIndicator(Modifier.size(Dimens.IconSizeLarge), strokeWidth = 2.dp)
                            } else {
                                Icon(tile.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.IconSizeLarge))
                            }
                            Spacer(Modifier.height(Dimens.SpaceSM))
                            Text(tile.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            tile.hint?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                // صفّ أخير ناقص: نملأه بفراغات لتبقى الأعمدة متساوية العرض
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * سطر حداثة البيانات: متى وصلت آخر بيانات سحابية (الأرقام المالية والاشتراكات لحظية من Firestore) ومتى جُلب المتصلون من الراوتر،
 * مع زر «إعادة المحاولة» عند فشل أي قسم. الفشل لا يبقى صامتاً ولا دائماً بلا مخرج.
 */
@Composable
private fun OverviewFreshnessRow(
    lastCloudUpdateMillis: Long?,
    anyFailed: Boolean,
    liveSessions: ps.mgtik.hotspot.ui.viewmodel.LiveSessionsUiState,
    onRetry: () -> Unit
) {
    val timeFormat = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault()) }
    fun timeOf(ms: Long) = timeFormat.format(java.time.Instant.ofEpochMilli(ms))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = lastCloudUpdateMillis?.let { stringResource(R.string.overview_cloud_updated_at, timeOf(it)) }
                    ?: stringResource(R.string.overview_cloud_not_updated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            liveSessions.lastSuccessAt?.let {
                Text(
                    text = stringResource(R.string.overview_live_updated_at, timeOf(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (anyFailed) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.overview_retry)) }
        }
    }
}
