package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.onSuccess
import ps.mgtik.hotspot.core.result.safeApiCall
import ps.mgtik.hotspot.data.cloud.source.CloudSubscriptionDataSource
import ps.mgtik.hotspot.data.local.cache.LocalCacheManager
import ps.mgtik.hotspot.data.subscription.SubscriptionEngine
import ps.mgtik.hotspot.domain.model.NewSubscription
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionInvoice
import ps.mgtik.hotspot.domain.model.SubscriptionOperation
import ps.mgtik.hotspot.domain.model.SubscriptionPayment
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import ps.mgtik.hotspot.domain.repository.ISubscriptionRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudSubscriptionDataSource,
    private val engine: SubscriptionEngine,
    private val localCache: LocalCacheManager
) : ISubscriptionRepository {

    override fun getSubscriptions(): Flow<List<Subscription>> =
        cloudDataSource.getSubscriptions().onEach { subs -> localCache.saveSubscriptions(subs) }

    override fun getSubscriptionInvoices(subscriptionId: String): Flow<List<SubscriptionInvoice>> = cloudDataSource.getInvoices(subscriptionId)
    override fun getSubscriptionPayments(subscriptionId: String): Flow<List<SubscriptionPayment>> = cloudDataSource.getPayments(subscriptionId)
    override fun getAllSubscriptionPayments(): Flow<List<SubscriptionPayment>> = cloudDataSource.getAllPayments()
    override fun getSubscriptionOperations(subscriptionId: String): Flow<List<SubscriptionOperation>> = cloudDataSource.getOperations(subscriptionId)

    override suspend fun createSubscription(draft: NewSubscription, idempotencyKey: String): AppResult<Subscription> =
        engine.create(draft, idempotencyKey).cache()

    override suspend fun renewSubscription(subscriptionId: String, idempotencyKey: String): AppResult<Subscription> =
        engine.renew(subscriptionId, idempotencyKey).cache()

    override suspend fun suspendSubscription(subscriptionId: String): AppResult<Subscription> = engine.suspend(subscriptionId).cache()
    override suspend fun resumeSubscription(subscriptionId: String): AppResult<Subscription> = engine.resume(subscriptionId).cache()
    override suspend fun cancelSubscription(subscriptionId: String): AppResult<Subscription> = engine.cancel(subscriptionId).cache()
    override suspend fun retrySubscriptionSync(subscriptionId: String): AppResult<Subscription> = engine.retrySync(subscriptionId).cache()
    override suspend fun refreshSubscriptionFromRouter(subscriptionId: String): AppResult<Subscription> = engine.refreshFromRouter(subscriptionId).cache()

    override suspend fun recordSubscriptionPayment(invoiceId: String, amountMinor: Long, method: String, idempotencyKey: String): AppResult<Unit> =
        engine.recordPayment(invoiceId, amountMinor, method, idempotencyKey)

    override suspend fun voidSubscriptionPayment(paymentId: String): AppResult<Unit> = engine.voidPayment(paymentId)

    override suspend fun findActiveSubscriptionByUsername(username: String): AppResult<Subscription?> = safeApiCall {
        cloudDataSource.readAll().firstOrNull { it.customerUsername == username && it.status != SubscriptionStatus.CANCELLED }
    }

    private suspend fun AppResult<Subscription>.cache(): AppResult<Subscription> =
        onSuccess { sub -> localCache.saveSubscriptions(listOf(sub)) }
}
