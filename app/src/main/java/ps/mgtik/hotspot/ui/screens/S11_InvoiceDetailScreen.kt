package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import ps.mgtik.hotspot.ui.util.tone
import ps.mgtik.hotspot.ui.components.chips.MgtikStatusChip
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.domain.usecase.OwnerInputValidator
import ps.mgtik.hotspot.ui.util.labelRes

/**
 * الشاشة 11: تفاصيل الفاتورة وإدارة التحصيل (§8, §4.16, §4.17).
 * كل الأرقام من الفاتورة المحفوظة ودفعاتها الفعلية؛ لا أسعار حالية ولا تقديرات.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S11_InvoiceDetailScreen(
    invoice: Invoice,
    payments: List<Payment>,
    events: List<InvoiceEventLog> = emptyList(),
    pdf: ps.mgtik.hotspot.ui.viewmodel.PdfUiState,
    isProcessing: Boolean,
    successMessageResId: Int?,
    errorMessageResId: Int?,
    onMessageShown: () -> Unit,
    onRecordPayment: (Long, String) -> Unit,
    onApplyCredit: (Long) -> Unit,
    onApplyDiscount: (String, Long) -> Unit,
    onCancelInvoice: (Boolean, String?) -> Unit,
    onReopen: () -> Unit,
    onVoidPayment: (Payment, String) -> Unit,
    onRefundPayment: (Payment, Long) -> Unit,
    onSetDueDate: (Long?) -> Unit,
    onPreparePdf: () -> Unit,
    onClearPdf: () -> Unit,
    onBack: () -> Unit
) {
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showCreditDialog by remember { mutableStateOf(false) }
    var showDiscountDialog by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }
    var showDueDatePicker by remember { mutableStateOf(false) }
    var voidTarget by remember { mutableStateOf<Payment?>(null) }
    var refundTarget by remember { mutableStateOf<Payment?>(null) }
    val extendedColors = LocalMgtikExtendedColors.current
    val context = LocalContext.current
    val cancelReason = stringResource(R.string.owner_cancel_admin_reason)
    val snackbarHostState = remember { SnackbarHostState() }
    val today = remember { java.time.LocalDate.now().toString() }
    val overdue = remember(invoice, today) { ps.mgtik.hotspot.domain.usecase.BillingRules.isOverdue(invoice, today) }
    val currency = invoice.currency
    val remaining = ps.mgtik.hotspot.domain.usecase.BillingRules.outstandingMinor(invoice)
    val discountMinor = maxOf(invoice.subtotalMinor - invoice.totalMinor, 0L)

    // نتيجة العملية المالية (نجاح/فشل) + أخطاء/نتائج PDF
    val messageResId = successMessageResId ?: errorMessageResId
    val messageText = messageResId?.let { stringResource(it) }
    LaunchedEffect(messageResId) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onMessageShown()
        }
    }
    val pdfErrorText = pdf.errorResId?.let { stringResource(it) }
    LaunchedEffect(pdf.errorResId) {
        if (pdfErrorText != null) {
            snackbarHostState.showSnackbar(pdfErrorText)
            onClearPdf()
        }
    }
    val savedOk = stringResource(R.string.inv_pdf_saved)
    val savedFail = stringResource(R.string.inv_pdf_save_failed)
    val printUnavailable = stringResource(R.string.inv_print_unavailable)
    val jobName = stringResource(R.string.inv_pdf_job_name, invoice.invoiceNumber.ifBlank { invoice.businessId })
    var localMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(localMessage) { localMessage?.let { snackbarHostState.showSnackbar(it); localMessage = null } }
    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val f = pdf.file
        if (uri != null && f != null) localMessage = if (ps.mgtik.hotspot.ui.util.ShareFiles.copyTo(context, f, uri)) savedOk else savedFail
    }

    pdf.file?.let { file ->
        ps.mgtik.hotspot.ui.components.PdfPreviewDialog(
            file = file,
            onDismiss = onClearPdf,
            onShare = { ps.mgtik.hotspot.ui.util.ShareFiles.share(context, file, "application/pdf") },
            onSave = { saveLauncher.launch(file.name) },
            onPrint = { if (!ps.mgtik.hotspot.ui.util.InvoicePrint.print(context, file, jobName)) localMessage = printUnavailable }
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text(stringResource(R.string.invoice_cancel_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.invoice_cancel_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = { showCancelConfirm = false; onCancelInvoice(false, cancelReason) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showCancelConfirm = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.owner_title_manage_invoice)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (pdf.isGenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Dimens.SpaceMD))
                    } else {
                        IconButton(onClick = onPreparePdf) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = stringResource(R.string.inv_action_preview_pdf))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(Dimens.RadiusLG),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = invoice.invoiceNumber.ifBlank { invoice.businessId }.ifBlank { stringResource(R.string.inv_no_number) },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (overdue) MgtikStatusChip(stringResource(R.string.inv_status_overdue), ps.mgtik.hotspot.ui.components.chips.StatusTone.ERROR)
                                MgtikStatusChip(stringResource(invoice.status.labelRes()), invoice.status.tone())
                            }
                        }
                        Text(invoice.ownerNameSnapshot.ifBlank { "—" }, fontWeight = FontWeight.Bold)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val issue = ps.mgtik.hotspot.ui.util.InvoiceTexts.dateOnly(invoice.issueDate.ifBlank { invoice.createdAt })
                        if (issue.isNotEmpty()) DetailRow(stringResource(R.string.inv_label_issue_date), issue)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.inv_label_due_date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val due = ps.mgtik.hotspot.ui.util.InvoiceTexts.dateOnly(invoice.dueDate)
                            if (due.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(due, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = if (overdue) MaterialTheme.colorScheme.error else Color.Unspecified)
                                    if (invoice.status.acceptsPayment) {
                                        IconButton(onClick = { showDueDatePicker = true }, enabled = !isProcessing, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.inv_action_set_due_date), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            } else if (invoice.status.acceptsPayment) {
                                TextButton(onClick = { showDueDatePicker = true }, enabled = !isProcessing) { Text(stringResource(R.string.inv_action_set_due_date)) }
                            } else {
                                Text(stringResource(R.string.inv_due_not_set), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        ps.mgtik.hotspot.ui.util.InvoiceTexts.displayNotes(context, invoice.notes)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(Dimens.RadiusLG),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                        if (discountMinor > 0) {
                            DetailRow(stringResource(R.string.owner_label_subtotal), "${formatMoney(invoice.subtotalMinor)} $currency")
                            DetailRow(stringResource(R.string.owner_label_discounts), "- ${formatMoney(discountMinor)} $currency")
                        }
                        DetailRow(stringResource(R.string.owner_label_total_required), "${formatMoney(invoice.totalMinor)} $currency", isBold = true)
                        if (invoice.totalMinor > 0 && invoice.status != InvoiceStatus.CANCELED) {
                            LinearProgressIndicator(
                                progress = { (invoice.amountPaidMinor.toFloat() / invoice.totalMinor).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = extendedColors.success,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                        DetailRow(stringResource(R.string.owner_label_paid_amount), "${formatMoney(invoice.amountPaidMinor)} $currency", color = extendedColors.success)
                        DetailRow(
                            stringResource(R.string.owner_label_remaining_amount),
                            "${formatMoney(remaining)} $currency",
                            color = if (remaining > 0) MaterialTheme.colorScheme.error else extendedColors.success, isBold = true
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    if (invoice.status.acceptsPayment) {
                        Button(
                            onClick = { showPaymentDialog = true },
                            enabled = !isProcessing,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(Dimens.RadiusMD),
                            colors = ButtonDefaults.buttonColors(containerColor = extendedColors.success)
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Default.AddCard, contentDescription = null)
                                Spacer(modifier = Modifier.width(Dimens.SpaceSM))
                                Text(stringResource(R.string.owner_btn_record_payment), fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                            if (invoice.ownerId.isNotBlank()) {
                                OutlinedButton(onClick = { showCreditDialog = true }, enabled = !isProcessing, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusMD)) {
                                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.owner_btn_pay_from_credit), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            OutlinedButton(onClick = { showDiscountDialog = true }, enabled = !isProcessing, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Dimens.RadiusMD)) {
                                Icon(Icons.Default.LocalOffer, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.inv_action_discount), maxLines = 1)
                            }
                        }
                    }
                    if (invoice.status == InvoiceStatus.CANCELED) {
                        OutlinedButton(onClick = onReopen, enabled = !isProcessing, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD)) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.inv_action_reopen))
                        }
                    }
                    if (invoice.status.isCancellable) {
                        TextButton(onClick = { showCancelConfirm = true }, enabled = !isProcessing, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.inv_action_cancel_invoice), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            item {
                Text(stringResource(R.string.inv_payments_title, payments.size), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
            }
            if (payments.isEmpty()) {
                item { Text(stringResource(R.string.inv_payments_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            } else {
                items(payments, key = { it.id }) { p ->
                    InvoicePaymentRow(
                        payment = p, currency = currency, enabled = !isProcessing,
                        onVoid = { voidTarget = p }, onRefund = { refundTarget = p }
                    )
                }
            }

            // سجل التدقيق المالي: كل عملية (دفعة/إبطال/استرداد/خصم/إلغاء...) بوقتها ومنفّذها
            item {
                Text(stringResource(R.string.inv_events_title), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
            }
            if (events.isEmpty()) {
                item { Text(stringResource(R.string.inv_events_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            } else {
                items(events, key = { it.eventId }) { e -> InvoiceEventRow(e, currency) }
            }
        }
    }

    if (showCreditDialog) {
        ApplyCreditDialog(
            amountDueMinor = remaining,
            onDismiss = { showCreditDialog = false },
            onConfirm = { amount -> onApplyCredit(amount); showCreditDialog = false }
        )
    }
    if (showPaymentDialog) {
        RecordPaymentDialog(
            amountDueMinor = remaining,
            onDismiss = { showPaymentDialog = false },
            onConfirm = { amount, method -> onRecordPayment(amount, method); showPaymentDialog = false }
        )
    }
    if (showDiscountDialog) {
        InvoiceDiscountDialog(
            subtotalMinor = invoice.subtotalMinor,
            paidMinor = invoice.amountPaidMinor,
            onDismiss = { showDiscountDialog = false },
            onConfirm = { type, value -> onApplyDiscount(type, value); showDiscountDialog = false }
        )
    }
    voidTarget?.let { p ->
        ReasonDialog(
            title = stringResource(R.string.inv_void_title),
            body = stringResource(R.string.inv_void_body, "${formatMoney(p.amountMinor)} $currency"),
            onDismiss = { voidTarget = null },
            onConfirm = { reason -> onVoidPayment(p, reason); voidTarget = null }
        )
    }
    refundTarget?.let { p ->
        RefundPaymentDialog(
            maxMinor = p.amountMinor,
            onDismiss = { refundTarget = null },
            onConfirm = { amount -> onRefundPayment(p, amount); refundTarget = null }
        )
    }
    if (showDueDatePicker) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDueDatePicker = false },
            confirmButton = {
                TextButton(onClick = { pickerState.selectedDateMillis?.let(onSetDueDate); showDueDatePicker = false }, enabled = pickerState.selectedDateMillis != null) {
                    Text(stringResource(R.string.owner_btn_confirm_save))
                }
            },
            dismissButton = {
                Row {
                    if (invoice.dueDate != null) TextButton(onClick = { onSetDueDate(null); showDueDatePicker = false }) { Text(stringResource(R.string.inv_action_clear_due_date)) }
                    TextButton(onClick = { showDueDatePicker = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun InvoiceEventRow(event: InvoiceEventLog, currency: String) {
    val label = when (event.eventType) {
        "PAYMENT_RECORDED" -> stringResource(R.string.inv_event_PAYMENT_RECORDED)
        "CREDIT_APPLIED" -> stringResource(R.string.inv_event_CREDIT_APPLIED)
        "PAYMENT_VOIDED" -> stringResource(R.string.inv_event_PAYMENT_VOIDED)
        "PAYMENT_REFUNDED" -> stringResource(R.string.inv_event_PAYMENT_REFUNDED)
        "INVOICE_CANCELED" -> stringResource(R.string.inv_event_INVOICE_CANCELED)
        "INVOICE_REOPENED" -> stringResource(R.string.inv_event_INVOICE_REOPENED)
        "DISCOUNT_APPLIED" -> stringResource(R.string.inv_event_DISCOUNT_APPLIED)
        "INVOICE_UPDATED" -> stringResource(R.string.inv_event_INVOICE_UPDATED)
        else -> event.eventType
    }
    val amount = (event.detail["amountMinor"] ?: event.detail["refundMinor"])?.toLongOrNull()?.let { "${formatMoney(it)} $currency" }
    val extra = listOfNotNull(amount, event.detail["reason"]?.takeIf { it.isNotBlank() }).joinToString(" · ")
    val whenText = runCatching {
        java.time.Instant.parse(event.occurredAt).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault(event.occurredAt.take(16).replace('T', ' '))
    Card(
        shape = RoundedCornerShape(Dimens.RadiusMD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.SpaceMD), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(whenText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(Dimens.RadiusSM)) {
        Row(
            modifier = Modifier.padding(horizontal = Dimens.SpaceSM, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun InvoicePaymentRow(payment: Payment, currency: String, enabled: Boolean, onVoid: () -> Unit, onRefund: () -> Unit) {
    val extended = LocalMgtikExtendedColors.current
    val inactive = payment.status != PaymentStatus.ACTIVE
    val statusColor = when (payment.status) {
        PaymentStatus.ACTIVE -> extended.success
        PaymentStatus.REFUNDED -> extended.warning
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        shape = RoundedCornerShape(Dimens.RadiusMD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(Dimens.SpaceMD), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(ps.mgtik.hotspot.ui.util.InvoiceTexts.methodRes(payment.method)), fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(ps.mgtik.hotspot.ui.util.InvoiceTexts.dateOnly(payment.createdAt).ifEmpty { null }, payment.reference?.takeIf { it.isNotBlank() }).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${formatMoney(payment.amountMinor)} $currency", fontWeight = FontWeight.Black,
                        textDecoration = if (inactive && payment.status != PaymentStatus.REFUNDED) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                    )
                    StatusPill(
                        stringResource(ps.mgtik.hotspot.ui.util.InvoiceTexts.paymentStatusRes(payment.status)) +
                            if (payment.status == PaymentStatus.REFUNDED && payment.refundedMinor > 0) " (${formatMoney(payment.refundedMinor)})" else "",
                        statusColor
                    )
                }
            }
            if (payment.status == PaymentStatus.ACTIVE) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                    TextButton(onClick = onRefund, enabled = enabled) { Text(stringResource(R.string.inv_action_refund)) }
                    TextButton(onClick = onVoid, enabled = enabled) { Text(stringResource(R.string.inv_action_void), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun ReasonDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Text(body)
                OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text(stringResource(R.string.inv_reason_label)) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD))
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(reason.trim()) }, enabled = reason.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_back)) } }
    )
}

@Composable
private fun RefundPaymentDialog(maxMinor: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var amountStr by remember { mutableStateOf(formatMoney(maxMinor)) }
    val parsed = OwnerInputValidator.parseAmountMinor(amountStr)
    val tooMuch = parsed != null && parsed > maxMinor
    val valid = parsed != null && !tooMuch
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inv_refund_title), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = amountStr, onValueChange = { amountStr = it },
                label = { Text(stringResource(R.string.owner_dialog_amount_label)) },
                isError = amountStr.isNotBlank() && !valid,
                supportingText = { if (amountStr.isNotBlank() && !valid) Text(stringResource(if (tooMuch) R.string.inv_refund_exceeds else R.string.owner_error_amount_invalid)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD)
            )
        },
        confirmButton = { Button(onClick = { parsed?.let(onConfirm) }, enabled = valid) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_back)) } }
    )
}

/** خصم على الفاتورة: نسبة (0..100، عدد صحيح) أو مبلغ ثابت؛ القواعد النهائية (لا أقل من المدفوع) تُفرض في الخدمة. */
@Composable
private fun InvoiceDiscountDialog(subtotalMinor: Long, paidMinor: Long, onDismiss: () -> Unit, onConfirm: (String, Long) -> Unit) {
    var percent by remember { mutableStateOf(true) }
    var valueStr by remember { mutableStateOf("") }
    val rules = ps.mgtik.hotspot.domain.usecase.BillingRules
    val value: Long? = if (percent) valueStr.trim().toLongOrNull() else OwnerInputValidator.parseAmountMinor(valueStr)
    val discount = value?.let { rules.discountAmountMinor(if (percent) rules.DISCOUNT_PERCENTAGE else rules.DISCOUNT_FIXED, it, subtotalMinor) }
    val belowPaid = discount != null && subtotalMinor - discount < paidMinor
    val valid = discount != null && !belowPaid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inv_discount_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    FilterChip(selected = percent, onClick = { percent = true; valueStr = "" }, label = { Text(stringResource(R.string.inv_discount_percent)) })
                    FilterChip(selected = !percent, onClick = { percent = false; valueStr = "" }, label = { Text(stringResource(R.string.inv_discount_fixed)) })
                }
                OutlinedTextField(
                    value = valueStr, onValueChange = { valueStr = it }, singleLine = true,
                    label = { Text(stringResource(if (percent) R.string.inv_discount_percent_label else R.string.owner_dialog_amount_label)) },
                    isError = valueStr.isNotBlank() && !valid,
                    supportingText = {
                        when {
                            valueStr.isNotBlank() && discount == null -> Text(stringResource(R.string.inv_discount_invalid))
                            belowPaid -> Text(stringResource(R.string.inv_discount_below_paid))
                            discount != null -> Text(stringResource(R.string.inv_discount_preview, formatMoney(discount), formatMoney(subtotalMinor - discount)))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Dimens.RadiusMD)
                )
            }
        },
        confirmButton = {
            Button(onClick = { value?.let { onConfirm(if (percent) rules.DISCOUNT_PERCENTAGE else rules.DISCOUNT_FIXED, it) } }, enabled = valid) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_back)) } }
    )
}

