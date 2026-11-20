package ps.mgtik.hotspot.data.repository

import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudOrderDataSource
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IOrderRepository
import ps.mgtik.hotspot.domain.repository.IOfflineCommandRepository
import ps.mgtik.hotspot.domain.repository.IOfflineScheduler
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OrderRepositoryImpl - تطبيق مستودع طلبات التوليد والدفعات (§4.15)
 * تم الإصلاح: تفعيل التنفيذ المباشر وتصحيح الازدواجية.
 */
@Singleton
class OrderRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudOrderDataSource,
    private val offlineRepo: IOfflineCommandRepository,
    private val offlineScheduler: IOfflineScheduler,
    private val auditRepo: IAuditRepository
) : IOrderRepository {

    private val gson = Gson()

    override fun getOrders(): Flow<List<Order>> = cloudDataSource.getOrdersFlow()

    override fun getOrderById(orderId: String): Flow<Order?> = cloudDataSource.getOrderByIdFlow(orderId)

    override fun getBatchesForOrder(orderId: String): Flow<List<Batch>> = cloudDataSource.getBatchesForOrderFlow(orderId)

    private suspend fun queueCommand(type: OfflineCommandType, payload: String, key: String) {
        val command = OfflineCommand(
            type = type,
            payloadJson = payload,
            idempotencyKey = key
        )
        offlineRepo.addCommand(command)
        offlineScheduler.scheduleSync()
        
        auditRepo.logAction("ORDER_QUEUE", "Queued order command: $type", LogSeverity.INFO)
    }

    override suspend fun createOrder(ownerId: String, idempotencyKey: String): AppResult<Order> {
        // تم الاعتماد على cloudDataSource مباشرة لضمان العمليات المالية
        return cloudDataSource.createOrder(ownerId, idempotencyKey).also {
            if (it is AppResult.Success) {
                auditRepo.logAction("ORDER_CREATE", "Created new order for owner: $ownerId", LogSeverity.INFO)
            }
        }
    }

    override suspend fun closeOrder(orderId: String, idempotencyKey: String): AppResult<Unit> {
        // تصحيح: استدعاء التنفيذ السحابي المباشر بدل الاكتفاء بالطابور بلا منفذ
        auditRepo.logAction("ORDER_CLOSE", "Closing order: $orderId", LogSeverity.INFO)
        return cloudDataSource.closeOrder(orderId, idempotencyKey)
    }

    override suspend fun reopenOrder(orderId: String, idempotencyKey: String): AppResult<Unit> {
        return cloudDataSource.reopenOrder(orderId, idempotencyKey).also {
            if (it is AppResult.Success) {
                auditRepo.logAction("ORDER_REOPEN", "Reopened order: $orderId", LogSeverity.WARNING)
            }
        }
    }

    override suspend fun cancelDraftOrder(orderId: String, idempotencyKey: String): AppResult<Unit> {
        return cloudDataSource.cancelDraftOrder(orderId, idempotencyKey).also {
            if (it is AppResult.Success) {
                auditRepo.logAction("ORDER_CANCEL", "Cancelled draft order: $orderId", LogSeverity.INFO)
            }
        }
    }

    override suspend fun correctOrderOwner(orderId: String, newOwnerId: String, idempotencyKey: String): AppResult<Unit> {
        // تصحيح الخطأ رقم 11: إزالة الازدواجية في الاستدعاء
        auditRepo.logAction("ORDER_OWNER_CORRECT", "Correcting owner for $orderId to $newOwnerId", LogSeverity.WARNING)
        return cloudDataSource.correctOrderOwner(orderId, newOwnerId, idempotencyKey)
    }

    override suspend fun retryMissedBilling(orderId: String, idempotencyKey: String): AppResult<Unit> {
        return cloudDataSource.retryMissedBilling(orderId, idempotencyKey)
    }

    override suspend fun updateOrderStatus(orderId: String, status: OrderStatus): AppResult<Unit> =
        cloudDataSource.updateOrderStatus(orderId, status)

    override suspend fun cancelOrder(orderId: String, reason: String): AppResult<Unit> =
        cloudDataSource.cancelOrder(orderId, reason).also {
            if (it is AppResult.Success) {
                auditRepo.logAction("ORDER_CANCEL", "Cancelled order $orderId for: $reason", LogSeverity.WARNING)
            }
        }

    override suspend fun refreshOrders(): AppResult<Unit> {
        // يمكن إضافة منطق سحب يدوي هنا إذا لزم الأمر، حالياً Flow يقوم بالمهمة
        return AppResult.Success(Unit)
    }
}
