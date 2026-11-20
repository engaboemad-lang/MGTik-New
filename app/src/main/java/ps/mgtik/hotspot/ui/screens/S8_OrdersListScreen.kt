package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.ReceiptLong
import ps.mgtik.hotspot.ui.util.tone
import ps.mgtik.hotspot.ui.components.states.MgtikEmptyState
import ps.mgtik.hotspot.ui.components.chips.MgtikStatusChip
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ps.mgtik.hotspot.domain.model.Order
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.util.labelRes
import ps.mgtik.hotspot.ui.theme.Dimens

/**
 * S8_OrdersListScreen - قائمة طلبات التوليد وسندات التوريد (§8).
 * تم التطهير البرمجي:
 * 1. Resource Bound: تم ربط جميع النصوص بـ strings.xml.
 * 2. Theme Compliance: استبدال الألوان الصلبة بـ MaterialTheme.
 * 3. Layout: استخدام Dimens الموحدة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S8_OrdersListScreen(
    orders: List<Order>,
    onSelectOrder: (Order) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredOrders = remember(orders, searchQuery) {
        if (searchQuery.isBlank()) orders
        else orders.filter {
            it.id.contains(searchQuery, ignoreCase = true) ||
            it.businessId.contains(searchQuery, ignoreCase = true) ||
            it.ownerNameSnapshot.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.orders_list_title))
                        Text(stringResource(R.string.orders_list_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.ScreenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.orders_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.RadiusMD),
                singleLine = true
            )

            Text(
                text = stringResource(R.string.orders_count_summary, filteredOrders.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (filteredOrders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    MgtikEmptyState(
                        icon = if (searchQuery.isNotEmpty()) Icons.Default.SearchOff else Icons.Default.ReceiptLong,
                        text = if (searchQuery.isNotEmpty()) stringResource(R.string.orders_empty_search) else stringResource(R.string.orders_empty_list)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)
                ) {
                    items(filteredOrders, key = { it.id }) { ord ->
                        OrderRowCard(ord, onClick = { onSelectOrder(ord) })
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderRowCard(ord: Order, onClick: () -> Unit) {
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
            // الصف الأول: رقم الطلب + الحالة
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ord.businessId.ifBlank { stringResource(R.string.order_no_number) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(Dimens.SpaceSM))
                MgtikStatusChip(text = stringResource(ord.status.labelRes()), tone = ord.status.tone())
            }
            Text(
                text = ord.ownerNameSnapshot.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // الصف الأخير: عدد الكروت + المبلغ (الأهم بصرياً)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.common_unit_cards, ord.totalCards),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formatMoney(ord.totalAmountMinor)} ${LocalCurrencySymbol.current}",
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
