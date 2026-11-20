package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.local.dao.AuditLogDao
import ps.mgtik.hotspot.data.local.entity.AuditLogEntity
import ps.mgtik.hotspot.data.mapping.SyncErrorReporter
import ps.mgtik.hotspot.domain.model.AuditLog
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.repository.IAuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AuditRepositoryImpl - تنفيذ سجلات التدقيق الهندسي والعملياتي (§10).
 */
@Singleton
class AuditRepositoryImpl @Inject constructor(
    private val auditLogDao: AuditLogDao,
    private val authRepository: IAuthRepository,
    private val reporter: SyncErrorReporter
) : IAuditRepository {

    override fun getLogs(severity: LogSeverity?): Flow<List<AuditLog>> {
        val flow = if (severity == null) {
            auditLogDao.getAllLogs()
        } else {
            auditLogDao.getLogsBySeverity(severity.name)
        }
        return flow.map { entities ->
            entities.mapNotNull { e ->
                // لا يُبلَّغ هنا بسجل تدقيق مرفوض عبر audit_logs نفسها دون حارس: المُبلِّغ يكرر مرة لكل معرّف
                e.toDomainOrNull() ?: run { reporter.rejected("audit_logs", e.id, "unknown severity '${e.severity}'"); null }
            }
        }
    }

    override suspend fun logAction(action: String, detail: String, severity: LogSeverity): AppResult<Unit> {
        val user = authRepository.getCurrentUser()
        val log = AuditLog(
            action = action,
            detail = detail,
            userId = user?.uid ?: "system",
            severity = severity
        )
        auditLogDao.insertLog(AuditLogEntity.fromDomain(log))
        return AppResult.Success(Unit)
    }

    override suspend fun clearOldLogs(days: Int): AppResult<Unit> {
        val timestamp = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        auditLogDao.deleteOldLogs(timestamp)
        return AppResult.Success(Unit)
    }
}
