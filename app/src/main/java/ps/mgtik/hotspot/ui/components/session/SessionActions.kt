package ps.mgtik.hotspot.ui.components.session

import androidx.compose.foundation.layout.Arrangement
import ps.mgtik.hotspot.ui.theme.Dimens
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.RouterStatus
import ps.mgtik.hotspot.ui.viewmodel.SessionAction
import ps.mgtik.hotspot.ui.viewmodel.SessionActionsUiState

/** نقطة ربط واحدة بين مواضع الأزرار (الإعدادات/القائمة الجانبية) ومنطق الجلسات. null = غير متاحة (قبل الدخول). */
class SessionActionsHost(val routerStatus: RouterStatus, val request: (SessionAction) -> Unit)

val LocalSessionActions = staticCompositionLocalOf<SessionActionsHost?> { null }

@androidx.annotation.StringRes
fun SessionAction.labelRes(): Int = when (this) {
    SessionAction.CLOUD_LOGOUT -> R.string.session_cloud_logout
    SessionAction.ROUTER_LOGOUT -> R.string.session_router_logout
    SessionAction.EXIT -> R.string.session_exit_app
}

fun SessionAction.icon(): ImageVector = when (this) {
    SessionAction.CLOUD_LOGOUT -> Icons.Default.CloudOff
    SessionAction.ROUTER_LOGOUT -> Icons.Default.LinkOff
    SessionAction.EXIT -> Icons.Default.PowerSettingsNew
}

/** الخروج من الراوتر ممكن فقط إن كان مهيّأً ولم يُسجَّل الخروج منه أصلاً. */
fun RouterStatus.canLogout(): Boolean = isConfigured && !loggedOut

/** الأزرار الثلاثة المستقلة بتصميم موحّد (تُستعمل في نهاية صفحة الإعدادات). */
@Composable
fun SessionActionButtons(host: SessionActionsHost, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
        Text(
            stringResource(R.string.session_section_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        SessionAction.values().forEach { action ->
            val enabled = action != SessionAction.ROUTER_LOGOUT || host.routerStatus.canLogout()
            OutlinedButton(
                onClick = { host.request(action) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(action.icon(), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(action.labelRes()), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            if (!enabled) {
                Text(
                    stringResource(R.string.session_router_logout_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** نافذتا التأكيد والتقدّم؛ تُركَّب مرة واحدة على مستوى التطبيق فتعمل من الموضعين. */
@Composable
fun SessionActionDialogs(state: SessionActionsUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    state.running?.let { action ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(action.labelRes()), fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.session_running))
                }
            },
            confirmButton = {}
        )
    }

    val action = state.pending ?: return
    val impact = state.impact
    val blocked = action != SessionAction.EXIT && (impact?.activeGenerationRuns ?: 0) > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(action.labelRes()), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                Text(
                    stringResource(
                        when (action) {
                            SessionAction.CLOUD_LOGOUT -> R.string.session_cloud_confirm_body
                            SessionAction.ROUTER_LOGOUT -> R.string.session_router_confirm_body
                            SessionAction.EXIT -> R.string.session_exit_confirm_body
                        }
                    )
                )
                when {
                    state.impactLoading -> Text(stringResource(R.string.session_impact_loading), style = MaterialTheme.typography.bodySmall)
                    state.impactFailed -> Text(
                        stringResource(R.string.session_impact_failed),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                    )
                    impact != null -> {
                        if (blocked) {
                            Text(
                                stringResource(R.string.session_generation_active_fmt, impact.activeGenerationRuns),
                                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            if (impact.activeGenerationRuns > 0) {
                                Text(stringResource(R.string.session_exit_generation_note_fmt, impact.activeGenerationRuns), style = MaterialTheme.typography.bodySmall)
                            }
                            if (impact.pendingCommands > 0) {
                                Text(stringResource(R.string.session_pending_kept_fmt, impact.pendingCommands), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!blocked) {
                Button(
                    onClick = onConfirm,
                    enabled = !state.impactLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.session_continue)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(if (blocked) R.string.common_close else R.string.common_cancel)) }
        }
    )
}
