package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import ps.mgtik.hotspot.ui.theme.Dimens
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import ps.mgtik.hotspot.ui.util.tone
import ps.mgtik.hotspot.ui.util.labelRes
import ps.mgtik.hotspot.ui.components.chips.MgtikStatusChip
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import ps.mgtik.hotspot.domain.model.Batch
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.Order
import ps.mgtik.hotspot.domain.model.OrderStatus
import ps.mgtik.hotspot.R

/**
 * S9_OrderDetailScreen: تفاصيل طلب الكروت وسند التوريد (§8)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S9_OrderDetailScreen(
    order: Order,
    batches: List<Batch>,
    invoice: Invoice?,
    owners: List<ps.mgtik.hotspot.domain.model.OwnerAccount>,
    isProcessing: Boolean,
    successMessageResId: Int?,
    errorMessageResId: Int?,
    onMessageShown: () -> Unit,
    onBack: () -> Unit,
    onIssueInvoice: (String) -> Unit,
    onCancelOrder: (String) -> Unit,
    onReopenOrder: (String) -> Unit,
    onRetryBilling: (String) -> Unit,
    onCorrectOwner: (String, String) -> Unit,
    onOpenInvoice: (Invoice) -> Unit,
    onOpenBatch: (Batch) -> Unit
) {
    val totalCards = batches.sumOf { it.successfulQuantity }
    var showCloseConfirm by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }
    var showReopenConfirm by remember { mutableStateOf(false) }
    var showOwnerPicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val messageResId = successMessageResId ?: errorMessageResId
    val messageText = messageResId?.let { stringResource(it) }
    LaunchedEffect(messageResId) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onMessageShown()
        }
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text(stringResource(R.string.order_close_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.order_close_confirm_body)) },
            confirmButton = {
                Button(onClick = { showCloseConfirm = false; onIssueInvoice(order.id) }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { showCloseConfirm = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text(stringResource(R.string.order_cancel_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.order_cancel_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = { showCancelConfirm = false; onCancelOrder(order.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showCancelConfirm = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    if (showReopenConfirm) {
        AlertDialog(
            onDismissRequest = { showReopenConfirm = false },
            title = { Text(stringResource(R.string.order_reopen_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.order_reopen_confirm_body)) },
            confirmButton = { Button(onClick = { showReopenConfirm = false; onReopenOrder(order.id) }) { Text(stringResource(R.string.common_confirm)) } },
            dismissButton = { TextButton(onClick = { showReopenConfirm = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    if (showOwnerPicker) {
        AlertDialog(
            onDismissRequest = { showOwnerPicker = false },
            title = { Text(stringResource(R.string.order_pick_owner_title), fontWeight = FontWeight.Bold) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(owners.filter { it.ownerId != order.ownerId && it.status == ps.mgtik.hotspot.domain.model.OwnerStatus.ACTIVE }, key = { it.ownerId }) { o ->
                        TextButton(
                            onClick = { showOwnerPicker = false; onCorrectOwner(order.id, o.ownerId) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(o.name, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showOwnerPicker = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.order_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { scaffoldPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .padding(Dimens.SpaceLG),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLG)
    ) {
        Card(
            shape = RoundedCornerShape(Dimens.RadiusLG),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = order.businessId.ifBlank { stringResource(R.string.order_no_number) },
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    MgtikStatusChip(text = stringResource(order.status.labelRes()), tone = order.status.tone())
                }
                Text(order.ownerNameSnapshot, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                
                DetailRow(
                    label = stringResource(R.string.order_label_total_successful),
                    value = stringResource(R.string.common_unit_cards, totalCards)
                )
                DetailRow(
                    label = stringResource(R.string.order_label_total_amount),
                    value = "${formatMoney(order.totalAmountMinor)} ${LocalCurrencySymbol.current}",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (invoice == null) {
            when (order.status) {
                OrderStatus.DRAFT, OrderStatus.REOPENED -> Button(
                    onClick = { showCloseConfirm = true },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Receipt, contentDescription = null)
                        Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                        Text(
                            text = stringResource(R.string.order_action_issue_invoice),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                // الطلب مغلق والفاتورة لم تصل بعد من السحابة: إعادة محاولة الإصدار أو إعادة فتح الطلب
                OrderStatus.CLOSED -> {
                    Text(
                        text = stringResource(R.string.order_invoice_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { onRetryBilling(order.id) },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    ) { Text(stringResource(R.string.order_action_retry_billing)) }
                    OutlinedButton(
                        onClick = { showReopenConfirm = true },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Dimens.RadiusMD)
                    ) { Text(stringResource(R.string.order_action_reopen)) }
                }
                OrderStatus.CANCELLED -> Unit
            }
            // تصحيح المالك والإلغاء قبل الإغلاق فقط (بعده تُنشأ فاتورة مرتبطة بالمالك الحالي)
            if (order.status == OrderStatus.DRAFT || order.status == OrderStatus.REOPENED) {
                OutlinedButton(
                    onClick = { showOwnerPicker = true },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD)
                ) { Text(stringResource(R.string.order_action_change_owner)) }
                OutlinedButton(
                    onClick = { showCancelConfirm = true },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.order_action_cancel)) }
            }
        } else {
            OutlinedCard(
                onClick = { onOpenInvoice(invoice) },
                shape = RoundedCornerShape(Dimens.RadiusMD),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.order_invoice_label, invoice.invoiceNumber.ifBlank { invoice.businessId }.ifBlank { stringResource(R.string.inv_no_number) }),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            MgtikStatusChip(text = stringResource(invoice.status.labelRes()), tone = invoice.status.tone())
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }

        Text(
            text = stringResource(R.string.order_linked_batches, batches.size),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            items(batches, key = { it.id }) { batch ->
                Card(
                    onClick = { onOpenBatch(batch) },
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(batch.businessId, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(batch.profileNameSnapshot, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = stringResource(R.string.common_unit_cards, batch.successfulQuantity),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun DetailRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
    }
}
