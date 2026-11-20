package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import android.content.ActivityNotFoundException
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.core.util.toSortableIso
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.usecase.OwnerCreditRules
import ps.mgtik.hotspot.domain.usecase.OwnerInputValidator
import ps.mgtik.hotspot.domain.usecase.OwnerStatement
import ps.mgtik.hotspot.domain.usecase.OwnerPriceBook
import ps.mgtik.hotspot.domain.model.SaleContext
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import java.util.Date

// ───────────────────────── مكوّنات مشتركة (يستعملها الدليل S12 أيضاً) ─────────────────────────

@Composable
internal fun OwnerAvatar(name: String, size: Dp = 48.dp) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "?",
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** شارة الحالة: لا شيء للفعّال، «موقوف» و«مؤرشف» بلونين مميزين. */
@Composable
internal fun OwnerStatusBadge(status: OwnerStatus) {
    if (status == OwnerStatus.ACTIVE) return
    val (container, content, label) = if (status == OwnerStatus.SUSPENDED) {
        Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, R.string.owner_status_suspended)
    } else {
        Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, R.string.owner_status_archived)
    }
    Surface(shape = RoundedCornerShape(Dimens.RadiusSM), color = container) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            modifier = Modifier.padding(horizontal = Dimens.SpaceSM, vertical = Dimens.SpaceXXS)
        )
    }
}

@Composable
internal fun OwnerStatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.RadiusMD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(Dimens.SpaceMD).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class OwnerAction(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val primary: Boolean = false,
    val onClick: () -> Unit
)

private fun launchSafely(context: Context, intent: Intent, failureMessage: String) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
    }
}

// ───────────────────────── الشاشة 14: مكتب المالك التشغيلي ─────────────────────────

