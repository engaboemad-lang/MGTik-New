package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.LiveSession
import ps.mgtik.hotspot.domain.usecase.LiveSessionRules
import ps.mgtik.hotspot.domain.usecase.LiveSessionRules.SortBy
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import ps.mgtik.hotspot.ui.components.live.formatClock
import ps.mgtik.hotspot.ui.components.live.formatSessionDuration
import ps.mgtik.hotspot.ui.components.live.liveStatusText
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.util.formatLimitBytes
import ps.mgtik.hotspot.ui.viewmodel.LiveDataSource
import ps.mgtik.hotspot.ui.viewmodel.LiveSessionsUiState

/**
 * S30 - المستخدمون النشطون (المستوى الثاني من وحدة المراقبة الحية).
 * البيانات من RouterOS عبر LiveSessionsViewModel (تحديث دوري ما دامت الشاشة مرئية + تحديث يدوي).
 * لا أرقام ثابتة: بيانات قديمة (فشل آخر جلب) تُعرض بشريط «مخزنة» مع وقت آخر نجاح، ولا تُقدَّم كمباشرة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S30_ActiveUsersScreen(
    state: LiveSessionsUiState,
    onRefresh: () -> Unit,
    onDisconnect: (LiveSession) -> Unit,
    onOpenUser: (LiveSession) -> Unit,
    onMessageShown: () -> Unit,
    onBack: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(SortBy.UPTIME.name) }
    var profile by rememberSaveable { mutableStateOf<String?>(null) }
    var disconnectTarget by remember { mutableStateOf<LiveSession?>(null) }
    val sortBy = SortBy.valueOf(sortName)

    // عنصر التصفية الذي اختفى من القائمة الحية (انقطع آخر مستخدم عليه) لا يبقي القائمة فارغة بلا تفسير
    val profiles = state.profileNames
    LaunchedEffect(profiles) { if (profile != null && profile !in profiles) profile = null }

    val visible = remember(state.sessions, query, profile, sortBy) {
        LiveSessionRules.apply(state.sessions, query, profile, sortBy)
    }

    val snackbar = remember { SnackbarHostState() }
    val msg = state.messageResId?.let { id -> state.messageArg?.let { stringResource(id, it) } ?: stringResource(id) }
    LaunchedEffect(msg) {
        if (msg != null) { snackbar.showSnackbar(msg); onMessageShown() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MgtikTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.active_users_title))
                        Text(liveStatusText(state), style = MaterialTheme.typography.bodySmall,
                            color = if (state.source == LiveDataSource.LIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(Modifier.padding(Dimens.SpaceMD).size(Dimens.IconSizeLarge), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.active_users_refresh)) }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // بيانات قديمة: فشل آخر تحديث لكن لدينا نتيجة سابقة؛ تحذير صريح بدل إخفاء الفشل
            if (state.source == LiveDataSource.CACHED && state.lastSuccessAt != null) {
                StaleBanner(
                    text = stringResource(
                        if (state.errorIsConnectivity) R.string.live_stale_router_fmt else R.string.live_stale_data_fmt,
                        formatClock(state.lastSuccessAt)
                    )
                )
            }

            when {
                // لا نتيجة ناجحة بعد
                !state.hasData && state.isRefreshing -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.common_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                !state.hasData && state.errorResId != null -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD), modifier = Modifier.padding(Dimens.SpaceXL)) {
                        Icon(Icons.Default.WifiOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                        Text(stringResource(if (state.errorIsConnectivity) R.string.live_err_router else R.string.live_err_data), fontWeight = FontWeight.Bold)
                        Text(stringResource(state.errorResId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onRefresh) { Text(stringResource(R.string.active_users_retry)) }
                    }
                }
                else -> {
                    FilterBar(
                        query = query, onQuery = { query = it },
                        sortBy = sortBy, onSort = { sortName = it.name },
                        profiles = profiles, profile = profile, onProfile = { profile = it },
                        shown = visible.size, total = state.sessions.size
                    )
                    when {
                        state.sessions.isEmpty() -> CenterBox { Text(stringResource(R.string.active_users_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        visible.isEmpty() -> CenterBox { Text(stringResource(R.string.active_users_no_match), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        else -> LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = Dimens.ScreenHorizontalPadding, vertical = Dimens.SpaceSM),
                            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)
                        ) {
                            items(visible, key = { it.sessionId }) { s ->
                                SessionCard(
                                    s = s,
                                    fetchedAt = state.lastSuccessAt,
                                    busy = state.disconnectingId == s.sessionId,
                                    actionsEnabled = state.disconnectingId == null && state.source == LiveDataSource.LIVE,
                                    onDisconnect = { disconnectTarget = s },
                                    onOpen = { onOpenUser(s) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    disconnectTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { disconnectTarget = null },
            title = { Text(stringResource(R.string.live_disconnect_title, t.username)) },
            text = { Text(stringResource(R.string.live_disconnect_body)) },
            confirmButton = {
                TextButton(onClick = { disconnectTarget = null; onDisconnect(t) }) { Text(stringResource(R.string.live_disconnect_confirm)) }
            },
            dismissButton = { TextButton(onClick = { disconnectTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun StaleBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text, modifier = Modifier.padding(horizontal = Dimens.ScreenHorizontalPadding, vertical = Dimens.SpaceSM),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    query: String, onQuery: (String) -> Unit,
    sortBy: SortBy, onSort: (SortBy) -> Unit,
    profiles: List<String>, profile: String?, onProfile: (String?) -> Unit,
    shown: Int, total: Int
) {
    Column(Modifier.padding(horizontal = Dimens.ScreenHorizontalPadding, vertical = Dimens.SpaceSM), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.RadiusMD),
            placeholder = { Text(stringResource(R.string.active_users_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) }
        )
        ChipRow(stringResource(R.string.active_users_sort)) {
            SortBy.values().forEach { sb ->
                FilterChip(selected = sortBy == sb, onClick = { onSort(sb) }, label = {
                    Text(stringResource(when (sb) { SortBy.UPTIME -> R.string.active_users_sort_uptime; SortBy.TRAFFIC -> R.string.active_users_sort_traffic; SortBy.NAME -> R.string.active_users_sort_name }))
                })
            }
        }
        if (profiles.size > 1) ChipRow(stringResource(R.string.active_users_filter_profile)) {
            FilterChip(selected = profile == null, onClick = { onProfile(null) }, label = { Text(stringResource(R.string.active_users_filter_all)) })
            profiles.forEach { p -> FilterChip(selected = profile == p, onClick = { onProfile(p) }, label = { Text(p) }) }
        }
        Text(stringResource(R.string.active_users_count_fmt, shown, total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun SessionCard(s: LiveSession, fetchedAt: Long?, busy: Boolean, actionsEnabled: Boolean, onDisconnect: () -> Unit, onOpen: () -> Unit) {
    val unknown = "—"
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(s.username, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(R.string.active_users_status_connected)) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Field(stringResource(R.string.active_users_f_ip), s.address.ifBlank { unknown }, Modifier.weight(1f))
                Field(stringResource(R.string.active_users_f_mac), s.macAddress.ifBlank { unknown }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Field(stringResource(R.string.active_users_f_profile), s.profile ?: unknown, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Field(stringResource(R.string.active_users_f_uptime), formatSessionDuration(s.uptimeSeconds), Modifier.weight(1f))
                // وقت البدء يُشتق من وقت القراءة ناقص المدة ويُعلَّم «تقريباً»
                Field(
                    stringResource(R.string.active_users_f_started),
                    if (fetchedAt != null && s.uptimeSeconds != null) formatClock(fetchedAt - s.uptimeSeconds * 1000) else unknown,
                    Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Field(stringResource(R.string.active_users_f_download), formatLimitBytes(s.downloadBytes), Modifier.weight(1f))
                Field(stringResource(R.string.active_users_f_upload), formatLimitBytes(s.uploadBytes), Modifier.weight(1f))
            }
            OutlinedButton(onClick = onDisconnect, enabled = actionsEnabled && !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusSM)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.live_disconnect_btn))
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
