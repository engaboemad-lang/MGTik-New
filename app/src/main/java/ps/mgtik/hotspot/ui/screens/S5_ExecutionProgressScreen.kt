package ps.mgtik.hotspot.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import ps.mgtik.hotspot.R
import ps.mgtik.hotspot.domain.model.Execution
import ps.mgtik.hotspot.domain.model.ExecutionStatus
import ps.mgtik.hotspot.domain.model.FailureReason
import ps.mgtik.hotspot.domain.model.VoucherBatch
import ps.mgtik.hotspot.domain.model.StepStatus
import ps.mgtik.hotspot.ui.theme.Dimens
import ps.mgtik.hotspot.ui.theme.LocalMgtikExtendedColors
import ps.mgtik.hotspot.ui.viewmodel.GenerationMessages

/**
 * S5_ExecutionProgressScreen - تقدم ساغا التوليد (تُقرأ من Room، فتعمل بلا اتصال وتنجو من إعادة التشغيل).
 * - خطوات حقيقية وتقدم عددي (كرت x من N) للخطوة الجارية.
 * - إلغاء (قبل الفوترة) وإعادة محاولة (للفاشل فقط) وسبب الفشل مترجماً.
 * - التنفيذ في الخلفية: يمكن مغادرة الشاشة بأمان.
 */
@Composable
fun S5_ExecutionProgressScreen(
    execution: Execution,
    batch: VoucherBatch? = null,
    failureReasons: List<FailureReason> = emptyList(),
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onBackToOverview: () -> Unit
) {
    var confirmCancel by remember { mutableStateOf(false) }
    val running = execution.status == ExecutionStatus.QUEUED || execution.status == ExecutionStatus.RUNNING || execution.status == ExecutionStatus.RETRYING
    val failed = execution.status == ExecutionStatus.FAILED
    val cancelled = execution.status == ExecutionStatus.CANCELLED
    // تقدم فعلي فقط: خطوات منجزة + نسبة الخطوة الجارية من عدّاد حقيقي (done/total)، لا نسبة مقدَّرة
    val fraction = ExecutionProgressMath.fraction(execution)
    val requested = execution.requestedQuantity ?: 0
    val successful = execution.successfulQuantity ?: 0
    val rejected = execution.failedQuantity ?: 0
    val remaining = (requested - successful - rejected).coerceAtLeast(0)

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
        Text(stringResource(R.string.gen_prog_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)

        Card(
            shape = RoundedCornerShape(Dimens.RadiusLG),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMD)) {
                if (batch != null) {
                    InfoRow(stringResource(R.string.gen_res_batch_no), batch.batchNumber)
                    InfoRow(stringResource(R.string.gen_res_profile), batch.profileName)
                    InfoRow(stringResource(R.string.gen_prog_requested_label), requested.toString())
                    HorizontalDivider()
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gen_prog_status_label, stringResource(statusLabel(execution.status))), fontWeight = FontWeight.Bold)
                    if (running) CircularProgressIndicator(Modifier.size(Dimens.IconSizeLarge), strokeWidth = 2.dp)
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                HorizontalDivider()

                execution.steps.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (step.status) {
                            StepStatus.SUCCESS -> Icon(Icons.Default.CheckCircle, null, tint = LocalMgtikExtendedColors.current.success, modifier = Modifier.size(Dimens.IconSizeLarge))
                            StepStatus.FAILED -> Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(Dimens.IconSizeLarge))
                            StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(Dimens.IconSizeMedium), strokeWidth = 2.dp)
                            else -> Icon(Icons.Rounded.Circle, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(Dimens.IconSizeSmall))
                        }
                        Spacer(Modifier.width(Dimens.SpaceMD))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(stepLabel(step.step)), style = MaterialTheme.typography.bodyMedium, fontWeight = if (step.status == StepStatus.RUNNING) FontWeight.Bold else FontWeight.Normal)
                            val d = step.detail?.get("done")?.toIntOrNull()
                            val t = step.detail?.get("total")?.toIntOrNull()
                            if (step.status == StepStatus.RUNNING && d != null && t != null && t > 0) {
                                Text(stringResource(R.string.gen_prog_count, d, t), style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                // حالة الحفظ والمزامنة: كل طبقة من حالة خطوتها الفعلية (لا افتراض)
                HorizontalDivider()
                fun stepOf(id: String) = execution.steps.firstOrNull { it.step == id }?.status ?: StepStatus.PENDING
                SyncRow(stringResource(R.string.gen_sync_local), stepOf("generate"))
                SyncRow(stringResource(R.string.gen_sync_router), stepOf("router_push"))
                SyncRow(stringResource(R.string.gen_sync_cloud), stepOf("cloud_commit"))

                // العدّادات الفعلية (من حالة التنفيذ المحفوظة) بعد بدء الدفع أو عند الفشل
                if (successful > 0 || rejected > 0 || failed) {
                    HorizontalDivider()
                    InfoRow(stringResource(R.string.gen_prog_successful), stringResource(R.string.gen_prog_count, successful, requested))
                    if (rejected > 0) InfoRow(stringResource(R.string.gen_res_failed_count), rejected.toString(), isError = true)
                    if (running || failed) InfoRow(stringResource(R.string.gen_prog_remaining), remaining.toString())
                }
            }
        }

        // سبب الفشل/إعادة المحاولة التلقائية
        val errorCode = execution.errorCode
        if ((failed || execution.status == ExecutionStatus.RETRYING) && errorCode != null) {
            Text(stringResource(GenerationMessages.forCode(errorCode)), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            // التفصيل التقني (رسالة الخطأ الخام) لمن يحتاج التشخيص؛ لا يُعرض إن كان فارغاً
            execution.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.gen_prog_error_detail, it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr), textAlign = TextAlign.Center)
            }
        }
        // أسباب رفض الكروت (مجمّعة بالسبب) كما في شاشة النتيجة
        if (rejected > 0 && failureReasons.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(Dimens.RadiusLG),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(Dimens.SpaceLG), verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSM)) {
                    Text(stringResource(R.string.gen_res_failure_reasons), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    failureReasons.forEach { r -> InfoRow(failureReasonText(r.reason), r.count.toString(), isError = true) }
                }
            }
        }
        if (cancelled) Text(stringResource(R.string.gen_cancelled_message), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (running) Text(stringResource(R.string.gen_prog_background_note), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (running) {
            OutlinedButton(
                onClick = { confirmCancel = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeightLarge),
                shape = RoundedCornerShape(Dimens.RadiusMD)
            ) { Text(stringResource(R.string.gen_btn_cancel)) }
        }
        if (failed) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeightLarge), shape = RoundedCornerShape(Dimens.RadiusMD)) {
                Text(stringResource(R.string.gen_btn_retry))
            }
            OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth().height(Dimens.ButtonHeightLarge), shape = RoundedCornerShape(Dimens.RadiusMD)) {
                Text(stringResource(R.string.gen_btn_cancel))
            }
        }
        TextButton(onClick = onBackToOverview) { Text(stringResource(R.string.gen_btn_back_overview)) }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.gen_cancel_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.gen_cancel_confirm_message)) },
            confirmButton = {
                Button(onClick = { confirmCancel = false; onCancel() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.gen_cancel_confirm_yes))
                }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.gen_cancel_confirm_no)) } }
        )
    }
}

