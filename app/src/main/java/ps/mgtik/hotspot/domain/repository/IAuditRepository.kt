package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.AuditLog
import ps.mgtik.hotspot.domain.model.LogSeverity

/**
 * IAuditRepository - عقد سجلات التدقيق الهندسي والعملياتي (§10).
 * يضمن تسجيل كافة الإجراءات الحساسة (توليد، حذف، تعديل مالي).
 */
interface IAuditRepository {
    fun getLogs(severity: LogSeverity? = null): Flow<List<AuditLog>>
    suspend fun logAction(action: String, detail: String, severity: LogSeverity = LogSeverity.INFO): AppResult<Unit>
    suspend fun clearOldLogs(days: Int): AppResult<Unit>
}
