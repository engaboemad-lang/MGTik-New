package ps.mgtik.hotspot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.report.BillingPeriod
import ps.mgtik.hotspot.domain.report.BillingPreset
import ps.mgtik.hotspot.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * محدد فترة لوحة Billing: فترات جاهزة + نطاق مخصص. «هذا الشهر» هو الافتراضي وتتجدد حدوده وحدها كل شهر،
 * ويظهر تحت الشرائح نطاق التواريخ الفعلي المحسوب (لا غموض فيما تُحسب عليه الأرقام).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingPeriodSelector(
    period: BillingPeriod,
    range: Pair<String, String>?,
    onSelect: (BillingPeriod) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val presets = listOf(
        BillingPreset.TODAY to R.string.billing_period_today,
        BillingPreset.THIS_MONTH to R.string.reports_preset_this_month,
        BillingPreset.LAST_MONTH to R.string.reports_preset_last_month,
        BillingPreset.LAST_30_DAYS to R.string.reports_preset_30d,
        BillingPreset.ALL_TIME to R.string.billing_period_all
    )
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXS)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
            items(presets) { (preset, label) ->
                FilterChip(
                    selected = period.preset == preset,
                    onClick = { onSelect(BillingPeriod(preset)) },
                    label = { Text(stringResource(label)) }
                )
            }
            item {
                FilterChip(
                    selected = period.preset == null,
                    onClick = { showPicker = true },
                    label = { Text(stringResource(R.string.reports_preset_custom)) }
                )
            }
        }
        Text(
            text = when {
                range == null -> stringResource(R.string.billing_period_all_caption)
                else -> stringResource(R.string.reports_period, range.first, range.second)
            } + if (period.preset == BillingPreset.THIS_MONTH) " · " + stringResource(R.string.billing_period_auto_note) else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showPicker) {
        // محدد التاريخ يعيد منتصف الليل UTC لليوم المختار: نقرأه بـ UTC ليبقى اليوم نفسه
        fun millisOf(date: String): Long? = try {
            LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) { null }
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = range?.first?.let(::millisOf),
            initialSelectedEndDateMillis = range?.second?.let(::millisOf)
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null && pickerState.selectedEndDateMillis != null,
                    onClick = {
                        val s = pickerState.selectedStartDateMillis
                        val e = pickerState.selectedEndDateMillis
                        if (s != null && e != null) onSelect(BillingPeriod(preset = null, custom = day(s) to day(e)))
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
