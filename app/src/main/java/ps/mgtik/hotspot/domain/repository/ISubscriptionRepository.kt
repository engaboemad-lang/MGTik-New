package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.NewSubscription
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionInvoice
import ps.mgtik.hotspot.domain.model.SubscriptionOperation
import ps.mgtik.hotspot.domain.model.SubscriptionPayment

/**
 * ISubscriptionRepository - نظام الاشتراكات: كيان إداري مستقل عن المستخدمين العاديين وعن محاسبة الطلبات والملاك.
 * العمليات تغيّر الحالة الإدارية في السحابة ثم تحاول تنفيذ الأمر على الراوتر؛ فشل الراوتر لا يلغي التغيير الإداري
 * (يظهر في execution/pendingOp على الاشتراك المُرجَع).
 */
interface ISubscriptionRepository {
    fun getSubscriptions(): Flow<List<Subscription>>
    fun getSubscriptionInvoices(subscriptionId: String): Flow<List<SubscriptionInvoice>>
    fun getSubscriptionPayments(subscriptionId: String): Flow<List<SubscriptionPayment>>
    /** كل دفعات الاشتراكات عبر الاشتراكات (للتقارير المجمَّعة حسب الفترة). */
    fun getAllSubscriptionPayments(): Flow<List<SubscriptionPayment>>
    fun getSubscriptionOperations(subscriptionId: String): Flow<List<SubscriptionOperation>>

    suspend fun createSubscription(draft: NewSubscription, idempotencyKey: String): AppResult<Subscription>
    suspend fun renewSubscription(subscriptionId: String, idempotencyKey: String): AppResult<Subscription>
    suspend fun suspendSubscription(subscriptionId: String): AppResult<Subscription>
    suspend fun resumeSubscription(subscriptionId: String): AppResult<Subscription>
    suspend fun cancelSubscription(subscriptionId: String): AppResult<Subscription>

    /** إعادة تنفيذ الأمر العالق على الراوتر. */
    suspend fun retrySubscriptionSync(subscriptionId: String): AppResult<Subscription>

    /** قراءة الحالة الفعلية للحساب من الراوتر دون تغيير (لكشف التعارض). */
    suspend fun refreshSubscriptionFromRouter(subscriptionId: String): AppResult<Subscription>

    suspend fun recordSubscriptionPayment(invoiceId: String, amountMinor: Long, method: String, idempotencyKey: String): AppResult<Unit>
    suspend fun voidSubscriptionPayment(paymentId: String): AppResult<Unit>

    /** اشتراك غير ملغى باسم الدخول معيّن (لتجديد الاشتراك من شاشة المستخدمين). */
    suspend fun findActiveSubscriptionByUsername(username: String): AppResult<Subscription?>
}
