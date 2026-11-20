package ps.mgtik.hotspot.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.*

/**
 * DiscoveryEntity - مطابق لـ DiscoveryRecord (§6.10)
 * يمثل سجل اكتشاف المستخدمين (مفقودين من الراوتر أو غير مسجلين سحابياً).
 */
@Entity(tableName = "discovery_records")
data class DiscoveryEntity(
    @PrimaryKey val discoveryId: String, // type + key
    @ColumnInfo(name = "type") val type: String, // Enum: missing, null
    @ColumnInfo(name = "key") val key: String, // recordUuid or routerUsername
    @ColumnInfo(name = "router_username") val routerUsername: String,
    @ColumnInfo(name = "status") val status: String, // Enum: open, resolved
    @ColumnInfo(name = "first_detected_at") val firstDetectedAt: String,
    @ColumnInfo(name = "last_detected_at") val lastDetectedAt: String,
    @ColumnInfo(name = "resolved_at") val resolvedAt: String?,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
) {
    /** null إن كان type أو status مجهولاً: لا تحويل إلى MISSING/OPEN، فيُتخطّى الصف ويُبلَّغ عنه. */
    fun toDomainOrNull(): DiscoveryRecord? {
        val parsedType = type.toEnumOrNull<DiscoveryType>() ?: return null
        val parsedStatus = status.toEnumOrNull<DiscoveryStatus>() ?: return null
        return DiscoveryRecord(
            discoveryId = discoveryId,
            type = parsedType,
            key = key,
            routerUsername = routerUsername,
            status = parsedStatus,
            firstDetectedAt = firstDetectedAt,
            lastDetectedAt = lastDetectedAt,
            resolvedAt = resolvedAt
        )
    }

    companion object {
        fun fromDomain(record: DiscoveryRecord): DiscoveryEntity {
            return DiscoveryEntity(
                discoveryId = record.discoveryId,
                type = record.type.name.lowercase(),
                key = record.key,
                routerUsername = record.routerUsername,
                status = record.status.name.lowercase(),
                firstDetectedAt = record.firstDetectedAt,
                lastDetectedAt = record.lastDetectedAt,
                resolvedAt = record.resolvedAt
            )
        }
    }
}
