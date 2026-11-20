package ps.mgtik.hotspot.data.local.entity

import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.AuditLog
import ps.mgtik.hotspot.domain.model.LogSeverity

// ORDER BY timestamp DESC في كل استعلامات AuditLogDao والجدول غير محدود النمو
@Entity(tableName = "audit_logs", indices = [Index(value = ["timestamp"])])
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val action: String,
    val detail: String,
    val userId: String,
    val severity: String
) {
    /** null عند severity مجهولة (نسخة أحدث): يُرفض الصف ويُسجَّل بدل اختراع مستوى. */
    fun toDomainOrNull(): AuditLog? {
        val parsed = severity.toEnumOrNull<LogSeverity>() ?: return null
        return AuditLog(
            id = id,
            timestamp = timestamp,
            action = action,
            detail = detail,
            userId = userId,
            severity = parsed
        )
    }

    companion object {
        fun fromDomain(log: AuditLog) = AuditLogEntity(
            id = log.id,
            timestamp = log.timestamp,
            action = log.action,
            detail = log.detail,
            userId = log.userId,
            severity = log.severity.name
        )
    }
}
