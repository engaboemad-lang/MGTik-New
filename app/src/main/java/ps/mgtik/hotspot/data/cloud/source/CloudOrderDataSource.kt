package ps.mgtik.hotspot.data.cloud.source

import ps.mgtik.hotspot.core.util.toSortableIso
import ps.mgtik.hotspot.data.mapping.SyncErrorReporter
import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.domain.model.Batch
import ps.mgtik.hotspot.domain.model.BatchStatus
import ps.mgtik.hotspot.domain.model.Order
import ps.mgtik.hotspot.domain.model.OrderStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudOrderDataSource - مصدر بيانات طلبات التوليد والدفعات (§4.15)
 */
@Singleton
class CloudOrderDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val reporter: SyncErrorReporter
) {
    private val ordersCollection = firestore.collection("orders")
    private val batchesCollection = firestore.collection("batches")

    fun getOrdersFlow(): Flow<List<Order>> {
        return ordersCollection.orderBy("createdAt", Query.Direction.DESCENDING).snapshots().map { snapshot ->
            snapshot.documents.mapNotNull { doc -> mapToOrder(doc) }
        }
    }

    fun getOrderByIdFlow(orderId: String): Flow<Order?> {
        return ordersCollection.document(orderId).snapshots().map { doc ->
            if (doc.exists()) mapToOrder(doc) else null
        }
    }

    fun getBatchesForOrderFlow(orderId: String): Flow<List<Batch>> {
        return batchesCollection.whereEqualTo("orderId", orderId).snapshots().map { snapshot ->
            snapshot.documents.mapNotNull { doc ->
                // المحرك السحابي (CloudGateway) يكتب الدفعة بأسماء snake_case؛ كان هذا القارئ يبحث عن camelCase فقط
                // فتظهر الكميات والأسعار وأسماء الباقات أصفاراً/فارغة. نقرأ snake_case أولاً ثم camelCase القديم.
                val rawStatus = doc.getString("status")
                val status = batchStatusOf(rawStatus)
                if (status == null) {
                    reporter.rejected("batches", doc.id, if (rawStatus.isNullOrBlank()) "missing status" else "unknown status '$rawStatus'")
                    return@mapNotNull null
                }
                fun str(snake: String, camel: String) = doc.getString(snake) ?: doc.getString(camel) ?: ""
                fun num(snake: String, camel: String) = doc.getLong(snake) ?: doc.getLong(camel) ?: 0L
                Batch(
                    id = doc.id,
                    businessId = doc.getString("businessId") ?: "",
                    orderId = orderId,
                    ownerId = str("owner_id", "ownerId"),
                    ownerNameSnapshot = doc.getString("ownerNameSnapshot") ?: "",
                    cloudProfileId = str("cloud_profile_id", "cloudProfileId"),
                    profileNameSnapshot = str("profile_name", "profileNameSnapshot"),
                    requestedQuantity = num("requested_quantity", "requestedQuantity").toInt(),
                    successfulQuantity = num("successful_quantity", "successfulQuantity").toInt(),
                    status = status,
                    createdAt = (doc.getTimestamp("created_at") ?: doc.getTimestamp("createdAt"))?.toDate()?.toSortableIso() ?: "",
                    totalAmountMinor = num("total_amount_minor", "totalAmountMinor"),
                    // سعر البطاقة المثبَّت لحظة التحضير (لا السعر الحالي للباقة)
                    wholesalePriceSnapshot = num("price_per_voucher_minor", "wholesalePriceMinorSnapshot")
                )
            }
        }
    }

    /** حالات المحرك (RUNNING/QUEUED/RETRYING) تُعرض كـ PENDING؛ CANCELLED كـ FAILED؛ المجهولة ترفض السجل. */
    private fun batchStatusOf(raw: String?): BatchStatus? = when (raw?.uppercase()) {
        "COMPLETED" -> BatchStatus.COMPLETED
        "FAILED", "CANCELLED" -> BatchStatus.FAILED
        "PENDING", "QUEUED", "RETRYING", "RUNNING" -> BatchStatus.PENDING
        else -> null
    }

    suspend fun createOrder(ownerId: String, idempotencyKey: String): AppResult<Order> = safeApiCall {
        val orderId = UUID.randomUUID().toString()
        val orderData = hashMapOf(
            "ownerId" to ownerId,
            "status" to OrderStatus.DRAFT.name,
            "idempotencyKey" to idempotencyKey,
            "createdAt" to FieldValue.serverTimestamp(),
            "linkedBatchIds" to emptyList<String>()
        )
        ordersCollection.document(orderId).set(orderData).await()
        
        // جلب المستند بعد الإنشاء للحصول على التوقيت الزمني للسيرفر
        val doc = ordersCollection.document(orderId).get().await()
        mapToOrder(doc) ?: throw Exception("Created order $orderId has a missing or unknown status")
    }

    suspend fun closeOrder(orderId: String, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        firestore.runTransaction { transaction ->
            val orderRef = ordersCollection.document(orderId)
            val orderSnap = transaction.get(orderRef)
            
            if (orderSnap.getString("status") == OrderStatus.CLOSED.name) {
                return@runTransaction
            }

            transaction.update(orderRef, mapOf(
                "status" to OrderStatus.CLOSED.name,
                "closedAt" to FieldValue.serverTimestamp(),
                "idempotencyKey" to idempotencyKey
            ))
        }.await()
    }

    suspend fun updateOrderStatus(orderId: String, status: OrderStatus): AppResult<Unit> = safeApiCall {
        ordersCollection.document(orderId).update("status", status.name).await()
    }

    suspend fun cancelOrder(orderId: String, reason: String): AppResult<Unit> = safeApiCall {
        ordersCollection.document(orderId).update(
            mapOf(
                "status" to OrderStatus.CANCELLED.name,
                "cancellationReason" to reason,
                "cancelledAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun reopenOrder(orderId: String, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        ordersCollection.document(orderId).update(
            mapOf(
                "status" to OrderStatus.REOPENED.name,
                "idempotencyKey" to idempotencyKey,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun cancelDraftOrder(orderId: String, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        ordersCollection.document(orderId).update(
            mapOf(
                "status" to OrderStatus.CANCELLED.name,
                "idempotencyKey" to idempotencyKey,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun correctOrderOwner(orderId: String, newOwnerId: String, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        ordersCollection.document(orderId).update(
            mapOf(
                "ownerId" to newOwnerId,
                "idempotencyKey" to idempotencyKey,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun retryMissedBilling(orderId: String, idempotencyKey: String): AppResult<Unit> = safeApiCall {
        // يتم تنفيذ منطق إعادة التسعير في السحابة
        ordersCollection.document(orderId).update("idempotencyKey", idempotencyKey).await()
    }

    /** null (مع SYNC_ERROR) إن كانت status ناقصة/مجهولة: لا DRAFT مُفترضة ولا استثناء يُسقط الـ Flow. */
    private fun mapToOrder(doc: com.google.firebase.firestore.DocumentSnapshot): Order? {
        val rawStatus = doc.getString("status")
        val status = rawStatus.toEnumOrNull<OrderStatus>()
        if (status == null) {
            reporter.rejected("orders", doc.id, if (rawStatus.isNullOrBlank()) "missing status" else "unknown status '$rawStatus'")
            return null
        }
        return Order(
            id = doc.id,
            ownerId = doc.getString("ownerId") ?: "",
            status = status,
            linkedBatchIds = doc.get("linkedBatchIds") as? List<String> ?: emptyList(),
            invoiceId = doc.getString("invoiceId"),
            createdAt = doc.getTimestamp("createdAt")?.toDate()?.toSortableIso() ?: "",
            closedAt = doc.getTimestamp("closedAt")?.toDate()?.toSortableIso(),
            totalCards = doc.getLong("totalCards")?.toInt() ?: 0,
            totalAmountMinor = doc.getLong("totalAmountMinor") ?: 0,
            createdBy = doc.getString("createdBy") ?: "",
            businessId = doc.getString("businessId") ?: "",
            ownerNameSnapshot = doc.getString("ownerNameSnapshot") ?: ""
        )
    }
}