/**
 * الشاشة 14: ملف المالك التشغيلي (§4.18) — بيانات التواصل، الرصيد والمستحق، كل العمليات المالية (إيداع، تحصيل، تسوية من الرصيد،
 * استرجاع، افتتاحي)، التوليد للمالك، التعديل/الإيقاف/الأرشفة/الاستعادة، مشاركة الكشف، وتبويبات الفواتير والدفعات والدفتر.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S14_OwnerDetailScreen(
    workspace: OwnerWorkspace,
    priceBook: OwnerPriceBook? = null,
    isSaving: Boolean,
    successMessageResId: Int?,
    errorMessageResId: Int?,
    onMessageShown: () -> Unit,
    onSetOpeningBalance: (Long) -> Unit,
    onRecordAdvance: (Long, String?) -> Unit,
    onRefund: (Long) -> Unit,
    onSettleFromCredit: () -> Unit,
    onCollect: (Long, String) -> Unit,
    onVoidPayment: (String) -> Unit,
    onSetStatus: (OwnerStatus) -> Unit,
    onEdit: () -> Unit,
    onGenerate: () -> Unit,
    onOpenInvoice: (String) -> Unit,
    onArchive: () -> Unit,
    onBack: () -> Unit
) {
    val account = workspace.account
    val isArchived = account.status == OwnerStatus.ARCHIVED
    val isSuspended = account.status == OwnerStatus.SUSPENDED
    val credit = account.availableCreditMinor
    val payableDues = remember(workspace.invoices) { OwnerStatement.payableDues(workspace.invoices) }
    val dueMinor = payableDues.sumOf { it.amountDueMinor }
    val extendedColors = LocalMgtikExtendedColors.current
    val currency = LocalCurrencySymbol.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var tab by rememberSaveable { mutableStateOf(0) }
    var showAdvance by remember { mutableStateOf(false) }
    var showOpening by remember { mutableStateOf(false) }
    var showRefund by remember { mutableStateOf(false) }
    var refundToConfirm by remember { mutableStateOf<Long?>(null) }
    var showSettle by remember { mutableStateOf(false) }
    var showCollect by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var showSuspend by remember { mutableStateOf(false) }
    var showStatement by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var voidTarget by remember { mutableStateOf<Payment?>(null) }

    LaunchedEffect(successMessageResId, errorMessageResId) {
        val message = successMessageResId ?: errorMessageResId
        if (message != null) {
            snackbarHostState.showSnackbar(context.getString(message))
            onMessageShown()
        }
    }

    // نصوص الكشف المشارَك تُقرأ في التركيب ثم تُستعمل عند الضغط
    val labels = OwnerStatement.Labels(
        title = stringResource(R.string.owner_statement_title),
        owner = stringResource(R.string.owner_statement_owner),
        phone = stringResource(R.string.owner_statement_phone),
        period = stringResource(R.string.owner_statement_period),
        invoices = stringResource(R.string.owner_statement_invoices),
        invoiced = stringResource(R.string.owner_statement_invoiced),
        paid = stringResource(R.string.owner_statement_paid),
        due = stringResource(R.string.owner_statement_due),
        creditIn = stringResource(R.string.owner_statement_credit_in),
        creditOut = stringResource(R.string.owner_statement_credit_out),
        balance = stringResource(R.string.owner_statement_balance),
        currency = currency
    )
    val periodLabels = listOf(
        stringResource(R.string.owner_statement_period_all),
        stringResource(R.string.owner_statement_period_30),
        stringResource(R.string.owner_statement_period_7)
    )
    val noAppMessage = stringResource(R.string.owner_no_phone_app)

    val shareStatement = { periodIndex: Int ->
        val days = when (periodIndex) { 1 -> 30L; 2 -> 7L; else -> 0L }
        val from = if (days == 0L) null else Date(System.currentTimeMillis() - days * 86_400_000L).toSortableIso()
        val summary = OwnerStatement.summarize(workspace.invoices, workspace.recentLedger, from)
        val text = OwnerStatement.toText(account.name, account.phone, credit, periodLabels[periodIndex], summary, labels)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, null))
    }

    val actions = buildList<OwnerAction> {
        if (!isArchived) {
            add(OwnerAction(R.string.owner_btn_deposit, Icons.Default.AddCard, primary = true) { showAdvance = true })
            if (dueMinor > 0) add(OwnerAction(R.string.owner_btn_collect, Icons.Default.Payments) { showCollect = true })
            if (credit > 0 && dueMinor > 0) add(OwnerAction(R.string.owner_btn_settle_credit, Icons.Default.AccountBalanceWallet) { showSettle = true })
            if (credit > 0) add(OwnerAction(R.string.owner_btn_refund, Icons.Default.Replay) { showRefund = true })
            if (account.openingBalanceSetAt.isBlank()) add(OwnerAction(R.string.owner_btn_opening_balance, Icons.Default.Flag) { showOpening = true })
            if (!isSuspended) add(OwnerAction(R.string.owner_btn_generate, Icons.Default.ConfirmationNumber) { onGenerate() })
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MgtikTopAppBar(
                title = { Text(account.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showStatement = true }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.owner_btn_share_statement))
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.owner_more_actions))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (!isArchived) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.owner_btn_edit)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { showMenu = false; onEdit() }
                                )
                                if (isSuspended) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.owner_btn_activate)) },
                                        leadingIcon = { Icon(Icons.Default.PlayCircle, null) },
                                        onClick = { showMenu = false; onSetStatus(OwnerStatus.ACTIVE) }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.owner_btn_suspend)) },
                                        leadingIcon = { Icon(Icons.Default.PauseCircle, null) },
                                        onClick = { showMenu = false; showSuspend = true }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.owner_btn_archive), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Archive, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { showMenu = false; showArchive = true }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.owner_btn_restore)) },
                                    leadingIcon = { Icon(Icons.Default.Unarchive, null) },
                                    onClick = { showMenu = false; onSetStatus(OwnerStatus.ACTIVE) }
                                )
                            }
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
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            contentPadding = PaddingValues(bottom = Dimens.SpaceXXL)
        ) {
            if (isSaving) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }

            // ── الترويسة: الهوية + التواصل + الأرقام الرئيسية
            item {
                Card(
                    shape = RoundedCornerShape(Dimens.RadiusLG),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f))
                ) {
                    Column(
                        modifier = Modifier.padding(Dimens.SpaceLG).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                            OwnerAvatar(account.name, 56.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(account.name, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(account.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OwnerStatusBadge(account.status)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    val number = account.phone.filter { it.isDigit() || it == '+' }
                                    launchSafely(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")), noAppMessage)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(Dimens.RadiusSM)
                            ) {
                                Icon(Icons.Default.Call, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(Dimens.SpaceXS))
                                Text(stringResource(R.string.owner_btn_call), style = MaterialTheme.typography.labelMedium)
                            }
                            val whatsapp = OwnerInputValidator.toInternationalDigits(account.phone)
                            OutlinedButton(
                                onClick = { launchSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$whatsapp")), noAppMessage) },
                                enabled = whatsapp.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(Dimens.RadiusSM)
                            ) {
                                Icon(Icons.Default.Chat, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(Dimens.SpaceXS))
                                Text(stringResource(R.string.owner_btn_whatsapp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), modifier = Modifier.fillMaxWidth()) {
                            OwnerStatTile(
                                stringResource(R.string.owner_label_available_credit), "${formatMoney(credit)} $currency", Modifier.weight(1f),
                                if (credit < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            OwnerStatTile(
                                stringResource(R.string.owner_label_total_due), "${formatMoney(dueMinor)} $currency", Modifier.weight(1f),
                                if (dueMinor > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            OwnerStatTile(
                                stringResource(R.string.owner_label_invoices_count), "${account.outstandingInvoicesCount}", Modifier.weight(1f),
                                if (account.outstandingInvoicesCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        // مبيعات الوكيل كما في فواتيره (غير الملغاة/المسودات): فوترة ومحصّل وعدد
                        val sales = remember(workspace.invoices) { OwnerStatement.summarize(workspace.invoices, emptyList(), null) }
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), modifier = Modifier.fillMaxWidth()) {
                            OwnerStatTile(stringResource(R.string.owner_label_invoiced_total), "${formatMoney(sales.invoicedMinor)} $currency", Modifier.weight(1f), MaterialTheme.colorScheme.onSurface)
                            OwnerStatTile(stringResource(R.string.owner_label_paid_total), "${formatMoney(sales.paidMinor)} $currency", Modifier.weight(1f), extendedColors.success)
                            OwnerStatTile(stringResource(R.string.owner_label_all_invoices_count), "${sales.invoicesCount}", Modifier.weight(1f), MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            // ── العمليات
            if (actions.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                        actions.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM), modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { a ->
                                    val m = Modifier.weight(1f).height(48.dp)
                                    val body: @Composable RowScope.() -> Unit = {
                                        Icon(a.icon, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(Dimens.SpaceXS))
                                        Text(stringResource(a.labelRes), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (a.primary) {
                                        Button(onClick = a.onClick, enabled = !isSaving, modifier = m, shape = RoundedCornerShape(Dimens.RadiusMD), content = body)
                                    } else {
                                        OutlinedButton(onClick = a.onClick, enabled = !isSaving, modifier = m, shape = RoundedCornerShape(Dimens.RadiusMD), content = body)
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // ── التبويبات
            item {
                TabRow(selectedTabIndex = tab) {
                    listOf(R.string.owner_tab_invoices, R.string.owner_tab_payments, R.string.owner_tab_ledger, R.string.owner_tab_prices).forEachIndexed { i, res ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res), fontWeight = FontWeight.Bold) })
                    }
                }
            }

            when (tab) {
                0 -> {
                    if (workspace.invoices.isEmpty()) {
                        item { EmptyHint(R.string.owner_empty_invoices) }
                    } else {
                        items(workspace.invoices, key = { it.id }) { invoice ->
                            Card(
                                onClick = { onOpenInvoice(invoice.id) },
                                shape = RoundedCornerShape(Dimens.RadiusMD),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(Dimens.SpaceMD).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(invoice.invoiceNumber.ifBlank { invoice.id.take(8) }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(invoice.createdAt.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("${formatMoney(invoice.totalMinor)} $currency", fontWeight = FontWeight.Black)
                                        if (invoice.amountDueMinor > 0 && invoice.status != InvoiceStatus.CANCELED) {
                                            Text(
                                                "${stringResource(R.string.owner_label_due)} ${formatMoney(invoice.amountDueMinor)} $currency",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        val (badgeColor, badgeText) = when (invoice.status) {
                                            InvoiceStatus.PAID -> extendedColors.success to R.string.owner_invoice_paid
                                            InvoiceStatus.PARTIALLY_PAID -> extendedColors.warning to R.string.owner_invoice_partial
                                            InvoiceStatus.CANCELED -> MaterialTheme.colorScheme.outline to R.string.owner_payment_status_canceled
                                            else -> MaterialTheme.colorScheme.error to R.string.owner_invoice_due
                                        }
                                        Text(stringResource(badgeText), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = badgeColor)
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (workspace.payments.isEmpty()) {
                        item { EmptyHint(R.string.owner_empty_payments) }
                    } else {
                        items(workspace.payments, key = { it.id }) { payment ->
                            Card(
                                shape = RoundedCornerShape(Dimens.RadiusMD),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(Dimens.SpaceMD).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${formatMoney(payment.amountMinor)} $currency", fontWeight = FontWeight.Black)
                                        val methodLabel = when (payment.method.lowercase()) {
                                            "cash" -> stringResource(R.string.owner_method_cash)
                                            "transfer" -> stringResource(R.string.owner_method_transfer)
                                            OwnerCreditRules.METHOD_CREDIT -> stringResource(R.string.owner_method_credit)
                                            else -> payment.method
                                        }
                                        Text(
                                            "$methodLabel · ${payment.createdAt.take(10)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (payment.status == PaymentStatus.ACTIVE) {
                                        TextButton(onClick = { voidTarget = payment }, enabled = !isSaving) {
                                            Text(stringResource(R.string.owner_btn_void), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                        }
                                    } else {
                                        Text(
                                            stringResource(
                                                when (payment.status) {
                                                    PaymentStatus.REFUNDED -> R.string.owner_payment_status_refunded
                                                    PaymentStatus.CANCELED -> R.string.owner_payment_status_canceled
                                                    else -> R.string.owner_payment_status_void
                                                }
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    if (workspace.recentLedger.isEmpty()) {
                        item { EmptyHint(R.string.owner_empty_ledger) }
                    } else {
                        items(workspace.recentLedger, key = { it.ledgerEntryId }) { entry ->
                            Card(
                                shape = RoundedCornerShape(Dimens.RadiusMD),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(Dimens.SpaceMD).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        val typeText = when (entry.type) {
                                            OwnerLedgerType.ADVANCE -> stringResource(R.string.owner_btn_deposit)
                                            OwnerLedgerType.REFUND_FROM_CREDIT -> stringResource(R.string.owner_ledger_type_refund)
                                            OwnerLedgerType.INVOICE_CHARGE -> stringResource(R.string.owner_ledger_type_invoice_charge)
                                            OwnerLedgerType.OPENING_BALANCE -> stringResource(R.string.owner_ledger_type_opening_balance)
                                            OwnerLedgerType.CHARGE_REVERSAL -> stringResource(R.string.owner_ledger_type_charge_reversal)
                                        }
                                        Text(typeText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        Text(entry.createdAt.take(16).replace('T', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (entry.note.isNotBlank()) {
                                            Text(entry.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                        }
                                    }
                                    val isPositive = entry.amountMinor >= 0
                                    Text(
                                        text = "${if (isPositive) "+" else ""}${formatMoney(entry.amountMinor)} $currency",
                                        color = if (isPositive) extendedColors.success else MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Black,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    if (priceBook == null) {
                        item { Box(Modifier.fillMaxWidth().padding(Dimens.SpaceXL), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    } else {
                        item {
                            Text(
                                stringResource(R.string.owner_prices_hint, priceBook.appliedCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (priceBook.rows.isEmpty()) {
                            item { EmptyHint(R.string.owner_prices_empty) }
                        } else {
                            items(priceBook.rows, key = { it.ruleId }) { row ->
                                Card(
                                    shape = RoundedCornerShape(Dimens.RadiusMD),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(Dimens.SpaceMD).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                row.categoryName.ifBlank { stringResource(R.string.owner_prices_legacy) },
                                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            val ctx = stringResource(if (row.context == SaleContext.WHOLESALE) R.string.owner_prices_ctx_wholesale else R.string.owner_prices_ctx_retail)
                                            val base = row.baseMinor?.let { "${formatMoney(it)} $currency" } ?: stringResource(R.string.owner_prices_base_unknown)
                                            Text(
                                                stringResource(R.string.owner_prices_row_sub, ctx, base),
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                "${formatMoney(row.specialMinor)} $currency",
                                                fontWeight = FontWeight.Black,
                                                color = if (row.applied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                stringResource(if (row.applied) R.string.owner_prices_applied else R.string.owner_prices_disabled),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (row.applied) extendedColors.success else MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ───────────── الحوارات ─────────────

    if (showAdvance) {
        RecordAdvanceDialog(
            onDismiss = { showAdvance = false },
            onConfirm = { amount, notes -> onRecordAdvance(amount, notes); showAdvance = false }
        )
    }
    if (showOpening) {
        RecordOpeningBalanceDialog(
            onDismiss = { showOpening = false },
            onConfirm = { amount -> onSetOpeningBalance(amount); showOpening = false }
        )
    }
    if (showCollect) {
        CollectPaymentDialog(
            totalDueMinor = dueMinor,
            currency = currency,
            onDismiss = { showCollect = false },
            onConfirm = { amount, method -> onCollect(amount, method); showCollect = false }
        )
    }
    if (showSettle) {
        val plan = OwnerCreditRules.allocateOldestFirst(credit, OwnerStatement.toDueInvoices(workspace.invoices))
        val used = plan.sumOf { it.amountMinor }
        AlertDialog(
            onDismissRequest = { showSettle = false },
            title = { Text(stringResource(R.string.owner_dialog_settle_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (plan.isEmpty()) stringResource(R.string.owner_dialog_settle_none)
                    else stringResource(R.string.owner_dialog_settle_message, formatMoney(used), currency, plan.size, formatMoney(credit - used))
                )
            },
            confirmButton = {
                Button(onClick = { showSettle = false; onSettleFromCredit() }, enabled = plan.isNotEmpty()) {
                    Text(stringResource(R.string.owner_btn_confirm_save))
                }
            },
            dismissButton = { TextButton(onClick = { showSettle = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (showRefund) {
        RefundFromCreditDialog(
            availableMinor = credit,
            onDismiss = { showRefund = false },
            onContinue = { amount -> showRefund = false; refundToConfirm = amount }
        )
    }
    refundToConfirm?.let { amount ->
        AlertDialog(
            onDismissRequest = { refundToConfirm = null },
            title = { Text(stringResource(R.string.owner_confirm_refund_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.owner_confirm_refund_message, formatMoney(amount), currency, account.name)) },
            confirmButton = {
                Button(
                    onClick = { refundToConfirm = null; onRefund(amount) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.owner_btn_refund_do)) }
            },
            dismissButton = { TextButton(onClick = { refundToConfirm = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    voidTarget?.let { payment ->
        AlertDialog(
            onDismissRequest = { voidTarget = null },
            title = { Text(stringResource(R.string.owner_dialog_void_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.owner_dialog_void_message, formatMoney(payment.amountMinor), currency)) },
            confirmButton = {
                Button(
                    onClick = { voidTarget = null; onVoidPayment(payment.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.owner_btn_void)) }
            },
            dismissButton = { TextButton(onClick = { voidTarget = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (showSuspend) {
        AlertDialog(
            onDismissRequest = { showSuspend = false },
            title = { Text(stringResource(R.string.owner_confirm_suspend_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.owner_confirm_suspend_message, account.name)) },
            confirmButton = {
                Button(onClick = { showSuspend = false; onSetStatus(OwnerStatus.SUSPENDED) }) { Text(stringResource(R.string.owner_btn_suspend)) }
            },
            dismissButton = { TextButton(onClick = { showSuspend = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (showArchive) {
        AlertDialog(
            onDismissRequest = { showArchive = false },
            title = { Text(stringResource(R.string.owner_confirm_archive_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    Text(stringResource(R.string.owner_confirm_archive_message, account.name))
                    // تنبيهان غير مانعين: الأرشفة لا تُسقط الفواتير ولا الرصيد، لكن المستخدم يجب أن يعلم بهما
                    if (account.outstandingInvoicesCount > 0) {
                        Text(
                            stringResource(R.string.owner_confirm_archive_warning_due, account.outstandingInvoicesCount),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (credit != 0L) {
                        Text(
                            stringResource(R.string.owner_confirm_archive_warning_credit, formatMoney(credit), currency),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showArchive = false; onArchive() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.owner_btn_archive_do)) }
            },
            dismissButton = { TextButton(onClick = { showArchive = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (showStatement) {
        StatementDialog(
            onDismiss = { showStatement = false },
            onShare = { periodIndex -> showStatement = false; shareStatement(periodIndex) }
        )
    }
}

@Composable
private fun EmptyHint(@StringRes resId: Int) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceXL), contentAlignment = Alignment.Center) {
        Text(stringResource(resId), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** اختيار فترة الكشف ثم مشاركته نصاً (واتساب/رسائل/بريد...). */
