package ps.mgtik.hotspot.data.mapping

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ps.mgtik.hotspot.data.local.dao.AuditLogDao
import ps.mgtik.hotspot.data.local.entity.AuditLogEntity
import ps.mgtik.hotspot.domain.model.AuditLog
import ps.mgtik.hotspot.domain.model.LogSeverity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * يسجّل السجلات المرفوضة (قيمة enum ناقصة/مجهولة...) كـ SYNC_ERROR في audit_logs مع المعرّف والسبب،
 * بدل إسقاطها بصمت. التسجيل مرة واحدة لكل (مصدر، معرّف، سبب) في عمر العملية، لأن المستمعين
 * يعيدون إصدار القائمة كلها عند كل تغيير ولا يجوز أن يغرقوا الجدول بنسخ متطابقة.
 * لا يرمي استثناءً أبداً: الإبلاغ لا يجوز أن يُسقط الـ Flow الذي يحاول حمايته.
 */
@Singleton
class SyncErrorReporter @Inject constructor(
    private val auditLogDao: AuditLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun rejected(source: String, id: String, reason: String) {
        if (reported.size > MAX_REMEMBERED) reported.clear()
        if (!reported.add("$source|$id|$reason")) return
        scope.launch {
            runCatching {
                auditLogDao.insertLog(
                    AuditLogEntity.fromDomain(
                        AuditLog(
                            action = "SYNC_ERROR",
                            detail = "Rejected record $source/$id: $reason",
                            userId = "system",
                            severity = LogSeverity.ERROR
                        )
                    )
                )
            }
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 500
    }
}
