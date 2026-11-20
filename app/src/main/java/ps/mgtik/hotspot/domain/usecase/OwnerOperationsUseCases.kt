package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.OwnerStatus
import ps.mgtik.hotspot.domain.repository.IBillingRepository
import ps.mgtik.hotspot.domain.repository.IOwnerRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import ps.mgtik.hotspot.domain.repository.IProfileRepository

/** تعديل بيانات المالك (الاسم/الهاتف) بعد التحقق؛ تكرار الهاتف يفحصه المصدر السحابي (المرجع النهائي). */
class UpdateOwnerProfileUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    suspend operator fun invoke(ownerId: String, name: String, phone: String): AppResult<Unit> {
        if (!OwnerInputValidator.isNameValid(name) || !OwnerInputValidator.isPhoneValid(phone)) {
            return AppResult.Error(ApiError.validationError("Invalid owner name or phone"))
        }
        return ownerRepository.updateOwner(ownerId, mapOf("name" to name.trim(), "phone" to phone.trim()))
    }
}

/** إيقاف/تفعيل/استعادة المالك. الموقوف والمؤرشف لا يُختاران في التوليد (يُفرض في السحابة أيضاً). */
class SetOwnerStatusUseCase @Inject constructor(private val ownerRepository: IOwnerRepository) {
    suspend operator fun invoke(ownerId: String, status: OwnerStatus): AppResult<Unit> =
        ownerRepository.updateOwner(ownerId, mapOf("status" to status.name))
}

/**
 * تسوية فواتير المالك المستحقة من الأقدم للأحدث: إما من رصيده (خصم ذرّي لكل فاتورة) أو بتحصيل نقدي.
 * تنفيذ تسلسلي مباشر؛ يتوقف عند أول خطأ ويُرجعه (ما سُوّي قبله يبقى صحيحاً ماليّاً لأن كل فاتورة معاملة مستقلة)،
 * وإعادة التشغيل تحسب التوزيع من المستحق الحالي فلا تكرر ما سُوّي.
 */
class SettleOwnerDuesUseCase @Inject constructor(private val billingRepository: IBillingRepository) {

    /** @return عدد الفواتير التي سُدّد منها شيء. */
    suspend fun fromCredit(availableCreditMinor: Long, invoices: List<Invoice>): AppResult<Int> {
        val plan = OwnerCreditRules.allocateOldestFirst(
            budgetMinor = availableCreditMinor,
            dues = OwnerStatement.toDueInvoices(invoices)
        )
        if (plan.isEmpty()) return AppResult.Error(ApiError.validationError("Nothing to settle"))
        var done = 0
        for (a in plan) {
            when (val r = billingRepository.applyOwnerCredit(a.invoiceId, a.amountMinor, IdempotencyKeyProvider.generateKey(), skipQueue = true)) {
                is AppResult.Success -> done++
                is AppResult.Error -> return r
                else -> Unit
            }
        }
        return AppResult.Success(done)
    }

    suspend fun collectCash(amountMinor: Long, method: String, invoices: List<Invoice>): AppResult<Int> {
        val dues = OwnerStatement.toDueInvoices(invoices)
        if (amountMinor <= 0 || amountMinor > dues.sumOf { it.dueMinor }) {
            return AppResult.Error(ApiError.validationError("Amount exceeds total due"))
        }
        var done = 0
        for (a in OwnerCreditRules.allocateOldestFirst(amountMinor, dues)) {
            when (val r = billingRepository.recordPayment(a.invoiceId, a.amountMinor, method, null, null, IdempotencyKeyProvider.generateKey(), skipQueue = true)) {
                is AppResult.Success -> done++
                is AppResult.Error -> return r
                else -> Unit
            }
        }
        return AppResult.Success(done)
    }
}

/** دفتر أسعار وكيل: استثناءاته السعرية مقابل أسعار الفئات الأساسية (مصدر السعر النهائي لكل فئة). */
class GetOwnerPriceBookUseCase @Inject constructor(private val profileRepository: IProfileRepository) {
    operator fun invoke(ownerId: String): Flow<OwnerPriceBook> =
        combine(profileRepository.getProfileCategories(), profileRepository.getOwnerPricingRules(ownerId)) { cats, rules ->
            OwnerPriceBookBuilder.build(ownerId, cats, rules)
        }
}
