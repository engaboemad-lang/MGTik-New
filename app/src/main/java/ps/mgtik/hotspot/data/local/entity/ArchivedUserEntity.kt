package ps.mgtik.hotspot.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.ArchiveType
import ps.mgtik.hotspot.domain.model.ArchivedUserSnapshot

/**
 * ArchivedUserEntity - مطابق لـ ArchivedUserSnapshot (§6.11)
 * تم تحديثه لضمان حفظ اللقطة الإدارية (administrativeSnapshot) وعدم فقدان البيانات.
 */
@Entity(tableName = "archived_users")
data class ArchivedUserEntity(
    @PrimaryKey val archiveId: String,
    @ColumnInfo(name = "original_record_uuid") val originalRecordUuid: String,
    @ColumnInfo(name = "router_username") val routerUsername: String,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "archived_by") val archivedBy: String?,
    @ColumnInfo(name = "archived_at") val archivedAt: String,
    @ColumnInfo(name = "administrative_snapshot") val administrativeSnapshot: Map<String, String>?, // تمت الإضافة لمنع فقدان البيانات
    /** ArchiveType.name: DISABLED (معطَّل على الراوتر) أو MISSING (غير موجود على الراوتر). */
    @ColumnInfo(name = "archive_type") val archiveType: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): ArchivedUserSnapshot {
        return ArchivedUserSnapshot(
            archiveId = archiveId,
            originalRecordUuid = originalRecordUuid,
            routerUsername = routerUsername,
            administrativeSnapshot = administrativeSnapshot ?: emptyMap(),
            reason = reason,
            archivedBy = archivedBy,
            archivedAt = archivedAt,
            archiveType = ArchiveType.values().firstOrNull { it.name.equals(archiveType, ignoreCase = true) } ?: ArchiveType.DISABLED
        )
    }

    companion object {
        fun fromDomain(domain: ArchivedUserSnapshot): ArchivedUserEntity {
            return ArchivedUserEntity(
                archiveId = domain.archiveId,
                originalRecordUuid = domain.originalRecordUuid,
                routerUsername = domain.routerUsername,
                reason = domain.reason,
                archivedBy = domain.archivedBy,
                archivedAt = domain.archivedAt,
                administrativeSnapshot = domain.administrativeSnapshot,
                archiveType = domain.archiveType.name
            )
        }
    }
}