@Composable
private fun DetailRow(label: String, value: String, color: Color = Color.Unspecified, isBold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isBold) FontWeight.Black else FontWeight.Bold, color = color)
    }
}

/** تسوية الفاتورة من رصيد المالك: الخادم يرفض المبلغ الأكبر من المستحق أو من الرصيد المتاح. */
@Composable
fun ApplyCreditDialog(amountDueMinor: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var amountStr by remember { mutableStateOf(formatMoney(amountDueMinor)) }
    val parsedMinor = OwnerInputValidator.parseAmountMinor(amountStr)
    val exceedsDue = parsedMinor != null && parsedMinor > amountDueMinor
    val isAmountValid = parsedMinor != null && !exceedsDue

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.owner_dialog_pay_from_credit_title), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = amountStr,
                onValueChange = { amountStr = it },
                label = { Text(stringResource(R.string.owner_dialog_amount_label)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                isError = amountStr.isNotBlank() && !isAmountValid,
                supportingText = {
                    if (amountStr.isNotBlank() && !isAmountValid) {
                        Text(stringResource(if (exceedsDue) R.string.owner_error_credit_exceeds_due else R.string.owner_error_amount_invalid))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
            )
        },
        confirmButton = {
            Button(onClick = { parsedMinor?.let(onConfirm) }, enabled = isAmountValid) {
                Text(stringResource(R.string.owner_btn_confirm_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_back)) } }
    )
}

