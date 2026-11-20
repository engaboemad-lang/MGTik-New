package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.Batch
import ps.mgtik.hotspot.domain.model.Order
import ps.mgtik.hotspot.domain.model.OrderStatus

/**
 * IOrderRepository - عقد إدارة ومتابعة طلبات التوليد والدفعات (§4.15)
 */
interface IOrderRepository {
    fun getOrders(): Flow<List<Order>>
    fun getOrderById(orderId: String): Flow<Order?>
    fun getBatchesForOrder(orderId: String): Flow<List<Batch>>
    
    suspend fun createOrder(ownerId: String, idempotencyKey: String): AppResult<Order>
    suspend fun closeOrder(orderId: String, idempotencyKey: String): AppResult<Unit>
    suspend fun reopenOrder(orderId: String, idempotencyKey: String): AppResult<Unit>
    suspend fun cancelDraftOrder(orderId: String, idempotencyKey: String): AppResult<Unit>
    suspend fun correctOrderOwner(orderId: String, newOwnerId: String, idempotencyKey: String): AppResult<Unit>
    suspend fun retryMissedBilling(orderId: String, idempotencyKey: String): AppResult<Unit>

    suspend fun refreshOrders(): AppResult<Unit>

    // New methods to match UseCases
    suspend fun updateOrderStatus(orderId: String, status: OrderStatus): AppResult<Unit>
    suspend fun cancelOrder(orderId: String, reason: String): AppResult<Unit>
}
