package ps.mgtik.hotspot.domain.model

import kotlinx.serialization.Serializable

/**
 * HistoricalBatch - تمثيل لدفعة كروت سابقة تم توليدها وأرشفتها (§21).
 */
@Serializable
data class HistoricalBatch(
    val batchId: String,
    val orderId: String,
    val ownerName: String,
    val profileName: String,
    val cardsCount: Int,
    val totalAmountMinor: Long,
    val generatedAt: String,
    val archiveDate: String,
    val status: String = "archived"
)

/**
 * HistoryFilter - معايير البحث والفلترة في سجل الأرشيف.
 */
@Serializable
data class HistoryFilter(
    val query: String = "",
    val startDate: String? = null,
    val endDate: String? = null,
    val ownerId: String? = null
)