private fun statusLabel(status: ExecutionStatus): Int = when (status) {
    ExecutionStatus.QUEUED -> R.string.gen_prog_status_queued
    ExecutionStatus.RUNNING -> R.string.gen_prog_status_running
    ExecutionStatus.RETRYING -> R.string.gen_prog_status_retrying
    ExecutionStatus.FAILED -> R.string.gen_prog_status_failed
    ExecutionStatus.CANCELLED -> R.string.gen_prog_status_cancelled
    ExecutionStatus.COMPLETED -> R.string.gen_prog_status_completed
}

private fun stepLabel(stepId: String): Int = when (stepId) {
    "prepare" -> R.string.gen_step_prepare
    "generate" -> R.string.gen_step_generate
    "router_push" -> R.string.gen_step_router_push
    "cloud_commit" -> R.string.gen_step_cloud_commit
    "billing" -> R.string.gen_step_billing
    else -> R.string.gen_error_unknown
}

@Composable
private fun SyncRow(label: String, status: StepStatus) {
    val (textRes, error) = when (status) {
        StepStatus.SUCCESS -> R.string.gen_sync_done to false
        StepStatus.RUNNING -> R.string.gen_sync_running to false
        StepStatus.FAILED -> R.string.gen_sync_failed to true
        else -> R.string.gen_sync_pending to false
    }
    InfoRow(label, stringResource(textRes), isError = error)
}

@Composable
private fun InfoRow(label: String, value: String, isError: Boolean = false) {
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

/** حساب نسبة التقدم من حالة التنفيذ فعلياً (نقي ⇒ قابل للاختبار). */
object ExecutionProgressMath {
    fun fraction(execution: Execution): Float {
        val steps = execution.steps
        if (steps.isEmpty()) return 0f
        if (execution.status == ExecutionStatus.COMPLETED) return 1f
        val done = steps.count { it.status == StepStatus.SUCCESS }
        val running = steps.firstOrNull { it.status == StepStatus.RUNNING }
        val d = running?.detail?.get("done")?.toIntOrNull()
        val t = running?.detail?.get("total")?.toIntOrNull()
        val partial = if (d != null && t != null && t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else 0f
        return ((done + partial) / steps.size).coerceIn(0f, 1f)
    }
}
