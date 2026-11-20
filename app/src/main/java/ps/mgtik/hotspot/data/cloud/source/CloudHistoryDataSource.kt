package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.domain.model.HistoricalBatch
import ps.mgtik.hotspot.domain.model.HistoryFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudHistoryDataSource - مصدر بيانات الأرشيف وسجل العمليات والدفعات التاريخية (S21)
 */
@Singleton
class CloudHistoryDataSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val historyCollection = firestore.collection("historical_batches")

    fun getHistoricalBatchesFlow(filter: HistoryFilter): Flow<List<HistoricalBatch>> {
        return historyCollection.orderBy("generated_at", Query.Direction.DESCENDING).snapshots().map { snapshot ->
            val list = snapshot.documents.map { doc ->
                HistoricalBatch(
                    batchId = doc.getString("batch_id") ?: doc.id,
                    orderId = doc.getString("order_id") ?: "",
                    ownerName = doc.getString("owner_name") ?: "",
                    profileName = doc.getString("profile_name") ?: "",
                    cardsCount = doc.getLong("cards_count")?.toInt() ?: 0,
                    totalAmountMinor = doc.getLong("total_amount_minor") ?: 0L,
                    generatedAt = doc.getString("generated_at") ?: "",
                    archiveDate = doc.getString("archive_date") ?: "",
                    status = doc.getString("status") ?: "archived"
                )
            }
            if (filter.query.isBlank()) list
            else list.filter {
                it.batchId.contains(filter.query, ignoreCase = true) ||
                        it.ownerName.contains(filter.query, ignoreCase = true) ||
                        it.profileName.contains(filter.query, ignoreCase = true)
            }
        }
    }

    fun getHistoricalBatchByIdFlow(batchId: String): Flow<HistoricalBatch?> {
        return historyCollection.document(batchId).snapshots().map { doc ->
            if (doc.exists()) {
                HistoricalBatch(
                    batchId = doc.getString("batch_id") ?: doc.id,
                    orderId = doc.getString("order_id") ?: "",
                    ownerName = doc.getString("owner_name") ?: "",
                    profileName = doc.getString("profile_name") ?: "",
                    cardsCount = doc.getLong("cards_count")?.toInt() ?: 0,
                    totalAmountMinor = doc.getLong("total_amount_minor") ?: 0L,
                    generatedAt = doc.getString("generated_at") ?: "",
                    archiveDate = doc.getString("archive_date") ?: "",
                    status = doc.getString("status") ?: "archived"
                )
            } else null
        }
    }

    suspend fun archiveBatch(batchId: String): AppResult<Unit> = safeApiCall {
        historyCollection.document(batchId).update(
            mapOf(
                "status" to "archived",
                "archive_date" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            )
        ).await()
    }

    suspend fun exportHistoryToCsv(filter: HistoryFilter): AppResult<String> = safeApiCall {
        val snapshot = historyCollection.get().await()
        val builder = StringBuilder("معرف الدفعة,رقم الطلب,الموزع,الباقة,عدد الكروت,الإجمالي,تاريخ التوليد,تاريخ الأرشفة\n")
        snapshot.documents.forEach { doc ->
            builder.append("${doc.id},${doc.getString("order_id")},\"${doc.getString("owner_name")}\",\"${doc.getString("profile_name")}\",${doc.getLong("cards_count")},${doc.getLong("total_amount_minor")},${doc.getString("generated_at")},${doc.getString("archive_date")}\n")
        }
        builder.toString()
    }
}
