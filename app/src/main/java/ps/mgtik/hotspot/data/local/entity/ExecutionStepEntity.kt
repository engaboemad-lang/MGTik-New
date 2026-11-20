package ps.mgtik.hotspot.data.local.entity

import ps.mgtik.hotspot.data.mapping.toEnumOrFailSafe
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.ExecutionStepRecord
import ps.mgtik.hotspot.domain.model.StepStatus

/**
 * ExecutionStepEntity - مطابق لـ ExecutionStepRecord (§6.7).
 * تم تحديثه لضمان حفظ حقل التفاصيل (detail) ومنع فقدان البيانات أثناء المزامنة المحلية.
 */
@Entity(
    tableName = "execution_steps",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionEntity::class,
            parentColumns = ["executionId"],
            childColumns = ["execution_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["execution_id"])]
)
data class ExecutionStepEntity(
    @PrimaryKey(autoGenerate = true) val localStepId: Long = 0,
    @ColumnInfo(name = "execution_id") val executionId: String,
    @ColumnInfo(name = "step") val step: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "detail") val detail: Map<String, String>?, // تمت الإضافة لمنع فقدان البيانات (§6.7)
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "started_at") val startedAt: String?,
    @ColumnInfo(name = "completed_at") val completedAt: String?
) {
    fun toDomain(): ExecutionStepRecord {
        return ExecutionStepRecord(
            step = step,
            status = status.toEnumOrFailSafe(StepStatus.FAILED), // فشل آمن، لا نجاح مُفترض
            detail = detail ?: emptyMap(),
            errorCode = errorCode,
            startedAt = startedAt,
            completedAt = completedAt
        )
    }

    companion object {
        fun fromDomain(executionId: String, record: ExecutionStepRecord): ExecutionStepEntity {
            return ExecutionStepEntity(
                executionId = executionId,
                step = record.step,
                status = record.status.name,
                detail = record.detail,
                errorCode = record.errorCode,
                startedAt = record.startedAt,
                completedAt = record.completedAt
            )
        }
    }
}
