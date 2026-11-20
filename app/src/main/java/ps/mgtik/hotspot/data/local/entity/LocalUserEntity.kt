package ps.mgtik.hotspot.data.local.entity

import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.*

/**
 * LocalUserEntity - مطابق لـ LocalUserRecord (§6.9)
 * تم التدقيق: إضافة تأمين التحويل (Safe Enum Mapping) لمنع الانهيار.
 */
@Entity(
    tableName = "local_users",
    // ORDER BY created_at DESC في getUsersFilteredFlow (انظر ملاحظة الفهارس في InvoiceEntity)
    indices = [Index(value = ["created_at"])]
)
data class LocalUserEntity(
    @PrimaryKey val recordUuid: String,
    @ColumnInfo(name = "router_username") val routerUsername: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "batch_id") val batchId: String?,
    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
    @ColumnInfo(name = "last_action") val lastAction: String?,
    @ColumnInfo(name = "last_action_result") val lastActionResult: String?,
    @ColumnInfo(name = "last_action_at") val lastActionAt: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    // القيود الفعلية المطبقة عند إنشاء الحساب (لقطة)؛ null = لم يُفرض قيد
    @ColumnInfo(name = "limit_uptime_seconds") val limitUptimeSeconds: Long? = null,
    @ColumnInfo(name = "limit_transfer_bytes") val limitTransferBytes: Long? = null,
    @ColumnInfo(name = "system_class", defaultValue = "NORMAL") val systemClass: String = SystemClass.NORMAL.name,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
) {
    /** null إن كانت أي من الحالات الثلاث ناقصة/مجهولة: لا تحويل إلى ACTIVE/GENERATED/SYNCED. */
    fun toDomainOrNull(): LocalUserRecord? {
        val parsedStatus = status.toEnumOrNull<UserRecordStatus>() ?: return null
        val parsedOrigin = origin.toEnumOrNull<UserOrigin>() ?: return null
        val parsedSync = syncStatus.toEnumOrNull<UserSyncStatus>() ?: return null
        val parsedClass = SystemClass.fromWire(systemClass) ?: return null
        return LocalUserRecord(
            recordUuid = recordUuid,
            routerUsername = routerUsername,
            ownerId = ownerId,
            status = parsedStatus,
            batchId = batchId,
            origin = parsedOrigin,
            syncStatus = parsedSync,
            lastAction = lastAction,
            lastActionResult = lastActionResult,
            lastActionAt = lastActionAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            limitUptimeSeconds = limitUptimeSeconds,
            limitTransferBytes = limitTransferBytes,
            systemClass = parsedClass
        )
    }

    companion object {
        fun fromDomain(record: LocalUserRecord): LocalUserEntity {
            return LocalUserEntity(
                recordUuid = record.recordUuid,
                routerUsername = record.routerUsername,
                ownerId = record.ownerId,
                status = record.status.name,
                batchId = record.batchId,
                origin = record.origin.name,
                syncStatus = record.syncStatus.name,
                lastAction = record.lastAction,
                lastActionResult = record.lastActionResult,
                lastActionAt = record.lastActionAt,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
                limitUptimeSeconds = record.limitUptimeSeconds,
                limitTransferBytes = record.limitTransferBytes,
                systemClass = record.systemClass.name
            )
        }
    }
}
