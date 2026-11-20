package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.layout.*
import ps.mgtik.hotspot.ui.components.bars.MgtikTopAppBar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.domain.report.ReportPeriods
import ps.mgtik.hotspot.domain.report.ReportPreset
import java.time.Instant
import java.time.ZoneOffset
import ps.mgtik.hotspot.domain.model.FinancialDashboard
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.viewmodel.ReportsViewModel

/**
 * S25_ReportsScreen - مركز التقارير والتحليلات البصرية (§4.19).
 * تم التطهير البرمجي:
 * 1. Resource Bound: ربط كافة النصوص والبطاقات بمفاتيح Strings.
 * 2. Theme Compliance: استخدام ExtendedColors للأداء المالي.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S25_ReportsScreen(
    viewModel: ReportsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.trackDashboard() }

    val errorText = state.errorMessageResId?.let { stringResource(it) }
    LaunchedEffect(state.errorMessageResId) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            MgtikTopAppBar(
                title = { Text(stringResource(R.string.reports_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = Dimens.ScreenHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing),
                contentPadding = PaddingValues(vertical = Dimens.ScreenVerticalPadding)
            ) {
                item {
                    // هذه الأرقام تراكمية لكل الأزمنة ولا تتأثر بالفترة المختارة أدناه
                    Text(
                        stringResource(R.string.reports_all_time_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.reports_all_time_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(Dimens.SpaceSM))
                    FinancialSummaryGrid(state.financialDashboard)
                }

                item {
                    PeriodSelector(
                        range = state.selectedDateRange,
                        selectedPreset = state.selectedPreset,
                        onPreset = viewModel::selectPreset,
                        onCustomRange = viewModel::updateDateRange
                    )
                }

                item {
                    ReportTypeSelector(enabled = !state.isGenerating, onSelect = { viewModel.generateReport(it) })
                }

                if (state.isGenerating) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                } else if (state.reportFailed) {
                    item { ReportFailedCard(onRetry = viewModel::retryReport) }
                } else if (state.reportType != null) {
                    item { ReportResultCard(state.reportData, state.reportType, state.previousReportData, state.previousDateRange, state.selectedDateRange) }
                }
            }
        }
    }
}

@Composable
private fun FinancialSummaryGrid(dashboard: FinancialDashboard) {
    val extendedColors = LocalMgtikExtendedColors.current
    
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
            KpiCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.reports_label_sales),
                value = "${formatMoney(dashboard.totalInvoicedMinor)} ${LocalCurrencySymbol.current}",
                color = MaterialTheme.colorScheme.primary
            )
            KpiCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.reports_label_paid),
                value = "${formatMoney(dashboard.totalPaidMinor)} ${LocalCurrencySymbol.current}",
                color = extendedColors.success
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
            KpiCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.reports_label_debt),
                value = "${formatMoney(dashboard.totalOutstandingMinor)} ${LocalCurrencySymbol.current}",
                color = MaterialTheme.colorScheme.error
            )
            KpiCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.reports_label_mrr),
                value = "${formatMoney(dashboard.monthlyMrrMinor)} ${LocalCurrencySymbol.current}",
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun KpiCard(modifier: Modifier, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(Dimens.SpaceSM))
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = color)
        }
    }
}

@Composable
private fun ReportTypeSelector(enabled: Boolean, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.reports_section_detailed), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(Dimens.SpaceSM))
    
    val reportTypes = listOf(
        "sales_summary" to stringResource(R.string.reports_type_sales),
        "owner_performance" to stringResource(R.string.reports_type_performance),
        "router_uptime" to stringResource(R.string.reports_type_uptime),
        "inventory_status" to stringResource(R.string.reports_type_inventory),
        "owner_balances" to stringResource(R.string.reports_type_balances)
    )

    reportTypes.forEach { (id, name) ->
        OutlinedCard(
            onClick = { onSelect(id) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.SpaceSM),
            shape = RoundedCornerShape(Dimens.RadiusMD)
        ) {
            Row(
                modifier = Modifier.padding(Dimens.SpaceLG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ReportFailedCard(onRetry: () -> Unit) {
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            Text(stringResource(R.string.reports_failed), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.reports_retry)) }
        }
    }
}

@androidx.annotation.StringRes
private fun reportKeyRes(key: String): Int? = when (key) {
    "invoicesCount" -> R.string.rk_invoicesCount
    "totalInvoicedMinor" -> R.string.rk_totalInvoicedMinor
    "totalOutstandingMinor" -> R.string.rk_totalOutstandingMinor
    "paymentsCount" -> R.string.rk_paymentsCount
    "totalCollectedMinor" -> R.string.rk_totalCollectedMinor
    "totalRefundedMinor" -> R.string.rk_totalRefundedMinor
    "byMethod" -> R.string.rk_byMethod
    "ownersCount" -> R.string.rk_ownersCount
    "owners" -> R.string.rk_owners
    "invoicedMinor" -> R.string.rk_invoicedMinor
    "collectedMinor" -> R.string.rk_collectedMinor
    "outstandingMinor" -> R.string.rk_outstandingMinor
    "isOnline" -> R.string.rk_isOnline
    "uptime" -> R.string.rk_uptime
    "cpuLoadPercent" -> R.string.rk_cpuLoadPercent
    "freeMemoryMb" -> R.string.rk_freeMemoryMb
    "lastCheckedAt" -> R.string.rk_lastCheckedAt
    "batchesCount" -> R.string.rk_batchesCount
    "requestedCards" -> R.string.rk_requestedCards
    "generatedCards" -> R.string.rk_generatedCards
    "failedCards" -> R.string.rk_failedCards
    "activeUsers" -> R.string.rk_activeUsers
    "disabledUsers" -> R.string.rk_disabledUsers
    "expiredUsers" -> R.string.rk_expiredUsers
    "archivedUsers" -> R.string.rk_archivedUsers
    "successRatePercent" -> R.string.rk_successRatePercent
    "completedBatches" -> R.string.rk_completedBatches
    "failedBatches" -> R.string.rk_failedBatches
    "totalCreditMinor" -> R.string.rk_totalCreditMinor
    "availableCreditMinor" -> R.string.rk_availableCreditMinor
    "byProfile" -> R.string.rk_byProfile
    "count" -> R.string.rk_count
    "amountMinor" -> R.string.rk_amountMinor
    else -> null
}

@Composable
private fun reportValueText(key: String, value: Any?): String = when {
    value is Boolean && key == "isOnline" -> stringResource(if (value) R.string.rk_online else R.string.rk_offline)
    key == "successRatePercent" && value != null -> "$value%"
    value is Long && key.endsWith("Minor") -> "${formatMoney(value)} ${LocalCurrencySymbol.current}"
    else -> value?.toString().orEmpty()
}

@Composable
private fun reportKeyText(key: String): String = reportKeyRes(key)?.let { stringResource(it) } ?: key

@Composable
private fun ReportResultCard(
    data: Map<String, Any?>,
    reportType: String?,
    previous: Map<String, Any?> = emptyMap(),
    previousRange: Pair<String, String>? = null,
    range: Pair<String, String>? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val csvHeader = listOf(
        stringResource(R.string.reports_csv_section), stringResource(R.string.reports_csv_item),
        stringResource(R.string.reports_csv_metric), stringResource(R.string.reports_csv_value)
    )
    val unknownOwner = stringResource(R.string.reports_owner_unknown)
    Card(
        shape = RoundedCornerShape(Dimens.RadiusLG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            Text(stringResource(R.string.reports_result_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            if (reportType == "owner_balances") {
                Text(stringResource(R.string.reports_snapshot_balances_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (reportType == "router_uptime") {
                Text(stringResource(R.string.reports_snapshot_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (data.isEmpty()) {
                Text(stringResource(R.string.reports_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                val resources = context.resources
                OutlinedButton(onClick = {
                    val csv = ps.mgtik.hotspot.domain.report.ReportCsv.build(
                        data = data,
                        header = csvHeader,
                        keyLabel = { k -> reportKeyRes(k)?.let { resources.getString(it) } ?: k },
                        valueText = { k, v -> when {
                            v is Boolean && k == "isOnline" -> resources.getString(if (v) R.string.rk_online else R.string.rk_offline)
                            v is Long && k.endsWith("Minor") -> ps.mgtik.hotspot.core.util.formatMinor(v)
                            else -> v?.toString().orEmpty()
                        } },
                        rowLabel = { section, raw -> when {
                            section == "byMethod" -> resources.getString(ps.mgtik.hotspot.ui.util.InvoiceTexts.methodRes(raw))
                            raw.isBlank() -> unknownOwner
                            else -> raw
                        } }
                    )
                    val name = "report-${reportType.orEmpty()}-${range?.first.orEmpty()}_${range?.second.orEmpty()}.csv".replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    val file = java.io.File(java.io.File(context.cacheDir, "exports").also { it.mkdirs() }, name)
                    file.writeText(csv, Charsets.UTF_8)
                    ps.mgtik.hotspot.ui.util.ShareFiles.share(context, file, "text/csv")
                }) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.reports_export_csv))
                }
                data.forEach { (key, value) ->
                    if (value is List<*>) {
                        // قسم تفصيلي: صفوف label + قيم
                        Text(reportKeyText(key), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (value.isEmpty()) {
                            Text(stringResource(R.string.reports_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        value.filterIsInstance<Map<*, *>>().forEach { row ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // طريقة الدفع تُخزَّن كمفتاح (cash/transfer…) فتُعرض مترجمة؛ وكيل بلا اسم لا يُعرض معرّفه الخام
                                val rawLabel = row["label"]?.toString().orEmpty()
                                val labelText = when {
                                    key == "byMethod" -> stringResource(ps.mgtik.hotspot.ui.util.InvoiceTexts.methodRes(rawLabel))
                                    rawLabel.isBlank() -> stringResource(R.string.reports_owner_unknown)
                                    else -> rawLabel
                                }
                                Text(labelText, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                row.entries.filter { it.key != "label" }.forEach { (k, v) ->
                                    val kk = k.toString()
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(reportKeyText(kk), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(reportValueText(kk, v), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(reportKeyText(key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(reportValueText(key, value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        ComparisonLine(key, value, previous, previousRange)
                    }
                }
            }
        }
    }
}

/** سطر المقارنة بالفترة السابقة تحت القيمة الرقمية؛ لا يظهر إن لم تتوفر قيمة سابقة. */
@Composable
private fun ComparisonLine(key: String, value: Any?, previous: Map<String, Any?>, previousRange: Pair<String, String>?) {
    if (previousRange == null || previous.isEmpty()) return
    val current = ReportPeriods.numeric(value) ?: return
    val before = ReportPeriods.numeric(previous[key]) ?: return
    val percent = ReportPeriods.deltaPercent(current, before)
    val beforeText = reportValueText(key, previous[key])
    val text = when {
        percent == null -> stringResource(R.string.reports_delta_no_base, beforeText)
        percent > 0 -> stringResource(R.string.reports_delta_up, percent, beforeText)
        percent < 0 -> stringResource(R.string.reports_delta_down, -percent, beforeText)
        else -> stringResource(R.string.reports_delta_flat, beforeText)
    }
    val color = when {
        percent == null || percent == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        // الارتفاع جيد إلا في الديون المستحقة
        (percent > 0) != (key == "totalOutstandingMinor" || key == "totalRefundedMinor") -> LocalMgtikExtendedColors.current.success
        else -> MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(
    range: Pair<String, String>,
    selectedPreset: ReportPreset?,
    onPreset: (ReportPreset) -> Unit,
    onCustomRange: (String, String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
        Text(stringResource(R.string.reports_period_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.reports_period, range.first, range.second),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            val presets = listOf(
                ReportPreset.LAST_7_DAYS to R.string.reports_preset_7d,
                ReportPreset.LAST_30_DAYS to R.string.reports_preset_30d,
                ReportPreset.THIS_MONTH to R.string.reports_preset_this_month,
                ReportPreset.LAST_MONTH to R.string.reports_preset_last_month
            )
            items(presets.size) { i ->
                val (preset, label) = presets[i]
                FilterChip(selected = selectedPreset == preset, onClick = { onPreset(preset) }, label = { Text(stringResource(label)) })
            }
            item {
                FilterChip(selected = selectedPreset == null, onClick = { showPicker = true }, label = { Text(stringResource(R.string.reports_preset_custom)) })
            }
        }
    }
    if (showPicker) {
        fun millisOf(date: String): Long? = try {
            java.time.LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) { null }
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = millisOf(range.first),
            initialSelectedEndDateMillis = millisOf(range.second)
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null && pickerState.selectedEndDateMillis != null,
                    onClick = {
                        val start = pickerState.selectedStartDateMillis
                        val end = pickerState.selectedEndDateMillis
                        if (start != null && end != null) {
                            // محدد التاريخ يعيد منتصف الليل UTC لليوم المختار: نقرأه بـ UTC ليبقى اليوم نفسه
                            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                            onCustomRange(day(start), day(end))
                        }
                        showPicker = false
                    }
                ) { Text(stringResource(R.string.reports_picker_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.common_cancel)) } }
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.height(500.dp))
        }
    }
}
