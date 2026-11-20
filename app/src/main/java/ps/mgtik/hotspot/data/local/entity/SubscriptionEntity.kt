package ps.mgtik.hotspot.data.local.entity

import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionStatus

/*
 * حالة (docs/LOCAL_STORAGE_DECISIONS.md §4): مكتوبة ولا تُقرأ حالياً (getCachedSubscriptionsFlow بلا مستدعٍ).
 * ليست "ميتة نهائياً": قرار دورة حياتها (fallback للعمل دون اتصال أم إيقاف الكتابة) مؤجَّل.
 * العمود subscription_id مرادف لـ id (يُملأ منه عند الفراغ)؛ حذفه ينتظر Migration.
 */
@Entity(tableName = "cached_subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "subscription_id") val subscriptionId: String,
    @ColumnInfo(name = "customer_uuid") val customerUuid: String,
    @ColumnInfo(name = "customer_username") val customerUsername: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "owner_name") val ownerName: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "profile_name") val profileName: String,
    @ColumnInfo(name = "profile_classification_snapshot") val profileClassificationSnapshot: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "next_renewal") val nextRenewal: String,
    @ColumnInfo(name = "billing_cycle") val billingCycle: String,
    @ColumnInfo(name = "price_minor") val priceMinor: Long,
    @ColumnInfo(name = "discount_minor") val discountMinor: Long,
    @ColumnInfo(name = "final_price_minor") val finalPriceMinor: Long,
    @ColumnInfo(name = "price_currency", defaultValue = "") val priceCurrency: String = "",
    @ColumnInfo(name = "price_source", defaultValue = "") val priceSource: String = "",
    @ColumnInfo(name = "pricing_type", defaultValue = "") val pricingType: String = "",
    @ColumnInfo(name = "price_category_id", defaultValue = "") val priceCategoryId: String = "",
    @ColumnInfo(name = "price_rule_id", defaultValue = "") val priceRuleId: String = "",
    @ColumnInfo(name = "discount_type") val discountType: String?,
    @ColumnInfo(name = "discount_value") val discountValue: Long,
    @ColumnInfo(name = "auto_renewal") val autoRenewal: Boolean,
    @ColumnInfo(name = "last_invoice_id") val lastInvoiceId: String?,
    @ColumnInfo(name = "notes") val notes: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "uptime_used") val uptimeUsed: String = "",
    @ColumnInfo(name = "bytes_in") val bytesIn: Long = 0L,
    @ColumnInfo(name = "bytes_out") val bytesOut: Long = 0L,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
) {
    /** null عند حالة ناقصة/مجهولة: لا يتحول اشتراك تالف إلى ACTIVE. */
    fun toDomainOrNull(): Subscription? {
        val parsedStatus = status.toEnumOrNull<SubscriptionStatus>() ?: return null
        return Subscription(
            id = id,
            subscriptionId = subscriptionId.ifBlank { id }, // مرادف لـ id حين يكون العمود فارغاً
            customerUuid = customerUuid,
            customerUsername = customerUsername,
            ownerId = ownerId,
            ownerName = ownerName,
            profileId = profileId,
            profileName = profileName,
            profileClassificationSnapshot = profileClassificationSnapshot,
            status = parsedStatus,
            startDate = startDate,
            nextRenewal = nextRenewal,
            billingCycle = billingCycle,
            priceMinor = priceMinor,
            discountMinor = discountMinor,
            finalPriceMinor = finalPriceMinor,
            priceCurrency = priceCurrency,
            priceSource = priceSource,
            pricingType = pricingType,
            priceCategoryId = priceCategoryId,
            priceRuleId = priceRuleId,
            discountType = discountType,
            discountValue = discountValue,
            autoRenewal = autoRenewal,
            lastInvoiceId = lastInvoiceId,
            notes = notes,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
            uptimeUsed = uptimeUsed,
            bytesIn = bytesIn,
            bytesOut = bytesOut
        )
    }

    companion object {
        fun fromDomain(sub: Subscription): SubscriptionEntity {
            return SubscriptionEntity(
                id = sub.id,
                subscriptionId = sub.subscriptionId.ifBlank { sub.id },
                customerUuid = sub.customerUuid,
                customerUsername = sub.customerUsername,
                ownerId = sub.ownerId,
                ownerName = sub.ownerName,
                profileId = sub.profileId,
                profileName = sub.profileName,
                profileClassificationSnapshot = sub.profileClassificationSnapshot,
                status = sub.status.name,
                startDate = sub.startDate,
                nextRenewal = sub.nextRenewal,
                billingCycle = sub.billingCycle,
                priceMinor = sub.priceMinor,
                discountMinor = sub.discountMinor,
                finalPriceMinor = sub.finalPriceMinor,
                priceCurrency = sub.priceCurrency,
                priceSource = sub.priceSource,
                pricingType = sub.pricingType,
                priceCategoryId = sub.priceCategoryId,
                priceRuleId = sub.priceRuleId,
                discountType = sub.discountType,
                discountValue = sub.discountValue,
                autoRenewal = sub.autoRenewal,
                lastInvoiceId = sub.lastInvoiceId,
                notes = sub.notes,
                createdAt = sub.createdAt,
                updatedAt = sub.updatedAt,
                version = sub.version,
                uptimeUsed = sub.uptimeUsed,
                bytesIn = sub.bytesIn,
                bytesOut = sub.bytesOut
            )
        }
    }
}