@Composable
fun RecordPaymentDialog(amountDueMinor: Long, onDismiss: () -> Unit, onConfirm: (Long, String) -> Unit) {
    var amountStr by remember { mutableStateOf(formatMoney(amountDueMinor)) }
    var method by remember { mutableStateOf("cash") }
    val parsedMinor = OwnerInputValidator.parseAmountMinor(amountStr)
    val exceedsDue = parsedMinor != null && parsedMinor > amountDueMinor
    val isAmountValid = parsedMinor != null && !exceedsDue

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.owner_dialog_payment_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text(stringResource(R.string.owner_dialog_amount_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    isError = amountStr.isNotBlank() && !isAmountValid,
                    supportingText = {
                        if (amountStr.isNotBlank() && !isAmountValid) {
                            Text(stringResource(if (exceedsDue) R.string.owner_error_credit_exceeds_due else R.string.owner_error_amount_invalid))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                )
                Text(stringResource(R.string.owner_dialog_method_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    FilterChip(
                        selected = method == "cash", 
                        onClick = { method = "cash" }, 
                        label = { Text(stringResource(R.string.owner_method_cash)) }
                    )
                    FilterChip(
                        selected = method == "transfer", 
                        onClick = { method = "transfer" }, 
                        label = { Text(stringResource(R.string.owner_method_transfer)) }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { parsedMinor?.let { onConfirm(it, method) } },
                enabled = isAmountValid
            ) {
                Text(stringResource(R.string.owner_btn_confirm_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_back)) } }
    )
}
