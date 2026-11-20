package ps.mgtik.hotspot.ui.components.um

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.data.userman.UmBurstDir
import ps.mgtik.hotspot.data.userman.UmLimitIssue
import ps.mgtik.hotspot.data.userman.UmLimitRules
import ps.mgtik.hotspot.data.userman.UmLimitation

/**
 * حالة نموذج Burst (مشتركة بين شاشة Limitations ونموذج الباقة). rx = رفع، tx = تنزيل.
 * [enabled]=false يعني: لا Burst (عند التعديل يُرسل تعطيل صريح إن كان مفعّلاً على الراوتر).
 */
class UmBurstFormState(initial: UmLimitation? = null) {
    private val initRx = initial?.burstRx ?: UmBurstDir()
    private val initTx = initial?.burstTx ?: UmBurstDir()
    var enabled by mutableStateOf(initRx.enabled || initTx.enabled)
    var txRate by mutableStateOf(initTx.rateKbps.takeIf { it > 0 }?.toString() ?: "")
    var txThr by mutableStateOf(initTx.thresholdKbps.takeIf { it > 0 }?.toString() ?: "")
    var txTime by mutableStateOf(initTx.timeSeconds.takeIf { it > 0 }?.toString() ?: "")
    var rxRate by mutableStateOf(initRx.rateKbps.takeIf { it > 0 }?.toString() ?: "")
    var rxThr by mutableStateOf(initRx.thresholdKbps.takeIf { it > 0 }?.toString() ?: "")
    var rxTime by mutableStateOf(initRx.timeSeconds.takeIf { it > 0 }?.toString() ?: "")

    private fun dir(rate: String, thr: String, time: String) = UmBurstDir(rate.toIntOrNull() ?: 0, thr.toIntOrNull() ?: 0, time.toLongOrNull() ?: 0L)
    val burstRx: UmBurstDir get() = if (enabled) dir(rxRate, rxThr, rxTime) else UmBurstDir()
    val burstTx: UmBurstDir get() = if (enabled) dir(txRate, txThr, txTime) else UmBurstDir()

    /** null = لم يتغير عن الحالة الأصلية فلا يُرسل (لا نعيد كتابة قيمة كما هي). */
    val changedRx: UmBurstDir? get() = burstRx.takeIf { it != initRx }
    val changedTx: UmBurstDir? get() = burstTx.takeIf { it != initTx }

    /** قيم مفضلة محسوبة من السرعة القصوى لكل اتجاه (اتجاه بلا سرعة قصوى لا يأخذ Burst). */
    fun fillDefaults(maxRxKbps: Int, maxTxKbps: Int) {
        val r = UmLimitRules.defaultBurst(maxRxKbps); val t = UmLimitRules.defaultBurst(maxTxKbps)
        enabled = true
        rxRate = r.rateKbps.takeIf { it > 0 }?.toString() ?: ""; rxThr = r.thresholdKbps.takeIf { it > 0 }?.toString() ?: ""; rxTime = r.timeSeconds.takeIf { it > 0 }?.toString() ?: ""
        txRate = t.rateKbps.takeIf { it > 0 }?.toString() ?: ""; txThr = t.thresholdKbps.takeIf { it > 0 }?.toString() ?: ""; txTime = t.timeSeconds.takeIf { it > 0 }?.toString() ?: ""
    }
}

@Composable
fun limitIssueText(i: UmLimitIssue): String = stringResource(
    when (i.code) {
        UmLimitRules.BURST_INCOMPLETE -> R.string.um_limit_issue_burst_incomplete
        UmLimitRules.BURST_NEEDS_RATE -> R.string.um_limit_issue_burst_needs_rate
        UmLimitRules.BURST_NOT_ABOVE_RATE -> R.string.um_limit_issue_burst_not_above_rate
        UmLimitRules.BURST_THRESHOLD_INVALID -> R.string.um_limit_issue_burst_threshold_invalid
        UmLimitRules.BURST_TIME_INVALID -> R.string.um_limit_issue_burst_time_invalid
        UmLimitRules.DOWNLOAD_EXCEEDS_TRANSFER -> R.string.um_limit_issue_download_exceeds_transfer
        UmLimitRules.UPLOAD_EXCEEDS_TRANSFER -> R.string.um_limit_issue_upload_exceeds_transfer
        UmLimitRules.PRIORITY_INVALID -> R.string.um_limit_issue_priority_invalid
        UmLimitRules.PRIORITY_NEEDS_RATE -> R.string.um_limit_issue_priority_needs_rate
        UmLimitRules.RESET_NEEDS_LIMIT -> R.string.um_limit_issue_reset_needs_limit
        UmLimitRules.SCHEDULE_TIME_INVALID -> R.string.um_limit_issue_schedule_time_invalid
        UmLimitRules.SCHEDULE_NO_DAYS -> R.string.um_limit_issue_schedule_no_days
        UmLimitRules.UNSUPPORTED -> R.string.um_limit_issue_unsupported
        else -> R.string.um_limit_issue_negative_value
    }
)

/** قسم Burst: مفتاح تفعيل، قيم لكل اتجاه، زر القيم المفضلة، وعرض أول مشاكل التحقق. */
@Composable
fun UmBurstFormSection(form: UmBurstFormState, maxRxKbps: Int, maxTxKbps: Int, issues: List<UmLimitIssue>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.um_burst_title), style = MaterialTheme.typography.labelLarge)
        UmSwitchRow(stringResource(R.string.um_burst_enable), form.enabled) { form.enabled = it }
        if (form.enabled) {
            Text(stringResource(R.string.um_burst_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { form.fillDefaults(maxRxKbps, maxTxKbps) }) { Text(stringResource(R.string.um_burst_defaults)) }
            Text(stringResource(R.string.um_burst_download), style = MaterialTheme.typography.labelMedium)
            UmNumberField(stringResource(R.string.um_burst_rate), form.txRate) { form.txRate = it }
            UmNumberField(stringResource(R.string.um_burst_threshold), form.txThr) { form.txThr = it }
            UmNumberField(stringResource(R.string.um_burst_time), form.txTime) { form.txTime = it }
            Text(stringResource(R.string.um_burst_upload), style = MaterialTheme.typography.labelMedium)
            UmNumberField(stringResource(R.string.um_burst_rate), form.rxRate) { form.rxRate = it }
            UmNumberField(stringResource(R.string.um_burst_threshold), form.rxThr) { form.rxThr = it }
            UmNumberField(stringResource(R.string.um_burst_time), form.rxTime) { form.rxTime = it }
        }
        issues.forEach { Text(limitIssueText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
