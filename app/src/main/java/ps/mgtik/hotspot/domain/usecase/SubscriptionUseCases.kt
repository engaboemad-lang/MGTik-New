package ps.mgtik.hotspot.domain.usecase

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.NewSubscription
import ps.mgtik.hotspot.domain.model.SaleContext
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionInvoice
import ps.mgtik.hotspot.domain.model.SubscriptionOperation
import ps.mgtik.hotspot.domain.model.SubscriptionPayment
import ps.mgtik.hotspot.domain.repository.ISubscriptionRepository
import ps.mgtik.hotspot.domain.subscription.SubscriptionRules
import javax.inject.Inject

/** إنشاء اشتراك جديد. مفتاح العملية = معرّف الاشتراك: إعادة المحاولة لا تنشئ اشتراكاً مكرراً. */
class CreateSubscriptionUseCase @Inject constructor(
    private val repository: ISubscriptionRepository,
    private val profileRepository: ps.mgtik.hotspot.domain.repository.IProfileRepository
) {
    suspend operator fun invoke(draft: NewSubscription, idempotencyKey: String = IdempotencyKeyProvider.generateKey()): AppResult<Subscription> {
        // السعر من خدمة التسعير المركزية (بيع مباشر = تجزئة؛ سعر خاص صالح للمالك أولاً)، لا مما عرضته الشاشة.
        // الخصم يبقى كما أدخله المستخدم ويُحسب على السعر المعتمد. إعادة التنفيذ بنفس المفتاح تعيد السجل الأول (لقطته محفوظة فيه).
        val quote = when (val q = profileRepository.resolveQuote(draft.profileId, draft.ownerId.takeIf { it.isNotBlank() }, SaleContext.RETAIL)) {
            is AppResult.Success -> q.data
            is AppResult.Error -> return AppResult.Error(q.error)
            else -> return AppResult.Error(ApiError.unknownError("Unexpected loading state"))
        }
        val priced = draft.copy(
            priceMinor = quote.unitMinor,
            priceCurrency = quote.currency,
            priceSource = quote.source.name.lowercase(),
            pricingType = quote.context.wire,
            priceCategoryId = quote.categoryId.orEmpty(),
            priceRuleId = quote.ruleId.orEmpty()
        )
        // التحقق نفسه يتكرر داخل المحرّك قبل الكتابة (المرجع)، وهنا فقط لرد فوري بلا رحلة سحابة
        SubscriptionRules.validateNew(priced, java.time.Instant.now())?.let {
            return AppResult.Error(ApiError(code = it, message = it, retryable = false))
        }
        return repository.createSubscription(priced, idempotencyKey)
    }
}

class RenewSubscriptionUseCase @Inject constructor(private val repository: ISubscriptionRepository) {
    suspend operator fun invoke(subscriptionId: String, idempotencyKey: String = IdempotencyKeyProvider.generateKey()) =
        repository.renewSubscription(subscriptionId, idempotencyKey)
}

class SuspendSubscriptionUseCase @Inject constructor(private val repository: ISubscriptionRepository) {
    suspend operator fun invoke(subscriptionId: String) = repository.suspendSubscription(subscriptionId)
}

class ResumeSubscriptionUseCase @Inject constructor(private val repository: ISubscriptionRepository) {
    suspend operator fun invoke(subscriptionId: String) = repository.resumeSubscription(subscriptionId)
}

class CancelSubscriptionUseCase @Inject constructor(private val repository: ISubscriptionRepository) {
    suspend operator fun invoke(subscriptionId: String) = repository.cancelSubscription(subscriptionId)
}

class RetrySubscriptionSyncUseCase @Inject constructor(private val repository: ISubscriptionRepository) {
    suspend operator fun invoke(subscriptionId: String) = repository.retrySubscriptionSync(subscriptionId)
}

class RefreshSubscriptionFromRouterUseCase @Inject constructor(private val repository: ISubscriptionRepository) {
    suspend operator fun invoke(subscriptionId: String) = repository.refreshSubscriptionFromRouter(subscriptionId)
}

class RecordSubscriptionPaymentUseCase @Inject constructor(private val repository: ISubscriptionRepository) {
    suspend operator fun invoke(
        invoiceId: String,
        amountMinor: Long,
        method: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = repository.recordSubscriptionPayment(invoiceId, amountMinor, method, idempotencyKey)
}

class VoidSubscriptionPaymentUseCase @Inject constructor(private val repository: ISubscriptionRepository) {
    suspend operator fun invoke(paymentId: String) = repository.voidSubscriptionPayment(paymentId)
}

/** تفاصيل اشتراك: فواتيره ومدفوعاته وسجل أوامر الراوتر (تدفقات لحظية). */
class GetSubscriptionDetailsUseCase @Inject constructor(private val repository: ISubscriptionRepository) {
    fun invoices(id: String): Flow<List<SubscriptionInvoice>> = repository.getSubscriptionInvoices(id)
    fun payments(id: String): Flow<List<SubscriptionPayment>> = repository.getSubscriptionPayments(id)
    fun operations(id: String): Flow<List<SubscriptionOperation>> = repository.getSubscriptionOperations(id)
}
