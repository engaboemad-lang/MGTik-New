package ps.mgtik.hotspot.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.AuditLog
import ps.mgtik.hotspot.domain.model.LiveSession
import ps.mgtik.hotspot.domain.model.SessionEndReason
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import ps.mgtik.hotspot.domain.model.SystemClass
import ps.mgtik.hotspot.domain.model.TrackedSession
import ps.mgtik.hotspot.domain.model.UserRecordStatus
import ps.mgtik.hotspot.domain.model.UserSyncStatus
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import ps.mgtik.hotspot.ui.components.live.formatClock
import ps.mgtik.hotspot.ui.components.live.formatSessionDuration
import ps.mgtik.hotspot.ui.components.live.liveStatusText
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.util.ErrorTranslator
import ps.mgtik.hotspot.ui.util.formatIsoDateTime
import ps.mgtik.hotspot.ui.util.formatLimitBytes
import ps.mgtik.hotspot.ui.viewmodel.LiveDataSource
import ps.mgtik.hotspot.ui.viewmodel.LiveSessionsUiState
import ps.mgtik.hotspot.ui.viewmodel.SectionState
import ps.mgtik.hotspot.ui.viewmodel.UserWorkspaceUiState
import ps.mgtik.hotspot.ui.viewmodel.WorkspaceAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private fun formatDateTime(ms: Long): String = DATE_TIME_FMT.format(Instant.ofEpochMilli(ms))
private const val NA = "—"

private enum class Confirm { DISCONNECT, DISABLE, ENABLE }

