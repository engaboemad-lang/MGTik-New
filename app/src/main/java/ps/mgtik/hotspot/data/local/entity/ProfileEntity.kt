package ps.mgtik.hotspot.data.local.entity

import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileStatus
import ps.mgtik.hotspot.domain.usecase.ClassificationRules

/**
 * ProfileEntity - السجل المحلي المخبأ للبروفايلات (§6.2).
 * تم تحديثه ليشمل الحقول المالية والتقنية الكاملة المستخلصة من الراوتر والسحابة.
 */
@Entity(tableName = "cached_profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "linked_profile_name") val linkedProfileName: String,
    @ColumnInfo(name = "display_name_en") val displayNameEn: String,
    @ColumnInfo(name = "display_name_ar") val displayNameAr: String,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "classification_key") val classificationKey: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "version") val version: Int,
    
    // تقنيات الراوتر
    @ColumnInfo(name = "rate_limit_tx_kbps") val rateLimitTxKbps: Int,
    @ColumnInfo(name = "rate_limit_rx_kbps") val rateLimitRxKbps: Int,
    @ColumnInfo(name = "validity_hours") val validityHours: Int,
    @ColumnInfo(name = "quota_mb") val quotaMb: Int,
    @ColumnInfo(name = "shared_users") val sharedUsers: Int,
    
    // ماليات السحابة
    @ColumnInfo(name = "price_minor") val priceMinor: Long,

    // قيود استخدام الحسابات (إدارية في MGTik)؛ null = غير مفروض
    @ColumnInfo(name = "limit_uptime_seconds") val limitUptimeSeconds: Long? = null,
    @ColumnInfo(name = "limit_transfer_bytes") val limitTransferBytes: Long? = null,
    // الإعدادات التي يحدّدها البروفايل بنفسه (نص مفصول بفواصل، مثل "speed,shared_users")؛ null = بروفايل قديم لا يرث شيئاً
    @ColumnInfo(name = "own_settings") val ownSettings: String? = null,
    @ColumnInfo(name = "validity_start") val validityStart: String = "first_auth",
    @ColumnInfo(name = "validity_unlimited") val validityUnlimited: Boolean = false,
    @ColumnInfo(name = "shared_unlimited") val sharedUnlimited: Boolean = false,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
) {
    /** null عند حالة ناقصة/مجهولة: لا تُحوَّل إلى ACTIVE (باقة قابلة للاستخدام) ولا إلى MISSING. */
    fun toDomainOrNull(): Profile? {
        val parsedStatus = status.toEnumOrNull<ProfileStatus>() ?: return null
        return Profile(
            id = id,
            linkedProfileName = linkedProfileName,
            displayNameEn = displayNameEn,
            displayNameAr = displayNameAr,
            description = description,
            categoryId = categoryId,
            classificationKey = classificationKey,
            status = parsedStatus,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
            rateLimitTxKbps = rateLimitTxKbps,
            rateLimitRxKbps = rateLimitRxKbps,
            validityHours = validityHours,
            quotaMb = quotaMb,
            sharedUsers = sharedUsers,
            priceMinor = priceMinor,
            limitUptimeSeconds = limitUptimeSeconds,
            limitTransferBytes = limitTransferBytes,
            ownSettings = ClassificationRules.ownSettingsFromCsv(ownSettings),
            validityStart = validityStart,
            validityUnlimited = validityUnlimited,
            sharedUnlimited = sharedUnlimited
        )
    }

    companion object {
        fun fromDomain(profile: Profile): ProfileEntity {
            return ProfileEntity(
                id = profile.id,
                linkedProfileName = profile.linkedProfileName,
                displayNameEn = profile.displayNameEn,
                displayNameAr = profile.displayNameAr,
                description = profile.description,
                categoryId = profile.categoryId,
                classificationKey = profile.classificationKey,
                status = profile.status.name,
                createdAt = profile.createdAt,
                updatedAt = profile.updatedAt,
                version = profile.version,
                rateLimitTxKbps = profile.rateLimitTxKbps,
                rateLimitRxKbps = profile.rateLimitRxKbps,
                validityHours = profile.validityHours,
                quotaMb = profile.quotaMb,
                sharedUsers = profile.sharedUsers,
                priceMinor = profile.priceMinor,
                limitUptimeSeconds = profile.limitUptimeSeconds,
                limitTransferBytes = profile.limitTransferBytes,
                ownSettings = profile.ownSettings?.let { ClassificationRules.ownSettingsToCsv(it) },
                validityStart = profile.validityStart,
                validityUnlimited = profile.validityUnlimited,
                sharedUnlimited = profile.sharedUnlimited
            )
        }
    }
}
