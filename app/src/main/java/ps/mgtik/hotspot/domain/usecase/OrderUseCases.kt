package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.Batch
import ps.mgtik.hotspot.domain.model.Order
import ps.mgtik.hotspot.domain.model.OrderStatus
import ps.mgtik.hotspot.domain.repository.IOrderRepository
import javax.inject.Inject

class GetOrdersUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    operator fun invoke(): Flow<List<Order>> = orderRepository.getOrders()
}

class GetOrderDetailUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    operator fun invoke(orderId: String): Flow<Order?> = orderRepository.getOrderById(orderId)
}

class GetOrderBatchesUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    operator fun invoke(orderId: String): Flow<List<Batch>> = orderRepository.getBatchesForOrder(orderId)
}

class CreateOrderUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    suspend operator fun invoke(
        ownerId: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Order> = orderRepository.createOrder(ownerId, idempotencyKey)
}

class CloseOrderUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    suspend operator fun invoke(
        orderId: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = orderRepository.closeOrder(orderId, idempotencyKey)
}

class ReopenOrderUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    suspend operator fun invoke(
        orderId: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = orderRepository.reopenOrder(orderId, idempotencyKey)
}

class CancelDraftOrderUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    suspend operator fun invoke(
        orderId: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = orderRepository.cancelDraftOrder(orderId, idempotencyKey)
}

class CorrectOrderOwnerUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    suspend operator fun invoke(
        orderId: String,
        newOwnerId: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = orderRepository.correctOrderOwner(orderId, newOwnerId, idempotencyKey)
}

class RetryMissedBillingUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    suspend operator fun invoke(
        orderId: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = orderRepository.retryMissedBilling(orderId, idempotencyKey)
}

class UpdateOrderStatusUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    suspend operator fun invoke(orderId: String, status: OrderStatus): AppResult<Unit> =
        orderRepository.updateOrderStatus(orderId, status)
}

class CancelOrderUseCase @Inject constructor(private val orderRepository: IOrderRepository) {
    suspend operator fun invoke(orderId: String, reason: String): AppResult<Unit> =
        orderRepository.cancelOrder(orderId, reason)
}
