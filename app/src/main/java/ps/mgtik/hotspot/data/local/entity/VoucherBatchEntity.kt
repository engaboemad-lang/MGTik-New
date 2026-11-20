package ps.mgtik.hotspot.data.local.entity

import ps.mgtik.hotspot.data.mapping.toEnumOrFailSafe
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ps.mgtik.hotspot.domain.model.VoucherBatch
import ps.mgtik.hotspot.domain.model.VoucherBatchStatus

/*
 * حالة (docs/LOCAL_STORAGE_DECISIONS.md §4): مكتوبة ولا تُقرأ حالياً (GenerationRepositoryImpl تكتب فقط، وكل قراءات
 * VoucherBatchDao بلا مستدعٍ). ليست "ميتة نهائياً": قرار دورة حياتها مؤجَّل.
 */
/**
 * VoucherBatchEntity - الكاش المحلي لدفعة الكروت (§6.12).
 * تم التوحيد مع DomainEntities.kt لضمان اتساق البيانات.
 */
@Entity(tableName = "cached_voucher_batches")
data class VoucherBatchEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "batch_number") val batchNumber: String,
    @ColumnInfo(name = "cloud_profile_id") val cloudProfileId: String,
    @ColumnInfo(name = "profile_name") val profileName: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "order_id") val orderId: String,
    @ColumnInfo(name = "business_id") val businessId: String,
    @ColumnInfo(name = "requested_quantity") val requestedQuantity: Int,
    @ColumnInfo(name = "successful_quantity") val successfulQuantity: Int,
    @ColumnInfo(name = "failed_quantity") val failedQuantity: Int,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "price_per_voucher_minor") val pricePerVoucherMinor: Long,
    @ColumnInfo(name = "total_amount_minor") val totalAmountMinor: Long,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): VoucherBatch {
        return VoucherBatch(
            id = id,
            batchNumber = batchNumber,
            cloudProfileId = cloudProfileId,
            profileName = profileName,
            ownerId = ownerId,
            orderId = orderId,
            businessId = businessId,
            requestedQuantity = requestedQuantity,
            successfulQuantity = successfulQuantity,
            failedQuantity = failedQuantity,
            status = status.toEnumOrFailSafe(VoucherBatchStatus.FAILED), // فشل آمن، لا نجاح مُفترض
            createdAt = createdAt,
            pricePerVoucherMinor = pricePerVoucherMinor,
            totalAmountMinor = totalAmountMinor
        )
    }

    companion object {
        fun fromDomain(batch: VoucherBatch): VoucherBatchEntity {
            return VoucherBatchEntity(
                id = batch.id,
                batchNumber = batch.batchNumber,
                cloudProfileId = batch.cloudProfileId,
                profileName = batch.profileName,
                ownerId = batch.ownerId,
                orderId = batch.orderId,
                businessId = batch.businessId,
                requestedQuantity = batch.requestedQuantity,
                successfulQuantity = batch.successfulQuantity,
                failedQuantity = batch.failedQuantity,
                status = batch.status.name,
                createdAt = batch.createdAt,
                pricePerVoucherMinor = batch.pricePerVoucherMinor,
                totalAmountMinor = batch.totalAmountMinor
            )
        }
    }
}
