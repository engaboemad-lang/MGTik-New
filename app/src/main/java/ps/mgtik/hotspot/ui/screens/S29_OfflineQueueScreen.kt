package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.OfflineCommand
import ps.mgtik.hotspot.domain.model.MAX_COMMAND_ATTEMPTS
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import ps.mgtik.hotspot.ui.components.states.MgtikEmptyState
import ps.mgtik.hotspot.ui.theme.Dimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S29 — طابور المزامنة: الأوامر التي لم تُنفَّذ بعد (بانتظار الاتصال) والفاشلة نهائياً.
 * الناجح يُحذف تلقائياً من الطابور، فلا يظهر هنا. الفاشل يمكن إعادته أو تجاهله بتأكيد.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S29_OfflineQueueScreen(
    commands: List<OfflineCommand>,
    onRetry: (OfflineCommand) -> Unit,
    onDiscard: (String) -> Unit,
    onBack: () -> Unit
) {
    var pendingDiscard by remember { mutableStateOf<OfflineCommand?>(null) }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.queue_title))
                        Text(
                            stringResource(R.string.queue_subtitle, commands.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        if (commands.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                MgtikEmptyState(icon = Icons.Default.CloudDone, text = stringResource(R.string.queue_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Dimens.SpaceLG),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
            ) {
                items(commands, key = { it.commandId }) { cmd ->
                    QueueCommandCard(cmd, onRetry = { onRetry(cmd) }, onDiscard = { pendingDiscard = cmd })
                }
            }
        }
    }

    pendingDiscard?.let { cmd ->
        AlertDialog(
            onDismissRequest = { pendingDiscard = null },
            title = { Text(stringResource(R.string.queue_discard_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.queue_discard_body)) },
            confirmButton = {
                TextButton(onClick = { onDiscard(cmd.commandId); pendingDiscard = null }) {
                    Text(stringResource(R.string.queue_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscard = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun QueueCommandCard(cmd: OfflineCommand, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val ltr = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr)
    val time = remember(cmd.createdAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(cmd.createdAt))
    }
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(cmd.type.name, style = ltr, fontWeight = FontWeight.Bold)
                Text(
                    text = if (cmd.isDead) stringResource(R.string.queue_status_failed) else stringResource(R.string.queue_status_waiting),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (cmd.isDead) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                stringResource(R.string.queue_meta, time, cmd.attemptCount, MAX_COMMAND_ATTEMPTS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            cmd.lastError?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.queue_retry)) }
                TextButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.queue_discard), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