@Composable
private fun StatementDialog(onDismiss: () -> Unit, onShare: (Int) -> Unit) {
    var period by rememberSaveable { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.owner_dialog_statement_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                listOf(R.string.owner_statement_period_all, R.string.owner_statement_period_30, R.string.owner_statement_period_7)
                    .forEachIndexed { i, res ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { period = i }
                        ) {
                            RadioButton(selected = period == i, onClick = { period = i })
                            Text(stringResource(res))
                        }
                    }
            }
        },
        confirmButton = { Button(onClick = { onShare(period) }) { Text(stringResource(R.string.owner_btn_share_statement)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** تحصيل مبلغ من المالك يُوزَّع على فواتيره المستحقة من الأقدم للأحدث؛ لا يتجاوز إجمالي المستحق. */
@Composable
private fun CollectPaymentDialog(
    totalDueMinor: Long,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit
) {
    var amountStr by remember { mutableStateOf(OwnerInputValidator.minorToInput(totalDueMinor)) }
    var method by remember { mutableStateOf("cash") }
    val parsed = OwnerInputValidator.parseAmountMinor(amountStr)
    val exceeds = parsed != null && parsed > totalDueMinor
    val valid = parsed != null && !exceeds

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.owner_dialog_collect_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                Text(
                    stringResource(R.string.owner_dialog_collect_due, formatMoney(totalDueMinor), currency),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { new ->
                        if (new.all { it.isDigit() || it == '.' } && new.count { it == '.' } <= 1) amountStr = new
                    },
                    label = { Text(stringResource(R.string.owner_dialog_amount_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    singleLine = true,
                    isError = amountStr.isNotBlank() && !valid,
                    supportingText = {
                        when {
                            exceeds -> Text(stringResource(R.string.owner_error_collect_exceeds))
                            amountStr.isNotBlank() && parsed == null -> Text(stringResource(R.string.owner_error_amount_invalid))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
                )
                Text(stringResource(R.string.owner_dialog_method_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
