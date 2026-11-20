package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.BorderStroke
import ps.mgtik.hotspot.ui.components.chips.StatusTone
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Description
import ps.mgtik.hotspot.ui.util.tone
import ps.mgtik.hotspot.ui.components.states.MgtikEmptyState
import ps.mgtik.hotspot.ui.components.chips.MgtikStatusChip
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.usecase.BillingRules
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.util.InvoiceTexts
import ps.mgtik.hotspot.ui.util.labelRes

private enum class InvoiceFilter { ALL, OPEN, OVERDUE, PAID, CANCELED }
private enum class InvoiceSort { NEWEST, OLDEST, AMOUNT_DESC, DUE_DESC }

/**
 * S10 - قائمة الفواتير: بحث، تصفية بالحالة (مع «متأخرة» المحسوبة من الاستحقاق)، ترتيب، وحالات تحميل/فراغ.
 * القائمة حيّة (مستمع Firestore) فلا حاجة لزر تحديث يدوي؛ التحميل الأولي يُعرض بمؤشر.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S10_InvoicesListScreen(
    invoices: List<Invoice>,
    isLoading: Boolean = false,
    onSelectInvoice: (Invoice) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(InvoiceFilter.ALL) }
    var sort by remember { mutableStateOf(InvoiceSort.NEWEST) }
    var sortMenu by remember { mutableStateOf(false) }
    val today = remember { java.time.LocalDate.now().toString() }
    val extendedColors = LocalMgtikExtendedColors.current

    val visible = remember(invoices, searchQuery, filter, sort, today) {
        val q = searchQuery.trim()
        invoices
            .filter { inv ->
                when (filter) {
                    InvoiceFilter.ALL -> true
                    InvoiceFilter.OPEN -> inv.status.acceptsPayment
                    InvoiceFilter.OVERDUE -> BillingRules.isOverdue(inv, today)
                    InvoiceFilter.PAID -> inv.status == InvoiceStatus.PAID
                    InvoiceFilter.CANCELED -> inv.status == InvoiceStatus.CANCELED
                }
            }
            .filter { q.isEmpty() || it.invoiceNumber.contains(q, true) || it.ownerNameSnapshot.contains(q, true) || it.businessId.contains(q, true) }
            .let { list ->
                when (sort) {
                    InvoiceSort.NEWEST -> list.sortedByDescending { it.issueDate.ifBlank { it.createdAt } }
                    InvoiceSort.OLDEST -> list.sortedBy { it.issueDate.ifBlank { it.createdAt } }
                    InvoiceSort.AMOUNT_DESC -> list.sortedByDescending { it.totalMinor }
                    InvoiceSort.DUE_DESC -> list.sortedByDescending { BillingRules.outstandingMinor(it) }
                }
            }
    }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.invoices_list_title))
                        Text(stringResource(R.string.invoices_list_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.inv_sort)) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            InvoiceSort.values().forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(when (opt) {
                                        InvoiceSort.NEWEST -> R.string.inv_sort_newest
                                        InvoiceSort.OLDEST -> R.string.inv_sort_oldest
                                        InvoiceSort.AMOUNT_DESC -> R.string.inv_sort_amount
                                        InvoiceSort.DUE_DESC -> R.string.inv_sort_due
                                    }), fontWeight = if (opt == sort) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = { sort = opt; sortMenu = false }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.invoices_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                singleLine = true
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                items(InvoiceFilter.values().toList()) { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(stringResource(when (f) {
                            InvoiceFilter.ALL -> R.string.inv_filter_all
                            InvoiceFilter.OPEN -> R.string.inv_filter_open
                            InvoiceFilter.OVERDUE -> R.string.inv_status_overdue
                            InvoiceFilter.PAID -> R.string.inv_filter_paid
                            InvoiceFilter.CANCELED -> R.string.inv_filter_canceled
                        })) }
                    )
                }
            }
            Text(
                text = stringResource(R.string.invoices_count_summary, visible.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                isLoading && invoices.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                visible.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    MgtikEmptyState(
                        icon = if (invoices.isEmpty()) Icons.Default.Description else Icons.Default.SearchOff,
                        text = stringResource(if (invoices.isEmpty()) R.string.invoices_empty_list else R.string.invoices_empty_search)
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = Dimens.SpaceLG),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)
                ) {
                    items(visible, key = { it.id }) { inv ->
                        InvoiceRowCard(inv, BillingRules.isOverdue(inv, today), onClick = { onSelectInvoice(inv) })
                    }
                }
            }
        }
    }
}

@Composable
private fun InvoiceRowCard(inv: Invoice, overdue: Boolean, onClick: () -> Unit) {
    val remaining = BillingRules.outstandingMinor(inv)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceLG),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)
        ) {
            // الصف الأول: رقم الفاتورة + الشارات (متأخرة تظهر قبل الحالة)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = inv.invoiceNumber.ifBlank { inv.businessId }.ifBlank { stringResource(R.string.inv_no_number) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(Dimens.SpaceSM))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
                    if (overdue) MgtikStatusChip(stringResource(R.string.inv_status_overdue), StatusTone.ERROR)
                    MgtikStatusChip(stringResource(inv.status.labelRes()), inv.status.tone())
                }
            }
            Text(
                text = inv.ownerNameSnapshot.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val issue = InvoiceTexts.dateOnly(inv.issueDate.ifBlank { inv.createdAt })
            if (issue.isNotEmpty()) {
                Text(issue, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // الصف الأخير: المتبقي (للجزئية فقط) + الإجمالي
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (inv.status != InvoiceStatus.CANCELED && remaining > 0 && inv.amountPaidMinor > 0) {
                    Text(
                        stringResource(R.string.inv_row_remaining, formatMoney(remaining), inv.currency.ifBlank { LocalCurrencySymbol.current }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Spacer(Modifier)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formatMoney(inv.totalMinor)} ${inv.currency.ifBlank { LocalCurrencySymbol.current }}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(Dimens.SpaceXS))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
