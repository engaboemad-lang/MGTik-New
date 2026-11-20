package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.annotation.StringRes
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.subscription.SubscriptionBilling
import ps.mgtik.hotspot.domain.subscription.SubscriptionRules
import ps.mgtik.hotspot.domain.usecase.OwnerInputValidator
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.viewmodel.SubscriptionsUiState
import java.time.Duration
import java.time.Instant
import java.util.Locale

// ───────────────────── مكوّنات مشتركة (S7 يستعملها أيضاً) ─────────────────────

@StringRes
internal fun subscriptionStatusRes(status: SubscriptionStatus): Int = when (status) {
    SubscriptionStatus.DRAFT -> R.string.sub_status_draft
    SubscriptionStatus.SCHEDULED -> R.string.sub_status_scheduled
    SubscriptionStatus.ACTIVE -> R.string.sub_status_active
    SubscriptionStatus.EXPIRED -> R.string.sub_status_expired
    SubscriptionStatus.SUSPENDED -> R.string.sub_status_suspended
    SubscriptionStatus.CANCELLED -> R.string.sub_status_cancelled
}

@StringRes
private fun opRes(op: SubscriptionOp): Int = when (op) {
    SubscriptionOp.ACTIVATE -> R.string.sub_op_activate
    SubscriptionOp.RENEW -> R.string.sub_op_renew
    SubscriptionOp.EXPIRE -> R.string.sub_op_expire
    SubscriptionOp.SUSPEND -> R.string.sub_op_suspend
    SubscriptionOp.RESUME -> R.string.sub_op_resume
    SubscriptionOp.CANCEL -> R.string.sub_op_cancel
}

@Composable
private fun Pill(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(Dimens.RadiusSM), color = container) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            modifier = Modifier.padding(horizontal = Dimens.SpaceSM, vertical = Dimens.SpaceXXS)
        )
    }
}

/** الحالة الإدارية + حالة التنفيذ على الراوتر + تنبيهات (قرب الانتهاء/تعارض)، كلٌّ بشارة مستقلة. */
@Composable
internal fun SubscriptionBadges(sub: Subscription, now: Instant = Instant.now()) {
    val colors = MaterialTheme.colorScheme
    val ext = LocalMgtikExtendedColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS), verticalAlignment = Alignment.CenterVertically) {
        val statusColors = when (sub.status) {
            SubscriptionStatus.ACTIVE -> ext.success.copy(alpha = 0.18f) to ext.success
            SubscriptionStatus.SCHEDULED -> colors.secondaryContainer to colors.onSecondaryContainer
            SubscriptionStatus.SUSPENDED -> colors.tertiaryContainer to colors.onTertiaryContainer
            SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED -> colors.errorContainer to colors.onErrorContainer
            SubscriptionStatus.DRAFT -> colors.surfaceVariant to colors.onSurfaceVariant
        }
        Pill(stringResource(subscriptionStatusRes(sub.status)), statusColors.first, statusColors.second)
        if (sub.execution != SubscriptionExecution.SYNCED) {
            val failed = sub.execution == SubscriptionExecution.FAILED
            Pill(
                stringResource(if (failed) R.string.sub_exec_failed else R.string.sub_exec_pending),
                if (failed) colors.errorContainer else ext.warning.copy(alpha = 0.2f),
                if (failed) colors.onErrorContainer else ext.warning
            )
        }
        if (SubscriptionRules.hasConflict(sub)) {
            Pill(stringResource(R.string.sub_badge_conflict), colors.error, colors.onError)
        } else if (SubscriptionRules.isExpiringSoon(sub, now)) {
            Pill(stringResource(R.string.sub_badge_expiring_soon), ext.warning.copy(alpha = 0.2f), ext.warning)
        }
    }
}

private fun shortDate(iso: String): String = if (iso.length >= 10) iso.take(10) else iso

private fun shortDateTime(iso: String): String = if (iso.length >= 16) iso.take(16).replace('T', ' ') else iso

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.SpaceMD))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private data class SubAction(
    @StringRes val label: Int,
    val icon: ImageVector,
    val primary: Boolean,
    val onClick: () -> Unit
)

// ───────────────────── تفاصيل الاشتراك ─────────────────────

