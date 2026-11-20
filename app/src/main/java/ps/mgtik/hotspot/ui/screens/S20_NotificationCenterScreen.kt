package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.layout.*
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ps.mgtik.hotspot.domain.model.MgtikNotification
import ps.mgtik.hotspot.domain.model.NotificationCategory
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.viewmodel.NotificationViewModel

/**
 * S20_NotificationCenterScreen - مركز الإشعارات والتنبيهات الموحد (§20).
 * تم التدقيق الهندسي:
 * 1. دعم تعدد اللغات (AR/EN) عبر Resource Bundle.
 * 2. فصل الاهتمامات عبر NotificationViewModel.
 * 3. معالجة حالات الاستثناء (فارغ/تحميل).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S20_NotificationCenterScreen(
    viewModel: NotificationViewModel,
    onOpenTarget: (type: String, id: String) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirm by remember { mutableStateOf(false) }

    // فشل تعليم/مسح الإشعارات: يُعرض مرة واحدة ثم يُستهلك
    val actionErrorText = state.actionErrorResId?.let { stringResource(it) }
    LaunchedEffect(state.actionErrorResId) {
        actionErrorText?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeActionError()
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.notifications_clear_confirm_title)) },
            text = { Text(stringResource(R.string.notifications_clear_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearAll()
                }) {
                    Text(
                        stringResource(R.string.notifications_clear_confirm_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.notifications_dialog_cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.notifications_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.notifications.isNotEmpty()) {
                        IconButton(onClick = { viewModel.markAllAsRead() }) {
                            Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.notifications_mark_all_read))
                        }
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.notifications_clear_all), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.hasLoadError) {
                NotificationsErrorView(
                    onRetry = { viewModel.retry() },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (state.notifications.isEmpty()) {
                EmptyNotificationsView(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
                ) {
                    items(state.notifications, key = { it.id }) { item ->
                        SwipeToDeleteNotification(
                            item = item,
                            onDelete = { viewModel.deleteNotification(item.id) },
                            onClick = {
                                if (!item.isRead) viewModel.markAsRead(item.id)
                                val type = item.targetType
                                val id = item.targetId
                                if (!type.isNullOrBlank() && !id.isNullOrBlank()) onOpenTarget(type, id)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** سحب أفقي (أي اتجاه) لحذف إشعار واحد؛ الحذف يتم عبر مستمع Firestore فيختفي العنصر تلقائياً. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteNotification(
    item: MgtikNotification,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onDelete()
            // لا نُبقي العنصر مُزاحاً: يعود لمكانه حتى يزيله مستمع Firestore، أو يبقى إن فشل الحذف
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimens.SpaceXL),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    ) {
        NotificationItemCard(item = item, onMarkRead = onClick)
    }
}

@Composable
fun NotificationItemCard(
    item: MgtikNotification,
    onMarkRead: () -> Unit
) {
    val containerColor = if (item.isRead) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) 
                         else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)

    Card(
        onClick = onMarkRead,
        shape = RoundedCornerShape(Dimens.RadiusMD),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Dimens.SpaceLG),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // مؤشر الحالة (نظام / فوترة)
            Surface(
                shape = RoundedCornerShape(Dimens.RadiusSM),
                color = if (item.category == NotificationCategory.SYSTEM) MaterialTheme.colorScheme.tertiaryContainer 
                        else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = when (item.category) {
                        NotificationCategory.SYSTEM -> Icons.Default.NotificationsNone
                        NotificationCategory.BILLING -> Icons.Default.Receipt
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(Dimens.SpaceSM),
                    tint = if (item.category == NotificationCategory.SYSTEM) MaterialTheme.colorScheme.onTertiaryContainer 
                           else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.width(Dimens.SpaceLG))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontWeight = if (item.isRead) FontWeight.Medium else FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = item.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }

            if (!item.isRead) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .padding(start = Dimens.SpaceSM)
                ) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize()) {}
                }
            }
        }
    }
}

@Composable
fun NotificationsErrorView(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Dimens.SpaceXXL),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceLG))
        Text(
            text = stringResource(R.string.notifications_load_error),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceLG))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
fun EmptyNotificationsView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.NotificationsNone,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceLG))
        Text(
            text = stringResource(R.string.notifications_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
