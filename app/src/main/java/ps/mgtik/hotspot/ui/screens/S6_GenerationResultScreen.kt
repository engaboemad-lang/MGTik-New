package ps.mgtik.hotspot.ui.screens

import ps.mgtik.hotspot.ui.util.LocalCurrencySymbol
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.VoucherBatch
import ps.mgtik.hotspot.domain.model.VoucherBatchStatus
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors

/**
 * S6_GenerationResultScreen - نتيجة صادقة: نجاح كامل أو جزئي مع عدد الناجح والمرفوض، والمبلغ بدقة مع العملة.
 */
@Composable
fun S6_GenerationResultScreen(
    batch: VoucherBatch,
    failureReasons: List<ps.mgtik.hotspot.domain.model.FailureReason> = emptyList(),
    onExport: () -> Unit,
    onOpenOrderDetail: () -> Unit,
    onDone: () -> Unit
) {
    val partial = batch.failedQuantity > 0
    val currencySymbol = LocalCurrencySymbol.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = Dimens.MaxFormWidth)
            .verticalScroll(rememberScrollState())
            .padding(Dimens.SpaceXXL),
        verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.CheckCircle, null,
            tint = if (partial) MaterialTheme.colorScheme.tertiary else LocalMgtikExtendedColors.current.success,
            modifier = Modifier.size(72.dp)
        )
        Text(
            stringResource(if (partial) R.string.gen_res_partial_title else R.string.gen_res_success_title),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, textAlign = TextAlign.Center
        )
        Text(
            stringResource(if (partial) R.string.gen_res_partial_subtitle else R.string.gen_res_success_subtitle),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )

        Card(
            shape = RoundedCornerShape(Dimens.RadiusLG),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                ResultRow(stringResource(R.string.gen_res_batch_no), batch.batchNumber)
                ResultRow(stringResource(R.string.gen_res_profile), batch.profileName)
                ResultRow(stringResource(R.string.gen_res_count), "${batch.successfulQuantity} / ${batch.requestedQuantity}")
                if (partial) ResultRow(stringResource(R.string.gen_res_failed_count), batch.failedQuantity.toString(), isError = true)
                ResultRow(stringResource(R.string.gen_res_value), "${formatMoney(batch.totalAmountMinor)} $currencySymbol")
            }
        }

        // أسباب فشل الحسابات التي رفضها الراوتر (مجمّعة بالسبب مع العدد)
        if (partial && failureReasons.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(Dimens.RadiusLG),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    Text(stringResource(R.string.gen_res_failure_reasons), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    failureReasons.forEach { r ->
                        ResultRow(failureReasonText(r.reason), r.count.toString(), isError = true)
                    }
                }
            }
        }

        if (batch.status == VoucherBatchStatus.COMPLETED && batch.successfulQuantity > 0) {
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeightLarge), shape = RoundedCornerShape(Dimens.RadiusMD)) {
                Icon(Icons.Default.Print, null, modifier = Modifier.size(Dimens.IconSizeMedium))
                Spacer(Modifier.width(Dimens.SpaceSM))
                Text(stringResource(R.string.gen_res_btn_export), fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(onClick = onOpenOrderDetail, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeightLarge), shape = RoundedCornerShape(Dimens.RadiusMD)) {
            Icon(Icons.Default.Receipt, null, modifier = Modifier.size(Dimens.IconSizeMedium))
            Spacer(Modifier.width(Dimens.SpaceSM))
            Text(stringResource(R.string.gen_res_btn_order))
        }
        TextButton(onClick = onDone) { Text(stringResource(R.string.gen_res_btn_done)) }
    }
}

/** سبب محفوظ بصيغة "ERR_X: تفصيل" ⇒ نص مترجم للرمز؛ غير ذلك (رسالة رفض الراوتر الخام) يُعرض كما هو. */
@Composable
internal fun failureReasonText(reason: String): String {
    if (reason.isBlank()) return stringResource(R.string.gen_res_reason_unknown)
    val code = Regex("^(ERR_[A-Z0-9_]+)").find(reason)?.groupValues?.get(1)
    val res = code?.let { ps.mgtik.hotspot.ui.util.ErrorTranslator.resIdOrNull(it) }
    return if (res != null) stringResource(res) else reason
}

@Composable
private fun ResultRow(label: String, value: String, isError: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
            fontWeight = FontWeight.Bold,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