/**
 * تفاصيل الاشتراك: الحالة الإدارية والتنفيذية مفصولتان، تنبيه التعارض والأمر العالق، التواريخ والمدة، المالية المستقلة،
 * والعمليات (تجديد/إيقاف/استئناف/إلغاء/إعادة مزامنة/فحص الراوتر) مع فواتير ومدفوعات وسجل أوامر الراوتر.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S27_SubscriptionDetailScreen(
    subscription: Subscription?,
    state: SubscriptionsUiState,
    onRenew: () -> Unit,
    onSuspend: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetrySync: () -> Unit,
    onRefreshRouter: () -> Unit,
    onRecordPayment: (invoiceId: String, amountMinor: Long, method: String) -> Unit,
    onVoidPayment: (paymentId: String) -> Unit,
    onMessageShown: () -> Unit,
    onBack: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currency = LocalCurrencySymbol.current
    val message = state.successMessageResId ?: state.warningMessageResId ?: state.errorMessageResId
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(context.getString(message))
            onMessageShown()
        }
    }

    var tab by rememberSaveable { mutableStateOf(0) }
    var showRenew by remember { mutableStateOf(false) }
    var showSuspend by remember { mutableStateOf(false) }
    var showCancel by remember { mutableStateOf(false) }
    var payTarget by remember { mutableStateOf<SubscriptionInvoice?>(null) }
    var voidTarget by remember { mutableStateOf<SubscriptionPayment?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = {
                    Text(
                        subscription?.let { it.customerName.ifBlank { it.customerUsername } } ?: stringResource(R.string.sub_detail_title),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
                }
            )
        }
    ) { padding ->
        if (subscription == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val busy = state.processingId != null
        val sub = subscription
        val now = remember(sub.expiresAt, sub.status) { Instant.now() }
        val conflict = SubscriptionRules.hasConflict(sub)
        val remainingDays = SubscriptionRules.parse(sub.expiresAt)?.let { Duration.between(now, it).toDays() }
        val ext = LocalMgtikExtendedColors.current

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            contentPadding = PaddingValues(bottom = Dimens.SpaceXXL)
        ) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            // ── الترويسة
            item {
                Card(shape = RoundedCornerShape(Dimens.RadiusLG), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(Dimens.SpaceLG).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                            OwnerAvatar(sub.customerName.ifBlank { sub.customerUsername }, 52.dp)
                            Column(Modifier.weight(1f)) {
                                Text(sub.customerName.ifBlank { sub.customerUsername }, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                                Text("${stringResource(R.string.sub_label_username)}: ${sub.customerUsername}", style = MaterialTheme.typography.bodySmall)
                                if (sub.customerPhone.isNotBlank()) Text(sub.customerPhone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        SubscriptionBadges(sub, now)
                    }
                }
            }

            // ── تنبيه التعارض: إداري يخالف التشغيلي
            if (conflict) {
                item {
                    val adminLabel = stringResource(subscriptionStatusRes(sub.status))
                    val routerLabel = stringResource(if (sub.routerObservedDisabled == true) R.string.sub_router_disabled else R.string.sub_router_enabled)
                    Banner(
                        title = stringResource(R.string.sub_banner_conflict_title),
                        body = stringResource(R.string.sub_banner_conflict_body, adminLabel, routerLabel),
                        isError = true,
                        actionLabel = stringResource(R.string.sub_btn_retry_sync),
                        enabled = !busy,
                        onAction = onRetrySync
                    )
                }
            }
            // ── أمر راوتر عالق/فاشل
            if (sub.execution != SubscriptionExecution.SYNCED) {
                item {
                    val opLabel = sub.pendingOp?.let { stringResource(opRes(it)) } ?: ""
                    Banner(
                        title = stringResource(R.string.sub_banner_pending_title, opLabel),
                        body = sub.lastSyncError.ifBlank { stringResource(R.string.sub_msg_router_pending) },
                        isError = sub.execution == SubscriptionExecution.FAILED,
                        actionLabel = stringResource(R.string.sub_btn_retry_sync),
                        enabled = !busy,
                        onAction = onRetrySync
                    )
                }
            }

            // ── المعلومات
            item {
                Card(shape = RoundedCornerShape(Dimens.RadiusMD), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                        InfoRow(stringResource(R.string.sub_label_profile), sub.profileName)
                        // السعر ومصدره كما ثُبِّتا وقت الإنشاء (لا يتغيران بتعديل أسعار الفئة لاحقاً)
                        ps.mgtik.hotspot.ui.components.profile.priceSourceLabelRes(sub.priceSource)?.let { srcRes ->
                            InfoRow(
                                stringResource(R.string.sub_detail_price_source),
                                "${formatMoney(sub.priceMinor)} ${sub.priceCurrency.ifBlank { currency }} · " + stringResource(srcRes)
                            )
                        }
                        InfoRow(
                            stringResource(R.string.sub_label_period),
                            "${sub.periodCount} " + stringResource(if (sub.periodUnit == PeriodUnit.MONTHS) R.string.sub_form_period_unit_months else R.string.sub_form_period_unit_days) +
                                " · " + stringResource(R.string.sub_label_period_number, sub.periodNumber)
                        )
                        InfoRow(stringResource(R.string.sub_label_starts), shortDateTime(sub.startDate))
                        InfoRow(stringResource(R.string.sub_label_expires), shortDateTime(sub.expiresAt))
                        if (remainingDays != null && sub.status == SubscriptionStatus.ACTIVE) {
                            InfoRow(stringResource(R.string.sub_label_remaining), stringResource(R.string.sub_label_remaining_days, remainingDays.coerceAtLeast(0)))
                        }
                        if (sub.activatedAt.isNotBlank()) InfoRow(stringResource(R.string.sub_label_activated), shortDateTime(sub.activatedAt))
                        InfoRow(stringResource(R.string.sub_label_auto_renew), stringResource(if (sub.autoRenewal) R.string.sub_yes else R.string.sub_no))
                        InfoRow(
                            stringResource(R.string.sub_label_uptime_limit),
                            sub.limitUptimeSeconds?.let { stringResource(R.string.sub_limit_uptime_hours, it / 3600) } ?: stringResource(R.string.sub_label_unlimited)
                        )
                        InfoRow(
                            stringResource(R.string.sub_label_data_limit),
                            sub.limitTransferBytes?.let { stringResource(R.string.sub_limit_data_gb, String.format(Locale.US, "%.1f", it / 1_073_741_824.0)) }
                                ?: stringResource(R.string.sub_label_unlimited)
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        InfoRow(
                            stringResource(R.string.sub_label_router_state),
                            when (sub.routerObservedDisabled) {
                                true -> stringResource(R.string.sub_router_disabled)
                                false -> stringResource(R.string.sub_router_enabled)
                                null -> stringResource(R.string.sub_router_unknown)
                            }
                        )
                        if (sub.lastSyncAt.isNotBlank()) InfoRow(stringResource(R.string.sub_label_last_sync), shortDateTime(sub.lastSyncAt))
                        if (sub.uptimeUsed.isNotBlank()) InfoRow(stringResource(R.string.sub_label_uptime_used), sub.uptimeUsed)
                        if (sub.notes.isNotBlank()) InfoRow(stringResource(R.string.sub_form_notes), sub.notes)
                    }
                }
            }

            // ── المالية (مستقلة عن الطلبات والملاك)
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    OwnerStatTile(stringResource(R.string.sub_label_invoiced), "${formatMoney(sub.totalInvoicedMinor)} $currency", Modifier.weight(1f))
                    OwnerStatTile(stringResource(R.string.sub_label_paid), "${formatMoney(sub.totalPaidMinor)} $currency", Modifier.weight(1f), ext.success)
                    OwnerStatTile(
                        stringResource(R.string.sub_label_balance), "${formatMoney(sub.balanceMinor)} $currency", Modifier.weight(1f),
                        if (sub.balanceMinor > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // ── العمليات
            item {
                val actions = buildList<SubAction> {
                    if (sub.status == SubscriptionStatus.ACTIVE || sub.status == SubscriptionStatus.EXPIRED) add(SubAction(R.string.sub_btn_renew, Icons.Default.Autorenew, true) { showRenew = true })
                    if (sub.status == SubscriptionStatus.ACTIVE) add(SubAction(R.string.sub_btn_suspend, Icons.Default.PauseCircle, false) { showSuspend = true })
                    if (sub.status == SubscriptionStatus.SUSPENDED) add(SubAction(R.string.sub_btn_resume, Icons.Default.PlayCircle, true, onResume))
                    if (sub.status != SubscriptionStatus.CANCELLED) add(SubAction(R.string.sub_btn_check_router, Icons.Default.Sync, false, onRefreshRouter))
                    if (sub.status != SubscriptionStatus.CANCELLED) add(SubAction(R.string.sub_btn_cancel, Icons.Default.Cancel, false) { showCancel = true })
                }
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    actions.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), modifier = Modifier.fillMaxWidth()) {
                            pair.forEach { a ->
                                val m = Modifier.weight(1f).height(48.dp)
                                val body: @Composable RowScope.() -> Unit = {
                                    Icon(a.icon, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(Dimens.SpaceXS))
                                    Text(stringResource(a.label), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (a.primary) Button(onClick = a.onClick, enabled = !busy, modifier = m, shape = RoundedCornerShape(Dimens.RadiusMD), content = body)
                                else OutlinedButton(onClick = a.onClick, enabled = !busy, modifier = m, shape = RoundedCornerShape(Dimens.RadiusMD), content = body)
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── التبويبات
            item {
                TabRow(selectedTabIndex = tab) {
                    listOf(R.string.sub_tab_invoices, R.string.sub_tab_payments, R.string.sub_tab_ops).forEachIndexed { i, res ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res), fontWeight = FontWeight.Bold) })
                    }
                }
            }

            when (tab) {
                0 -> if (state.invoices.isEmpty()) item { EmptyLine(R.string.sub_empty_invoices) } else items(state.invoices, key = { it.id }) { inv ->
                    val overdue = SubscriptionBilling.isOverdue(inv.dueDate, inv.balanceMinor, now)
                    Card(shape = RoundedCornerShape(Dimens.RadiusMD), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(Dimens.SpaceMD).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.sub_invoice_period, inv.periodNumber), fontWeight = FontWeight.Bold)
                                Text("${formatMoney(inv.totalMinor)} $currency", fontWeight = FontWeight.Black)
                            }
                            Text("${shortDate(inv.periodStart)} → ${shortDate(inv.periodEnd)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                val (txt, color) = when (inv.payStatus) {
                                    SubscriptionPayStatus.PAID -> R.string.sub_invoice_status_paid to ext.success
                                    SubscriptionPayStatus.PARTIAL -> R.string.sub_invoice_status_partial to ext.warning
                                    SubscriptionPayStatus.UNPAID -> R.string.sub_invoice_status_unpaid to MaterialTheme.colorScheme.error
                                }
                                Text(
                                    stringResource(txt) + if (overdue) " · " + stringResource(R.string.sub_invoice_overdue) else "",
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color
                                )
                                if (inv.balanceMinor > 0) {
                                    TextButton(onClick = { payTarget = inv }, enabled = !busy) { Text(stringResource(R.string.sub_btn_record_payment)) }
                                }
                            }
                            if (inv.paidMinor > 0 && inv.balanceMinor > 0) {
                                Text(
                                    "${stringResource(R.string.sub_label_balance)}: ${formatMoney(inv.balanceMinor)} $currency",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                1 -> if (state.payments.isEmpty()) item { EmptyLine(R.string.sub_empty_payments) } else items(state.payments, key = { it.id }) { p ->
                    Card(shape = RoundedCornerShape(Dimens.RadiusMD), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(Dimens.SpaceMD).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${formatMoney(p.amountMinor)} $currency", fontWeight = FontWeight.Black)
                                Text(
                                    (if (p.method == "transfer") stringResource(R.string.owner_method_transfer) else stringResource(R.string.owner_method_cash)) + " · " + shortDate(p.createdAt),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (p.isVoid) Text(stringResource(R.string.owner_payment_status_void), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else TextButton(onClick = { voidTarget = p }, enabled = !busy) {
                                Text(stringResource(R.string.sub_btn_void_payment), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                else -> if (state.operations.isEmpty()) item { EmptyLine(R.string.sub_empty_ops) } else items(state.operations, key = { it.id }) { op ->
                    Card(shape = RoundedCornerShape(Dimens.RadiusMD), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(Dimens.SpaceMD).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(opRes(op.op)), fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(
                                        when (op.status) {
                                            "DONE" -> R.string.sub_opstatus_done
                                            "FAILED" -> R.string.sub_opstatus_failed
                                            else -> R.string.sub_opstatus_pending
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                    color = when (op.status) { "DONE" -> ext.success; "FAILED" -> MaterialTheme.colorScheme.error; else -> ext.warning }
                                )
                            }
                            Text(
                                "${shortDateTime(op.requestedAt)} · ${stringResource(R.string.sub_op_attempts, op.attempts)}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (op.lastError.isNotBlank()) Text(op.lastError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // ── الحوارات
        if (showRenew) {
            val net = SubscriptionBilling.netMinor(sub.priceMinor, sub.discountMinor)
            AlertDialog(
                onDismissRequest = { showRenew = false },
                title = { Text(stringResource(R.string.sub_dialog_renew_title), fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.sub_dialog_renew_message, formatMoney(net), currency)) },
                confirmButton = { Button(onClick = { showRenew = false; onRenew() }) { Text(stringResource(R.string.sub_btn_renew)) } },
                dismissButton = { TextButton(onClick = { showRenew = false }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
        if (showSuspend) {
            AlertDialog(
                onDismissRequest = { showSuspend = false },
                title = { Text(stringResource(R.string.sub_dialog_suspend_title), fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.sub_dialog_suspend_message)) },
                confirmButton = { Button(onClick = { showSuspend = false; onSuspend() }) { Text(stringResource(R.string.sub_btn_suspend)) } },
                dismissButton = { TextButton(onClick = { showSuspend = false }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
        if (showCancel) {
            AlertDialog(
                onDismissRequest = { showCancel = false },
                title = { Text(stringResource(R.string.sub_dialog_cancel_title), fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.sub_dialog_cancel_message)) },
                confirmButton = {
                    Button(onClick = { showCancel = false; onCancel() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.sub_btn_cancel))
                    }
                },
                dismissButton = { TextButton(onClick = { showCancel = false }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
        payTarget?.let { inv ->
            SubscriptionPaymentDialog(
                balanceMinor = inv.balanceMinor,
                currency = currency,
                onDismiss = { payTarget = null },
                onConfirm = { amount, method -> payTarget = null; onRecordPayment(inv.id, amount, method) }
            )
        }
        voidTarget?.let { p ->
            AlertDialog(
                onDismissRequest = { voidTarget = null },
                title = { Text(stringResource(R.string.sub_dialog_void_title), fontWeight = FontWeight.Bold) },
                text = { Text(stringResource(R.string.sub_dialog_void_message, formatMoney(p.amountMinor), currency)) },
                confirmButton = {
                    Button(onClick = { voidTarget = null; onVoidPayment(p.id) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.sub_btn_void_payment))
                    }
                },
                dismissButton = { TextButton(onClick = { voidTarget = null }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
    }
}

@Composable
private fun EmptyLine(@StringRes res: Int) {
    Box(Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXL), contentAlignment = Alignment.Center) {
        Text(stringResource(res), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Banner(title: String, body: String, isError: Boolean, actionLabel: String, enabled: Boolean, onAction: () -> Unit) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Card(shape = RoundedCornerShape(Dimens.RadiusMD), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(Dimens.SpaceLG).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
            Text(title, fontWeight = FontWeight.Bold, color = content)
            Text(body, style = MaterialTheme.typography.bodySmall, color = content)
            TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel, color = content, fontWeight = FontWeight.Bold) }
        }
    }
}

/** دفعة على فاتورة اشتراك: لا تتجاوز رصيد الفاتورة (لا رصيد دائن في نظام الاشتراكات). */
@Composable
private fun SubscriptionPaymentDialog(balanceMinor: Long, currency: String, onDismiss: () -> Unit, onConfirm: (Long, String) -> Unit) {
    var amountStr by remember { mutableStateOf(OwnerInputValidator.minorToInput(balanceMinor)) }
    var method by remember { mutableStateOf("cash") }
    val parsed = OwnerInputValidator.parseAmountMinor(amountStr)
    val exceeds = parsed != null && parsed > balanceMinor
    val valid = parsed != null && !exceeds
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sub_dialog_payment_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Text(stringResource(R.string.sub_dialog_payment_balance, formatMoney(balanceMinor), currency), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { new -> if (new.all { it.isDigit() || it == '.' } && new.count { it == '.' } <= 1) amountStr = new },
                    label = { Text(stringResource(R.string.owner_dialog_amount_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    singleLine = true,
                    isError = amountStr.isNotBlank() && !valid,
                    supportingText = {
                        when {
                            exceeds -> Text(stringResource(R.string.sub_error_payment_exceeds))
                            amountStr.isNotBlank() && parsed == null -> Text(stringResource(R.string.owner_error_amount_invalid))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    FilterChip(selected = method == "cash", onClick = { method = "cash" }, label = { Text(stringResource(R.string.owner_method_cash)) })
                    FilterChip(selected = method == "transfer", onClick = { method = "transfer" }, label = { Text(stringResource(R.string.owner_method_transfer)) })
                }
            }
        },
        confirmButton = { Button(onClick = { parsed?.let { onConfirm(it, method) } }, enabled = valid) { Text(stringResource(R.string.owner_btn_confirm_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_back)) } }
    )
}
