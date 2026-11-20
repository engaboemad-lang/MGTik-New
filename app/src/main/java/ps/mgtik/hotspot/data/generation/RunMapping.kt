package ps.mgtik.hotspot.data.generation

import ps.mgtik.hotspot.domain.generation.RunState
import ps.mgtik.hotspot.domain.generation.RunStatus
import ps.mgtik.hotspot.domain.generation.SagaStep
import ps.mgtik.hotspot.domain.generation.StepState
import ps.mgtik.hotspot.domain.model.Execution
import ps.mgtik.hotspot.domain.model.ExecutionStatus
import ps.mgtik.hotspot.domain.model.ExecutionStepRecord
import ps.mgtik.hotspot.domain.model.StepStatus
import ps.mgtik.hotspot.domain.model.VoucherBatch
import ps.mgtik.hotspot.domain.model.VoucherBatchStatus
import ps.mgtik.hotspot.domain.model.WorkflowFlowType
import ps.mgtik.hotspot.core.util.toSortableIso
import java.util.Date

/** تحويلات نقية بين حالة الساغا ونماذج الواجهة (Execution/VoucherBatch). */
object RunMapping {

    /** تنسيق التاريخ الموحَّد للمشروع (UTC بالملّي ثانية، قابل للترتيب نصياً): نفس معيار IsoTimestamps. */
    fun formatTime(millis: Long?): String? = millis?.let { Date(it).toSortableIso() }

    fun executionStatus(status: RunStatus): ExecutionStatus = when (status) {
        RunStatus.QUEUED -> ExecutionStatus.QUEUED
        RunStatus.RUNNING -> ExecutionStatus.RUNNING
        RunStatus.RETRYING -> ExecutionStatus.RETRYING
        RunStatus.COMPLETED -> ExecutionStatus.COMPLETED
        RunStatus.FAILED -> ExecutionStatus.FAILED
        RunStatus.CANCELLED -> ExecutionStatus.CANCELLED
    }

    fun stepStatus(state: StepState): StepStatus = when (state) {
        StepState.PENDING -> StepStatus.PENDING
        StepState.RUNNING -> StepStatus.RUNNING
        StepState.SUCCESS -> StepStatus.SUCCESS
        StepState.FAILED -> StepStatus.FAILED
    }

    /** contextId = batchId (S6 يجلب الدفعة به)؛ التقدم العددي في detail للخطوة الجارية. */
    fun toExecution(state: RunState): Execution = Execution(
        executionId = state.executionId,
        contextId = state.batchId,
        flowType = WorkflowFlowType.GENERATION,
        attemptNumber = state.attempt,
        status = executionStatus(state.status),
        steps = SagaStep.values().map { step ->
            val st = state.step(step)
            ExecutionStepRecord(
                step = step.id,
                status = stepStatus(st),
                detail = if (st == StepState.RUNNING && state.progressTotal > 0)
                    mapOf("done" to state.progressDone.toString(), "total" to state.progressTotal.toString())
                else emptyMap(),
                errorCode = if (st == StepState.FAILED) state.errorCode else null
            )
        },
        requestedQuantity = state.requested,
        successfulQuantity = state.successful,
        failedQuantity = state.failed,
        errorCode = state.errorCode,
        errorMessage = state.errorMessage,
        startedAt = formatTime(state.createdAtMillis) ?: "",
        completedAt = formatTime(state.completedAtMillis)
    )

    fun toBatch(state: RunState): VoucherBatch = VoucherBatch(
        id = state.batchId,
        batchNumber = state.batchNumber.ifBlank { "—" },
        cloudProfileId = state.spec.profileId,
        profileName = state.spec.profileName,
        ownerId = state.spec.ownerId,
        orderId = state.orderId,
        requestedQuantity = state.requested,
        successfulQuantity = state.successful,
        failedQuantity = state.failed,
        pricePerVoucherMinor = state.pricePerVoucherMinor,
        totalAmountMinor = state.totalAmountMinor,
        status = when (state.status) {
            RunStatus.QUEUED -> VoucherBatchStatus.QUEUED
            RunStatus.RUNNING -> VoucherBatchStatus.RUNNING
            RunStatus.RETRYING -> VoucherBatchStatus.RETRYING
            RunStatus.COMPLETED -> VoucherBatchStatus.COMPLETED
            RunStatus.FAILED -> VoucherBatchStatus.FAILED
            RunStatus.CANCELLED -> VoucherBatchStatus.CANCELLED
        },
        createdAt = formatTime(state.createdAtMillis) ?: ""
    )
}