/**
 * S31 - مساحة عمل المستخدم (المستوى الثالث). شاشة كاملة قابلة للتمرير (ليست Dialog).
 * الجلسة الحالية من المصدر الحي المشترك [live]؛ الحساب والإحصائيات والسجل والنشاط من [ws].
 * الفصل بين الجلسة الحالية والتراكمي صارم: بطاقتان منفصلتان، والتراكمي = جلسات مُغلقة مسجَّلة (+ الجارية معلَّمة).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S31_UserWorkspaceScreen(
    username: String,
    live: LiveSessionsUiState,
    ws: UserWorkspaceUiState,
    onRefresh: () -> Unit,
    onDisconnect: (LiveSession) -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
    onLoadHistory: () -> Unit,
    onOpenFullProfile: (recordUuid: String) -> Unit,
    onOpenSubscription: (subscriptionId: String) -> Unit,
    onLiveMessageShown: () -> Unit,
    onWorkspaceMessageShown: () -> Unit,
    onBack: () -> Unit
) {
    val current = live.sessions.firstOrNull { it.username == username }
    var lastKnown by remember(username) { mutableStateOf<LiveSession?>(null) }
    LaunchedEffect(current) { if (current != null) lastKnown = current }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val actionsBusy = ws.busy != null || live.disconnectingId != null

    val snackbar = remember { SnackbarHostState() }
    val liveMsg = live.messageResId?.let { id -> live.messageArg?.let { stringResource(id, it) } ?: stringResource(id) }
    LaunchedEffect(liveMsg) { if (liveMsg != null) { snackbar.showSnackbar(liveMsg); onLiveMessageShown() } }
    val wsMsg = ws.messageResId?.let { id -> ws.messageArg?.let { stringResource(id, it) } ?: stringResource(id) }
    LaunchedEffect(wsMsg) { if (wsMsg != null) { snackbar.showSnackbar(wsMsg); onWorkspaceMessageShown() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MgtikTopAppBar(
                title = {
                    Column {
                        Text(username)
                        Text(
                            liveStatusText(live), style = MaterialTheme.typography.bodySmall,
                            color = if (live.source == LiveDataSource.LIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    if (ws.accountLoading || live.isRefreshing) {
                        CircularProgressIndicator(Modifier.padding(Dimens.SpaceMD).size(Dimens.IconSizeLarge), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.ws_act_refresh)) }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Dimens.ScreenHorizontalPadding, vertical = Dimens.SpaceSM),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)
        ) {
            if (live.source == LiveDataSource.CACHED && live.lastSuccessAt != null) {
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(Dimens.RadiusSM)) {
                        Text(
                            stringResource(
                                if (live.errorIsConnectivity) R.string.live_stale_router_fmt else R.string.live_stale_data_fmt,
                                formatClock(live.lastSuccessAt)
                            ),
                            modifier = Modifier.padding(Dimens.SpaceMD), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            item { AccountCard(username, ws, current ?: lastKnown, current) }
            item { CurrentSessionCard(live, current, lastKnown) }
            item { LifetimeCard(ws, current) }
            item {
                ActionsCard(
                    ws = ws, live = live, current = current, busy = actionsBusy,
                    onDisconnect = { confirm = Confirm.DISCONNECT },
                    onDisable = { confirm = Confirm.DISABLE },
                    onEnable = { confirm = Confirm.ENABLE },
                    onRefresh = onRefresh, onOpenFullProfile = onOpenFullProfile, onOpenSubscription = onOpenSubscription
                )
            }
            item { HistoryHeader(ws, onLoadHistory) }
            when (val h = ws.history) {
                is SectionState.Loaded -> if (h.value.isEmpty()) {
                    item { Note(stringResource(R.string.ws_h_empty)) }
                } else {
                    items(h.value, key = { it.sessionKey }) { HistoryRow(it) }
                }
                is SectionState.Failed -> item { Note(stringResource(h.errorResId), error = true) }
                SectionState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                SectionState.NotLoaded -> Unit
            }
            item { ActivitySection(ws.activity) }
            item { Spacer(Modifier.height(Dimens.SpaceXL)) }
        }
    }

    confirm?.let { c ->
        val (title, body, label) = when (c) {
            Confirm.DISCONNECT -> Triple(stringResource(R.string.live_disconnect_title, username), stringResource(R.string.live_disconnect_body), R.string.live_disconnect_confirm)
            Confirm.DISABLE -> Triple(stringResource(R.string.ws_disable_title, username), stringResource(R.string.ws_disable_body), R.string.ws_act_disable)
            Confirm.ENABLE -> Triple(stringResource(R.string.ws_enable_title, username), stringResource(R.string.ws_enable_body), R.string.ws_act_enable)
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when (c) {
                        Confirm.DISCONNECT -> current?.let(onDisconnect)
                        Confirm.DISABLE -> onDisable()
                        Confirm.ENABLE -> onEnable()
                    }
                }) { Text(stringResource(label)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

// ───────────── البطاقات ─────────────

@Composable
private fun SectionCard(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            Text(stringResource(title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Pair2(l1: String, v1: String, l2: String, v2: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
        Field(l1, v1, Modifier.weight(1f))
        Field(l2, v2, Modifier.weight(1f))
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AccountCard(username: String, ws: UserWorkspaceUiState, session: LiveSession?, connectedNow: LiveSession?) {
    SectionCard(R.string.ws_section_account) {
        if (ws.accountLoading && ws.record == null && ws.subscription == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        ws.accountErrorResId?.let { Note(stringResource(it), error = true) }
        val rec = ws.record
        val sub = ws.subscription
        val typeText = when {
            ws.isSubscriber -> stringResource(R.string.ws_type_subscription)
            rec != null -> stringResource(R.string.ws_type_normal)
            ws.accountLoading -> NA
            else -> stringResource(R.string.ws_type_unmanaged)
        }
        val statusText = when {
            sub != null -> stringResource(sub.status.labelRes())
            rec != null -> stringResource(rec.status.labelRes())
            else -> NA
        }
        Pair2(stringResource(R.string.ws_f_username), username, stringResource(R.string.ws_f_type), typeText)
        Pair2(stringResource(R.string.ws_f_status), statusText, stringResource(R.string.ws_f_owner), ws.ownerName ?: NA)
        Pair2(
            stringResource(R.string.ws_f_package), sub?.profileName?.takeIf { it.isNotBlank() } ?: session?.profile ?: NA,
            stringResource(R.string.ws_f_category), sub?.profileClassificationSnapshot?.takeIf { it.isNotBlank() } ?: NA
        )
        Pair2(
            stringResource(R.string.ws_f_profile), sub?.routerProfileName?.takeIf { it.isNotBlank() } ?: session?.profile ?: NA,
            "", ""
        )
        Pair2(
            stringResource(R.string.ws_f_created), (rec?.createdAt ?: sub?.createdAt)?.takeIf { it.isNotBlank() }?.let(::formatIsoDateTime) ?: NA,
            stringResource(R.string.ws_f_expiry), sub?.expiresAt?.takeIf { it.isNotBlank() }?.let(::formatIsoDateTime) ?: NA
        )
        val syncText = when (rec?.syncStatus) {
            UserSyncStatus.SYNCED -> stringResource(R.string.ws_sync_synced)
            UserSyncStatus.PENDING -> stringResource(R.string.ws_sync_pending)
            UserSyncStatus.FAILED -> stringResource(R.string.ws_sync_failed)
            null -> NA
        }
        Pair2(
            stringResource(R.string.ws_f_sync), syncText,
            stringResource(R.string.ws_f_ip), connectedNow?.address?.ifBlank { null } ?: NA
        )
        Pair2(stringResource(R.string.ws_f_mac), connectedNow?.macAddress?.ifBlank { null } ?: NA, "", "")
        if (!ws.accountLoading && rec == null && sub == null && ws.accountErrorResId == null) Note(stringResource(R.string.ws_unmanaged_note))
    }
}

@Composable
private fun CurrentSessionCard(live: LiveSessionsUiState, current: LiveSession?, lastKnown: LiveSession?) {
    SectionCard(R.string.ws_section_session) {
        when {
            current != null -> {
                Pair2(stringResource(R.string.active_users_f_uptime), formatSessionDuration(current.uptimeSeconds),
                    stringResource(R.string.active_users_f_started),
                    if (live.lastSuccessAt != null && current.uptimeSeconds != null) formatClock(live.lastSuccessAt - current.uptimeSeconds * 1000) else NA)
                Pair2(stringResource(R.string.active_users_f_download), formatLimitBytes(current.downloadBytes),
                    stringResource(R.string.active_users_f_upload), formatLimitBytes(current.uploadBytes))
                Pair2(stringResource(R.string.ws_s_total), formatLimitBytes(current.totalBytes), "", "")
                Pair2(stringResource(R.string.active_users_f_ip), current.address.ifBlank { NA },
                    stringResource(R.string.active_users_f_mac), current.macAddress.ifBlank { NA })
                Pair2(stringResource(R.string.active_users_f_profile), current.profile ?: NA, "", "")
                live.lastSuccessAt?.let { Note(stringResource(R.string.ws_s_updated_fmt, formatClock(it))) }
            }
            lastKnown != null && live.source == LiveDataSource.LIVE -> {
                Text(stringResource(R.string.ws_s_ended_title), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Note(stringResource(R.string.ws_s_ended_body_fmt, formatClock(live.lastSuccessAt ?: 0L)))
                Pair2(stringResource(R.string.active_users_f_uptime), formatSessionDuration(lastKnown.uptimeSeconds),
                    stringResource(R.string.ws_s_total), formatLimitBytes(lastKnown.totalBytes))
            }
            lastKnown != null -> {
                Text(stringResource(R.string.ws_s_unverified), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Pair2(stringResource(R.string.active_users_f_uptime), formatSessionDuration(lastKnown.uptimeSeconds),
                    stringResource(R.string.ws_s_total), formatLimitBytes(lastKnown.totalBytes))
            }
            live.source == LiveDataSource.LIVE -> Note(stringResource(R.string.ws_s_not_connected))
            live.errorResId != null -> Note(stringResource(R.string.ws_s_unverified), error = true)
            else -> LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LifetimeCard(ws: UserWorkspaceUiState, current: LiveSession?) {
    SectionCard(R.string.ws_section_lifetime) {
        when (val st = ws.stats) {
            SectionState.NotLoaded, SectionState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is SectionState.Failed -> Note(stringResource(st.errorResId), error = true)
            is SectionState.Loaded -> {
                val s = st.value
                if (s == null) {
                    // لا نعرض أصفاراً: لا سجل بعد = غير متاح
                    Note(stringResource(R.string.ws_l_unavailable))
                } else {
                    val up = s.totalUptimeSeconds + (current?.uptimeSeconds ?: 0L)
                    val dl = s.totalDownloadBytes + (current?.downloadBytes ?: 0L)
                    val ul = s.totalUploadBytes + (current?.uploadBytes ?: 0L)
                    Pair2(stringResource(R.string.ws_l_total_uptime), formatSessionDuration(up),
                        stringResource(R.string.ws_l_session_count), (s.closedSessionCount + if (current != null) 1 else 0).toString())
                    Pair2(stringResource(R.string.ws_l_total_download), formatLimitBytes(dl),
                        stringResource(R.string.ws_l_total_upload), formatLimitBytes(ul))
                    Pair2(stringResource(R.string.ws_l_total_traffic), formatLimitBytes(dl + ul),
                        stringResource(R.string.ws_l_last_seen),
                        if (current != null) stringResource(R.string.ws_l_last_seen_now) else s.lastSeenAtMs?.let(::formatDateTime) ?: NA)
                    Pair2(stringResource(R.string.ws_l_last_duration), s.lastSessionDurationSeconds?.let { formatSessionDuration(it) } ?: NA, "", "")
                    s.trackingSinceMs?.let { Note(stringResource(R.string.ws_l_since_fmt, formatDateTime(it))) }
                    if (current != null) Note(stringResource(R.string.ws_l_includes_current))
                }
            }
        }
    }
}

@Composable
private fun ActionsCard(
    ws: UserWorkspaceUiState, live: LiveSessionsUiState, current: LiveSession?, busy: Boolean,
    onDisconnect: () -> Unit, onDisable: () -> Unit, onEnable: () -> Unit, onRefresh: () -> Unit,
    onOpenFullProfile: (String) -> Unit, onOpenSubscription: (String) -> Unit
) {
    val rec = ws.record
    val shape = RoundedCornerShape(Dimens.RadiusSM)
    SectionCard(R.string.ws_section_actions) {
        OutlinedButton(
            onClick = onDisconnect, shape = shape, modifier = Modifier.fillMaxWidth(),
            enabled = current != null && live.source == LiveDataSource.LIVE && !busy
        ) {
            if (live.disconnectingId != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.live_disconnect_btn))
        }
        if (ws.isSubscriber) {
            Note(stringResource(R.string.ws_act_subscriber_note))
        } else if (rec != null) {
            if (rec.status == UserRecordStatus.DISABLED) {
                Button(onClick = onEnable, shape = shape, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                    if (ws.busy == WorkspaceAction.ENABLE) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.ws_act_enable))
                }
            } else {
                Button(
                    onClick = onDisable, shape = shape, modifier = Modifier.fillMaxWidth(),
                    enabled = rec.status == UserRecordStatus.ACTIVE && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (ws.busy == WorkspaceAction.DISABLE) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.ws_act_disable))
                }
            }
        }
        OutlinedButton(onClick = onRefresh, shape = shape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ws_act_refresh)) }
        if (rec != null) {
            OutlinedButton(onClick = { onOpenFullProfile(rec.recordUuid) }, shape = shape, modifier = Modifier.fillMaxWidth(), enabled = !ws.isSubscriber) {
                Text(stringResource(R.string.ws_act_profile))
            }
        }
        ws.subscription?.let { sub ->
            OutlinedButton(onClick = { onOpenSubscription(sub.id) }, shape = shape, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ws_act_subscription))
            }
        }
    }
}

@Composable
private fun HistoryHeader(ws: UserWorkspaceUiState, onLoad: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
        Text(stringResource(R.string.ws_section_history), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Note(stringResource(R.string.ws_h_note))
        OutlinedButton(
            onClick = onLoad, enabled = ws.history !is SectionState.Loading,
            shape = RoundedCornerShape(Dimens.RadiusSM), modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(if (ws.history is SectionState.NotLoaded) R.string.ws_h_load else R.string.ws_h_reload)) }
    }
}

@Composable
private fun HistoryRow(s: TrackedSession) {
    var expanded by remember(s.sessionKey) { mutableStateOf(false) }
    val est = stringResource(R.string.ws_h_estimated)
    Card(
        shape = RoundedCornerShape(Dimens.RadiusMD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(Dimens.SpaceMD), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
            Pair2(
                stringResource(R.string.ws_h_start), formatDateTime(s.startedAtMs) + if (s.startEstimated) " $est" else "",
                stringResource(R.string.ws_h_end), (s.endedAtMs?.let(::formatDateTime) ?: NA) + if (s.endEstimated) " $est" else ""
            )
            Pair2(
                stringResource(R.string.ws_h_duration), formatSessionDuration(s.durationSeconds),
                stringResource(R.string.ws_l_total_traffic), formatLimitBytes(s.totalBytes)
            )
            if (expanded) {
                Pair2(stringResource(R.string.active_users_f_download), formatLimitBytes(s.downloadBytes),
                    stringResource(R.string.active_users_f_upload), formatLimitBytes(s.uploadBytes))
                Pair2(stringResource(R.string.active_users_f_ip), s.address.ifBlank { NA }, stringResource(R.string.active_users_f_mac), s.macAddress.ifBlank { NA })
                Pair2(stringResource(R.string.active_users_f_profile), s.profile ?: NA, "", "")
                Field(stringResource(R.string.ws_h_reason), s.endReason?.let { stringResource(it.labelRes()) } ?: NA)
            }
        }
    }
}

@Composable
private fun ActivitySection(activity: SectionState<List<AuditLog>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
        Text(stringResource(R.string.ws_section_activity), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Note(stringResource(R.string.ws_a_local_note))
        when (activity) {
            SectionState.NotLoaded, SectionState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is SectionState.Failed -> Note(stringResource(activity.errorResId), error = true)
            is SectionState.Loaded -> if (activity.value.isEmpty()) Note(stringResource(R.string.ws_a_empty)) else {
                activity.value.forEach { log ->
                    Column {
                        Text("${log.action} · ${formatDateTime(log.timestamp)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(log.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ───────────── تسميات ─────────────

@StringRes
private fun SessionEndReason.labelRes(): Int = when (this) {
    SessionEndReason.DISAPPEARED -> R.string.ws_reason_disappeared
    SessionEndReason.RECONNECTED -> R.string.ws_reason_reconnected
    SessionEndReason.DISCONNECTED_BY_ADMIN -> R.string.ws_reason_admin
    SessionEndReason.ACCOUNT_DISABLED -> R.string.ws_reason_disabled
}

@StringRes
private fun UserRecordStatus.labelRes(): Int = when (this) {
    UserRecordStatus.ACTIVE -> R.string.ws_status_active
    UserRecordStatus.DISABLED -> R.string.ws_status_disabled
    UserRecordStatus.EXPIRED -> R.string.ws_status_expired
}

@StringRes
private fun SubscriptionStatus.labelRes(): Int = when (this) {
    SubscriptionStatus.DRAFT -> R.string.ws_sub_draft
    SubscriptionStatus.SCHEDULED -> R.string.ws_sub_scheduled
    SubscriptionStatus.ACTIVE -> R.string.ws_status_active
    SubscriptionStatus.EXPIRED -> R.string.ws_status_expired
    SubscriptionStatus.SUSPENDED -> R.string.ws_sub_suspended
    SubscriptionStatus.CANCELLED -> R.string.ws_sub_cancelled
}
