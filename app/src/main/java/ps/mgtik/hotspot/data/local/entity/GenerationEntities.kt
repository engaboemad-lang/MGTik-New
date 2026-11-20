package ps.mgtik.hotspot.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.generation.GenerationSpec
import ps.mgtik.hotspot.domain.generation.RunState
import ps.mgtik.hotspot.domain.generation.RunStatus
import ps.mgtik.hotspot.domain.generation.SagaStep
import ps.mgtik.hotspot.domain.generation.StagedVoucher
import ps.mgtik.hotspot.domain.generation.StepState
import ps.mgtik.hotspot.domain.generation.UserLimits
import ps.mgtik.hotspot.domain.generation.VoucherState

/**
 * GenerationRunEntity - مصدر الحقيقة المحلي لساغا توليد دفعة (يُستأنف منه بعد إغلاق التطبيق).
 * أي تغيير في مخطط هذا الجدول يتطلب رفع إصدار MgtikDatabase (انظر سياسة الإصدارات هناك).
 */
@Entity(
    tableName = "generation_runs",
    indices = [Index(value = ["batch_id"])]
)
data class GenerationRunEntity(
    @PrimaryKey val executionId: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "order_id") val orderId: String,
    @ColumnInfo(name = "batch_number") val batchNumber: String,
    @ColumnInfo(name = "owner_name") val ownerName: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "profile_name") val profileName: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "voucher_count") val voucherCount: Int,
    @ColumnInfo(name = "format") val format: String,
    @ColumnInfo(name = "prefix") val prefix: String,
    @ColumnInfo(name = "code_length") val codeLength: Int,
    @ColumnInfo(name = "price_per_voucher_minor") val pricePerVoucherMinor: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "steps") val steps: String,
    @ColumnInfo(name = "attempt") val attempt: Int,
    @ColumnInfo(name = "successful") val successful: Int,
    @ColumnInfo(name = "failed") val failed: Int,
    @ColumnInfo(name = "progress_done") val progressDone: Int,
    @ColumnInfo(name = "progress_total") val progressTotal: Int,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "cancel_requested") val cancelRequested: Boolean,
    @ColumnInfo(name = "invoice_id") val invoiceId: String?,
    @ColumnInfo(name = "total_amount_minor") val totalAmountMinor: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    // لقيود الاستخدام المحسومة في PREPARE (لقطة تُعاد بها كل محاولات التنفيذ نفسها)
    @ColumnInfo(name = "limit_uptime_seconds") val limitUptimeSeconds: Long? = null,
    @ColumnInfo(name = "limit_transfer_bytes") val limitTransferBytes: Long? = null,
    // خيارات شاشة التوليد (تُحفظ مع الساغا فيعاد تنفيذها بها نفسها بعد إغلاق التطبيق)
    @ColumnInfo(name = "mode") val mode: String = "RANDOM",
    @ColumnInfo(name = "password_type") val passwordType: String = "",
    @ColumnInfo(name = "password_length") val passwordLength: Int = 0,
    @ColumnInfo(name = "comment") val comment: String = "",
    // عمود تاريخي: لم يعد يُقرأ ولا يُكتب (اختيار السيرفر أُزيل مع الانتقال إلى User Manager)؛ يبقى حفاظاً على مخطط Room دون ترحيل
    @ColumnInfo(name = "server") val server: String = "all"
) {
    fun toState(): RunState = RunState(
        executionId = executionId,
        idempotencyKey = idempotencyKey,
        spec = GenerationSpec(
            profileId, profileName, ownerId, voucherCount, format, prefix, codeLength,
            mode, passwordType, passwordLength, comment
        ),
        orderId = orderId,
        batchId = batchId,
        batchNumber = batchNumber,
        ownerName = ownerName,
        pricePerVoucherMinor = pricePerVoucherMinor,
        status = runCatching { RunStatus.valueOf(status) }.getOrDefault(RunStatus.FAILED),
        steps = decodeSteps(steps),
        attempt = attempt,
        successful = successful,
        failed = failed,
        progressDone = progressDone,
        progressTotal = progressTotal,
        errorCode = errorCode,
        errorMessage = errorMessage,
        cancelRequested = cancelRequested,
        invoiceId = invoiceId,
        totalAmountMinor = totalAmountMinor,
        createdAtMillis = createdAt,
        completedAtMillis = completedAt,
        limits = UserLimits(limitUptimeSeconds, limitTransferBytes)
    )

    companion object {
        fun fromState(s: RunState): GenerationRunEntity = GenerationRunEntity(
            executionId = s.executionId,
            idempotencyKey = s.idempotencyKey,
            batchId = s.batchId,
            orderId = s.orderId,
            batchNumber = s.batchNumber,
            ownerName = s.ownerName,
            profileId = s.spec.profileId,
            profileName = s.spec.profileName,
            ownerId = s.spec.ownerId,
            voucherCount = s.spec.count,
            format = s.spec.format,
            prefix = s.spec.prefix,
            codeLength = s.spec.codeLength,
            pricePerVoucherMinor = s.pricePerVoucherMinor,
            status = s.status.name,
            steps = encodeSteps(s.steps),
            attempt = s.attempt,
            successful = s.successful,
            failed = s.failed,
            progressDone = s.progressDone,
            progressTotal = s.progressTotal,
            errorCode = s.errorCode,
            errorMessage = s.errorMessage,
            cancelRequested = s.cancelRequested,
            invoiceId = s.invoiceId,
            totalAmountMinor = s.totalAmountMinor,
            createdAt = s.createdAtMillis,
            completedAt = s.completedAtMillis,
            limitUptimeSeconds = s.limits.uptimeSeconds,
            limitTransferBytes = s.limits.transferBytes,
            mode = s.spec.mode,
            passwordType = s.spec.passwordType,
            passwordLength = s.spec.passwordLength,
            comment = s.spec.comment
        )

        fun encodeSteps(steps: Map<SagaStep, StepState>): String =
            SagaStep.values().joinToString(",") { "${it.name}=${(steps[it] ?: StepState.PENDING).name}" }

        fun decodeSteps(raw: String): Map<SagaStep, StepState> {
            val parsed = raw.split(',').mapNotNull { part ->
                val kv = part.split('=')
                val step = SagaStep.values().firstOrNull { it.name == kv.getOrNull(0) } ?: return@mapNotNull null
                val state = StepState.values().firstOrNull { it.name == kv.getOrNull(1) } ?: StepState.PENDING
                step to state
            }.toMap()
            return SagaStep.values().associateWith { parsed[it] ?: StepState.PENDING }
        }
    }
}

/** كرت مُجهَّز (محلياً) ضمن ساغا؛ يبقى بعد الاكتمال ليُصدَّر دون اتصال. */
@Entity(
    tableName = "staged_vouchers",
    primaryKeys = ["execution_id", "seq"],
    indices = [Index(value = ["execution_id", "state"])]
)
data class StagedVoucherEntity(
    @ColumnInfo(name = "execution_id") val executionId: String,
    @ColumnInfo(name = "seq") val seq: Int,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password") val password: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "error") val error: String?
) {
    fun toDomain(): StagedVoucher = StagedVoucher(
        executionId = executionId,
        seq = seq,
        username = username,
        password = password,
        state = runCatching { VoucherState.valueOf(state) }.getOrDefault(VoucherState.PENDING),
        error = error
    )

    companion object {
        fun fromDomain(v: StagedVoucher) = StagedVoucherEntity(v.executionId, v.seq, v.username, v.password, v.state.name, v.error)
    }
}
