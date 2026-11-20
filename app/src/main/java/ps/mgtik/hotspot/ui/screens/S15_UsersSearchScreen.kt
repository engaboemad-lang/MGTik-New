package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import ps.mgtik.hotspot.ui.theme.Dimens
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
import ps.mgtik.hotspot.domain.model.ActiveSession
import ps.mgtik.hotspot.ui.viewmodel.MgtikUiState
import ps.mgtik.hotspot.R

/**
 * الشاشة 15: البحث المتقدم في المتصلين والجلسات النشطة (§8)
 * تم التدقيق الهندسي الكامل لإزالة النصوص والألوان الصلبة ودعم تعدد اللغات بشكل كامل.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S15_UsersSearchScreen(
    state: MgtikUiState,
    onBack: () -> Unit,
    onKickSession: (ActiveSession) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var kickTarget by remember { mutableStateOf<ActiveSession?>(null) }

    kickTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { kickTarget = null },
            title = { Text(stringResource(R.string.session_kick_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.session_kick_confirm_body, target.user)) },
            confirmButton = {
                Button(
                    onClick = { onKickSession(target); kickTarget = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { kickTarget = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    val filteredSessions = remember(searchQuery, state.activeSessions) {
        state.activeSessions.filter {
            it.user.contains(searchQuery, ignoreCase = true) ||
            it.address.contains(searchQuery) ||
            it.macAddress.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.user_search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.SpaceLG),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLG)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.user_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                singleLine = true
            )

            Text(
                text = stringResource(R.string.user_results_count, filteredSessions.size),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (filteredSessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.user_no_sessions), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredSessions, key = { it.mikrotikId.ifBlank { it.user + it.macAddress } }) { session ->
                        ActiveSessionCard(
                            session = session,
                            enabled = !state.isLoading,
                            onKick = { kickTarget = session }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(
    session: ActiveSession,
    enabled: Boolean,
    onKick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Dimens.SpaceLG).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(session.user, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text("IP: ${session.address} • MAC: ${session.macAddress}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.user_label_uptime, session.uptime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(
                onClick = onKick,
                enabled = enabled,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = stringResource(R.string.user_action_kick))
            }
        }
    }
}
