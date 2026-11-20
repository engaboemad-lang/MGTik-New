package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.domain.model.FinancialDashboard
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.ui.util.labelRes
import ps.mgtik.hotspot.domain.model.Order
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.viewmodel.MgtikUiState
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.components.cards.MgtikKpiCard
import ps.mgtik.hotspot.ui.components.BillingPeriodSelector

/**
 * S7_BillingDashboardScreen: لوحة الفوترة والتحصيل المالي والاشتراكات
 * تم التدقيق هندسياً:
 * 1. توحيد المكونات عبر استخدام MgtikKpiCard المطور.
 * 2. تحسين الهيكل البصري واتساق الألوان.
 */
@Composable
fun S7_BillingDashboardScreen(
    state: MgtikUiState,
    successMessageResId: Int? = null,
    errorMessageResId: Int? = null,
    onMessageShown: () -> Unit = {},
    onSelectInvoice: (Invoice) -> Unit = {},
    onSelectOrder: (Order) -> Unit = {},
    /** null = إنشاء الاشتراك غير متاح من هذه الشاشة فيُخفى الزر (لا زر بلا وظيفة). */
    onOpenCreateSubscription: (() -> Unit)? = null,
    onSelectSubscription: (Subscription) -> Unit = {},
    onPeriodSelected: (ps.mgtik.hotspot.domain.report.BillingPeriod) -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val messageResId = successMessageResId ?: errorMessageResId
    val messageText = messageResId?.let { stringResource(it) }
    LaunchedEffect(messageResId) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onMessageShown()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
    S7_BillingDashboardContent(
        stats = state.financialDashboard,
        period = state.billingPeriod,
        periodRange = state.billingPeriodRange,
        summary = state.periodSummary,
        onPeriodSelected = onPeriodSelected,
        financialReady = state.financialLoad == ps.mgtik.hotspot.ui.viewmodel.DataLoad.READY,
        isLoading = !state.isInitialSyncChecked,
        orders = state.orders,
        invoices = state.invoices,
        subscriptions = state.subscriptions,
        onSelectInvoice = onSelectInvoice,
        onSelectOrder = onSelectOrder,
        onOpenCreateSubscription = onOpenCreateSubscription,
        onSelectSubscription = onSelectSubscription
    )
    SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun S7_BillingDashboardContent(
    stats: FinancialDashboard,
    period: ps.mgtik.hotspot.domain.report.BillingPeriod,
    periodRange: Pair<String, String>?,
    summary: ps.mgtik.hotspot.domain.usecase.BillingRules.PeriodSummary?,
    onPeriodSelected: (ps.mgtik.hotspot.domain.report.BillingPeriod) -> Unit,
    financialReady: Boolean,
    isLoading: Boolean,
    orders: List<Order>,
    invoices: List<Invoice>,
    subscriptions: List<Subscription>,
    onSelectInvoice: (Invoice) -> Unit,
    onSelectOrder: (Order) -> Unit,
    onOpenCreateSubscription: (() -> Unit)?,
    onSelectSubscription: (Subscription) -> Unit
) {
    var selectedSubTab by remember { mutableStateOf("invoices") } 
    var searchQuery by remember { mutableStateOf("") }
    val extendedColors = LocalMgtikExtendedColors.current
    // عملة الشركة الفعلية (لا ₪ الافتراضية في النموذج)؛ والأرقام لا تُعرض أصفاراً قبل اكتمال التحميل
    val currency = LocalCurrencySymbol.current
    fun money(v: Long) = if (financialReady) "${formatMoney(v)} $currency" else "—"

    // قائمة الفواتير تتبع الفترة المختارة نفسها، فيطابق ما تراه مجاميع البطاقات (قابلية تتبّع أي مبلغ إلى فواتيره)
    val periodInvoices = remember(invoices, periodRange) {
        if (periodRange == null) invoices
        else invoices.filter { ps.mgtik.hotspot.core.util.isWithinDateRange(it.createdAt, periodRange.first, periodRange.second) }
    }
    val filteredInvoices = remember(periodInvoices, searchQuery) {
        if (searchQuery.isBlank()) periodInvoices
        else periodInvoices.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
            it.invoiceNumber.contains(searchQuery, ignoreCase = true) ||
            it.businessId.contains(searchQuery, ignoreCase = true) ||
            it.ownerNameSnapshot.contains(searchQuery, ignoreCase = true)
        }
    }

    // رقم الفاتورة المقروء لكل طلب (بدل المعرّف الخام)
    val invoiceNumberByOrder = remember(invoices) { invoices.filter { it.orderId.isNotBlank() }.associate { it.orderId to it.invoiceNumber } }

    val filteredOrders = remember(orders, searchQuery, invoiceNumberByOrder) {
        if (searchQuery.isBlank()) orders
        else orders.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
            invoiceNumberByOrder[it.id].orEmpty().contains(searchQuery, ignoreCase = true) ||
            it.ownerNameSnapshot.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredSubscriptions = remember(subscriptions, searchQuery) {
        if (searchQuery.isBlank()) subscriptions
        else subscriptions.filter {
            it.customerUsername.contains(searchQuery, ignoreCase = true) ||
            it.profileName.contains(searchQuery, ignoreCase = true) ||
            it.id.contains(searchQuery, ignoreCase = true)
        }
    }

    fun periodMoney(v: Long?) = if (summary != null && v != null) "${formatMoney(v)} $currency" else "—"

    // صفحة واحدة قابلة للتمرير: الملخص والتبويبات والقائمة معاً، فلا تضيق القائمة على الشاشات الصغيرة
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Dimens.ScreenHorizontalPadding, vertical = Dimens.ScreenVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
    ) {
        item {
            // Header with Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.billing_dashboard_title),
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.billing_dashboard_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selectedSubTab == "subscriptions" && onOpenCreateSubscription != null) {
                    Button(onClick = onOpenCreateSubscription, shape = RoundedCornerShape(Dimens.RadiusMD)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(Dimens.IconSizeSmall))
                        Spacer(modifier = Modifier.width(Dimens.SpaceXS))
                        Text(stringResource(R.string.billing_btn_new_subscription), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                BillingPeriodSelector(period = period, range = periodRange, onSelect = onPeriodSelected)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                    MgtikKpiCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.billing_label_total_invoiced),
                        value = periodMoney(summary?.invoicedMinor),
                        color = MaterialTheme.colorScheme.primary
                    )
                    MgtikKpiCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.billing_label_total_paid),
                        value = periodMoney(summary?.collectedMinor),
                        color = extendedColors.success
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                    MgtikKpiCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.rk_totalRefundedMinor),
                        value = periodMoney(summary?.refundedMinor),
                        color = MaterialTheme.colorScheme.secondary
                    )
                    MgtikKpiCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.billing_label_subs_collected),
                        value = periodMoney(summary?.subscriptionsCollectedMinor),
                        color = extendedColors.success
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                    MgtikKpiCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.billing_label_total_outstanding),
                        value = money(stats.totalOutstandingMinor),
                        color = MaterialTheme.colorScheme.error
                    )
                    MgtikKpiCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.billing_label_subs_outstanding),
                        value = money(stats.subscriptionsOutstandingMinor),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    stringResource(R.string.billing_snapshot_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                TabRow(
                    selectedTabIndex = when (selectedSubTab) {
                        "invoices" -> 0
                        "orders" -> 1
                        else -> 2
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                ) {
                    Tab(selected = selectedSubTab == "invoices", onClick = { selectedSubTab = "invoices" }, 
                        text = { Text(stringResource(R.string.billing_tab_invoices, periodInvoices.size), fontWeight = FontWeight.Bold) })
                    Tab(selected = selectedSubTab == "orders", onClick = { selectedSubTab = "orders" }, 
                        text = { Text(stringResource(R.string.billing_tab_orders, orders.size), fontWeight = FontWeight.Bold) })
                    Tab(selected = selectedSubTab == "subscriptions", onClick = { selectedSubTab = "subscriptions" }, 
                        text = { Text(stringResource(R.string.billing_tab_subscriptions, subscriptions.size), fontWeight = FontWeight.Bold) })
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.billing_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimens.RadiusMD),
                    singleLine = true
                )
            }
        }
        when (selectedSubTab) {
            "invoices" -> {
                if (filteredInvoices.isEmpty() && isLoading) {
                    item { Box(Modifier.fillMaxWidth().padding(Dimens.SpaceLG), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else if (filteredInvoices.isEmpty()) {
                    item { EmptyState(if (searchQuery.isNotEmpty()) stringResource(R.string.billing_no_invoices_search) else stringResource(R.string.billing_no_invoices)) }
                } else {
                    items(filteredInvoices, key = { it.id }) { inv -> InvoiceCard(inv, extendedColors.success, onSelectInvoice) }
                }
            }
            "orders" -> {
                if (filteredOrders.isEmpty() && isLoading) {
                    item { Box(Modifier.fillMaxWidth().padding(Dimens.SpaceLG), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else if (filteredOrders.isEmpty()) {
                    item { EmptyState(if (searchQuery.isNotEmpty()) stringResource(R.string.billing_no_orders_search) else stringResource(R.string.billing_no_orders)) }
                } else {
                    items(filteredOrders, key = { it.id }) { ord -> OrderCard(ord, invoiceNumberByOrder[ord.id].orEmpty(), onSelectOrder) }
                }
            }
            "subscriptions" -> {
                if (filteredSubscriptions.isEmpty() && isLoading) {
                    item { Box(Modifier.fillMaxWidth().padding(Dimens.SpaceLG), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else if (filteredSubscriptions.isEmpty()) {
                    item { EmptyState(if (searchQuery.isNotEmpty()) stringResource(R.string.billing_no_subscriptions_search) else stringResource(R.string.billing_no_subscriptions)) }
                } else {
                    items(filteredSubscriptions, key = { it.id }) { sub -> SubscriptionCard(sub) { onSelectSubscription(sub) } }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.SpaceMD))
}

@Composable
private fun InvoiceCard(inv: Invoice, successColor: androidx.compose.ui.graphics.Color, onSelect: (Invoice) -> Unit) {
    Card(
        onClick = { onSelect(inv) },
        shape = RoundedCornerShape(Dimens.RadiusMD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceLG), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                // رقم الفاتورة المقروء لا معرّف المستند الخام
                Text(text = inv.invoiceNumber.ifBlank { inv.businessId }.ifBlank { stringResource(R.string.inv_no_number) }, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Text(text = inv.ownerNameSnapshot, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "${formatMoney(inv.totalMinor)} ${inv.currency}", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                val statusColor = when (inv.status) {
                    InvoiceStatus.PAID -> successColor
                    InvoiceStatus.PARTIALLY_PAID -> LocalMgtikExtendedColors.current.warning
                    InvoiceStatus.CANCELED, InvoiceStatus.DRAFT -> MaterialTheme.colorScheme.outline
                    InvoiceStatus.DUE -> MaterialTheme.colorScheme.error
                }
                Text(text = stringResource(inv.status.labelRes()),
                     style = MaterialTheme.typography.labelSmall, color = statusColor, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OrderCard(ord: Order, invoiceNumber: String, onSelect: (Order) -> Unit) {
    Card(
        onClick = { onSelect(ord) },
        shape = RoundedCornerShape(Dimens.RadiusMD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceLG), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(text = invoiceNumber.ifBlank { ord.id.takeLast(8) }, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Text(text = ord.ownerNameSnapshot, style = MaterialTheme.typography.bodySmall)
            }
            Text(text = stringResource(R.string.billing_label_vouchers_count, ord.totalCards), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SubscriptionCard(sub: Subscription, onClick: () -> Unit) {
    val currency = LocalCurrencySymbol.current
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(Dimens.RadiusMD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = sub.customerName.ifBlank { sub.customerUsername }, fontWeight = FontWeight.Black)
                    Text(text = "${sub.customerUsername} · ${sub.profileName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(text = "${formatMoney(if (sub.finalPriceMinor > 0L) sub.finalPriceMinor else sub.priceMinor)} $currency", fontWeight = FontWeight.Bold)
            }
            SubscriptionBadges(sub)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(R.string.sub_card_expires, sub.expiresAt.take(10)), style = MaterialTheme.typography.bodySmall)
                if (sub.balanceMinor > 0) {
                    Text(
                        text = stringResource(R.string.sub_card_balance, formatMoney(sub.balanceMinor), currency),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
