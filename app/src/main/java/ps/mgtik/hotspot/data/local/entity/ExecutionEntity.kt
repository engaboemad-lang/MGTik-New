package ps.mgtik.hotspot.data.local.entity

import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import ps.mgtik.hotspot.data.mapping.toEnumOrFailSafe
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.*

/**
 * ExecutionEntity - مطابق لـ Execution (§6.7)
 * يمثل رأس سجل تنفيذ عملية (توليد أو إنشاء بروفايل).
 */
@Entity(tableName = "executions")
data class ExecutionEntity(
    @PrimaryKey val executionId: String,
    @ColumnInfo(name = "context_id") val contextId: String, // batchId or cloudProfileId
    @ColumnInfo(name = "flow_type") val flowType: String, // Enum name: GENERATION, PROFILE_CREATION
    @ColumnInfo(name = "attempt_number") val attemptNumber: Int,
    @ColumnInfo(name = "status") val status: String, // Enum: QUEUED, RUNNING, COMPLETED...
    @ColumnInfo(name = "requested_quantity") val requestedQuantity: Int?,
    @ColumnInfo(name = "successful_quantity") val successfulQuantity: Int?,
    @ColumnInfo(name = "failed_quantity") val failedQuantity: Int?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "started_at") val startedAt: String,
    @ColumnInfo(name = "completed_at") val completedAt: String?,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
) {
    /**
     * flowType المجهول يرفض السجل (null). حالة التنفيذ المجهولة تصير FAILED: حالة فشل آمنة لا تدّعي
     * نجاحاً ولا تفتح صلاحية، ومصدر الحقيقة للساغا هو generation_runs وليس هذا الجدول.
     */
    fun toDomainOrNull(steps: List<ExecutionStepRecord>): Execution? {
        val parsedFlow = flowType.toEnumOrNull<WorkflowFlowType>() ?: return null
        return Execution(
            executionId = executionId,
            contextId = contextId,
            flowType = parsedFlow,
            attemptNumber = attemptNumber,
            status = status.toEnumOrFailSafe(ExecutionStatus.FAILED),
            steps = steps,
            requestedQuantity = requestedQuantity,
            successfulQuantity = successfulQuantity,
            failedQuantity = failedQuantity,
            errorCode = errorCode,
            errorMessage = errorMessage,
            startedAt = startedAt,
            completedAt = completedAt
        )
    }

    companion object {
        fun fromDomain(execution: Execution): ExecutionEntity {
            return ExecutionEntity(
                executionId = execution.executionId,
                contextId = execution.contextId,
                flowType = execution.flowType.name,
                attemptNumber = execution.attemptNumber,
                status = execution.status.name,
                requestedQuantity = execution.requestedQuantity,
                successfulQuantity = execution.successfulQuantity,
                failedQuantity = execution.failedQuantity,
                errorCode = execution.errorCode,
                errorMessage = execution.errorMessage,
                startedAt = execution.startedAt,
                completedAt = execution.completedAt
            )
        }
    }
}
