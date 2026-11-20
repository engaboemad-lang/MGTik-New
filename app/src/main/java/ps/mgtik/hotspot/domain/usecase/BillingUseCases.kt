package ps.mgtik.hotspot.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IBillingRepository
import ps.mgtik.hotspot.domain.repository.INotificationPublisher

/**
 * GetInvoicesUseCase
 * جلب قائمة الفواتير مع إمكانية التصفية حسب المالك أو الحالة (§4.16)
 */
class GetInvoicesUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    operator fun invoke(ownerId: String? = null, status: InvoiceStatus? = null): Flow<List<Invoice>> =
        billingRepository.getInvoices(ownerId, status)
}

/**
 * GetInvoiceDetailsUseCase
 * جلب تفاصيل فاتورة محددة (§4.16)
 */
class GetInvoiceDetailsUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    operator fun invoke(invoiceId: String): Flow<Invoice?> =
        billingRepository.getInvoiceDetails(invoiceId)
}

/**
 * UpdateInvoiceUseCase
 * تحديث حقول الفاتورة المسموح بها (تاريخ الاستحقاق، الملاحظات) (§4.16)
 */
class UpdateInvoiceUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    suspend operator fun invoke(
        invoiceId: String,
        fieldsToUpdate: Map<String, Any?>,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = billingRepository.updateInvoice(invoiceId, fieldsToUpdate, idempotencyKey, skipQueue = true)
}

/**
 * CancelInvoiceUseCase
 * إلغاء الفاتورة مع تحديد طبيعة المعالجة (رصيد أو استرداد) في حال كانت مدفوعة (§4.16)
 */
class CancelInvoiceUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    suspend operator fun invoke(
        invoiceId: String,
        force: Boolean = false,
        resolution: String? = null,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = billingRepository.cancelInvoice(invoiceId, force, resolution, idempotencyKey, skipQueue = true)
}

/**
 * ReopenCancelledInvoiceUseCase
 * إعادة فتح فاتورة ملغاة (§4.16)
 */
class ReopenCancelledInvoiceUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    suspend operator fun invoke(
        invoiceId: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = billingRepository.reopenCancelledInvoice(invoiceId, idempotencyKey, skipQueue = true)
}

/**
 * ApplyInvoiceDiscountUseCase
 * تطبيق خصم (ثابت أو نسبة) على الفاتورة (§4.16)
 */
class ApplyInvoiceDiscountUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    suspend operator fun invoke(
        invoiceId: String,
        discountType: String,
        discountValue: Long,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> {
        if (discountValue < 0) return AppResult.Error(ApiError.validationError("قيمة الخصم لا يمكن أن تكون سالبة"))
        // النسبة فوق 100% كانت تنتج إجمالياً سالباً في الفاتورة (طبقة البيانات تحسب ولا تتحقق)
        if (discountType == "percentage" && discountValue > 100) {
            return AppResult.Error(ApiError.validationError("نسبة الخصم لا يمكن أن تتجاوز 100%"))
        }
        return billingRepository.applyInvoiceDiscount(invoiceId, discountType, discountValue, idempotencyKey, skipQueue = true)
    }
}

/**
 * ApplyOwnerCreditUseCase
 * تطبيق رصيد المالك المتاح على الفاتورة لتسويتها أو جزء منها (§4.16)
 */
class ApplyOwnerCreditUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    suspend operator fun invoke(
        invoiceId: String,
        amountMinor: Long,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> {
        if (amountMinor <= 0) return AppResult.Error(ApiError.validationError("مبلغ الرصيد المطبَّق يجب أن يكون أكبر من صفر"))
        return billingRepository.applyOwnerCredit(invoiceId, amountMinor, idempotencyKey, skipQueue = true)
    }
}

/**
 * UnapplyOwnerCreditUseCase
 * إلغاء تطبيق رصيد مالك سابق على فاتورة (§4.16)
 */
class UnapplyOwnerCreditUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    suspend operator fun invoke(
        invoiceId: String,
        ledgerEntryId: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = billingRepository.unapplyOwnerCredit(invoiceId, ledgerEntryId, idempotencyKey, skipQueue = true)
}

/**
 * ListInvoiceEventsUseCase
 * جلب سجل الأحداث والتدقيق المالي لفاتورة محددة (§4.16)
 */
class ListInvoiceEventsUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    operator fun invoke(invoiceId: String): Flow<List<InvoiceEventLog>> =
        billingRepository.listInvoiceEvents(invoiceId)
}

/**
 * RecordPaymentUseCase
 * تسجيل دفعة مالية جديدة لفاتورة (§4.17)
 */
class RecordPaymentUseCase @Inject constructor(
    private val billingRepository: IBillingRepository,
    private val notificationPublisher: INotificationPublisher
) {
    suspend operator fun invoke(
        invoiceId: String,
        amountMinor: Long,
        method: String,
        reference: String? = null,
        notes: String? = null,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Payment> {
        if (amountMinor <= 0) return AppResult.Error(ApiError.validationError("مبلغ الدفعة يجب أن يكون أكبر من صفر"))
        val result = billingRepository.recordPayment(invoiceId, amountMinor, method, reference, notes, idempotencyKey, skipQueue = true)
        if (result is AppResult.Success) {
            notificationPublisher.paymentRecorded(result.data.id, invoiceId, amountMinor)
        }
        return result
    }
}

/**
 * VoidPaymentUseCase
 * إبطال دفعة مسجلة خطأً (§4.17)
 */
class VoidPaymentUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    suspend operator fun invoke(
        paymentId: String,
        reason: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = billingRepository.voidPayment(paymentId, reason, idempotencyKey, skipQueue = true)
}

/**
 * RefundPaymentUseCase
 * استرجاع مبلغ مدفوع فعلاً (Refund) (§4.17)
 */
class RefundPaymentUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    suspend operator fun invoke(
        paymentId: String,
        refundAmountMinor: Long,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> {
        // المبلغ السالب كان سيزيد المدفوع بدل أن يخفضه (طبقة البيانات تطرحه مباشرة)
        if (refundAmountMinor <= 0) return AppResult.Error(ApiError.validationError("مبلغ الاسترجاع يجب أن يكون أكبر من صفر"))
        return billingRepository.refundPayment(paymentId, refundAmountMinor, idempotencyKey, skipQueue = true)
    }
}

class GetInvoicePaymentsUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    operator fun invoke(invoiceId: String): Flow<List<Payment>> = billingRepository.listInvoicePayments(invoiceId)
}

/**
 * ListAllPaymentsUseCase
 * جلب كافة الدفعات المسجلة لأغراض التدقيق والفلترة (§4.17)
 */
class ListAllPaymentsUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    operator fun invoke(ownerId: String? = null, dateRange: Pair<String, String>? = null): Flow<List<Payment>> =
        billingRepository.listAllPayments(ownerId, dateRange)
}

/**
 * GetBillingReportUseCase
 * توليد التقارير المالية والإدارية الموحدة (§4.19)
 */
class GetBillingReportUseCase @Inject constructor(
    private val billingRepository: IBillingRepository,
    private val routerRepository: ps.mgtik.hotspot.domain.repository.IRouterRepository,
    private val generationRepository: ps.mgtik.hotspot.domain.repository.IGenerationRepository,
    private val userRepository: ps.mgtik.hotspot.domain.repository.IUserRepository,
    private val ownerRepository: ps.mgtik.hotspot.domain.repository.IOwnerRepository
) {
    /**
     * المالية (sales_summary / owner_performance) من مستودع الفوترة؛ بقية الأنواع تُركَّب هنا من
     * مصادرها الحقيقية. كل القيم المرجعة مقروءة فعلاً من مصدرها، ولا تُختلق قيم عند غياب البيانات.
     */
    suspend operator fun invoke(
        reportType: String,
        dateRange: Pair<String, String>,
        ownerId: String? = null,
        profileId: String? = null
    ): AppResult<Map<String, Any?>> = when (reportType) {
        "router_uptime" -> routerSnapshot()
        "inventory_status" -> inventory(dateRange, ownerId, profileId)
        "owner_balances" -> ownerBalances()
        else -> billingRepository.getBillingReport(reportType, dateRange, ownerId, profileId)
    }

    /** لا يوجد سجل تاريخي لتوفّر الراوتر في أي طبقة؛ التقرير لقطة صحة حالية حقيقية (وليس نسبة توفّر مخترعة). */
    private suspend fun routerSnapshot(): AppResult<Map<String, Any?>> = try {
        val h = withTimeoutOrNull(ROUTER_HEALTH_TIMEOUT_MS) { routerRepository.getRouterHealth().first { !it.isChecking } }
        if (h == null) {
            AppResult.Error(ApiError(code = ApiError.CODE_TIMEOUT, message = "Router health check timed out", retryable = true))
        } else AppResult.Success(mapOf(
            "isOnline" to h.isOnline,
            "uptime" to h.uptimeString,
            "cpuLoadPercent" to h.cpuLoadPercent,
            "freeMemoryMb" to h.freeMemoryMb,
            "lastCheckedAt" to h.lastCheckedAt
        ))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.mapThrowable(e))
    }

    /** لقطة راهنة لأرصدة الملاك وديونهم من حساباتهم وفواتيرهم الفعلية. */
    private suspend fun ownerBalances(): AppResult<Map<String, Any?>> = try {
        val owners = ownerRepository.getOwnersSummary().first()
        val invoices = billingRepository.getInvoices().first()
        AppResult.Success(BillingRules.ownerBalances(owners, invoices))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.mapThrowable(e))
    }

    private companion object {
        const val ROUTER_HEALTH_TIMEOUT_MS = 15_000L
    }

    private suspend fun inventory(
        dateRange: Pair<String, String>, ownerId: String?, profileId: String?
    ): AppResult<Map<String, Any?>> = try {
        val batches = generationRepository.observeBatches().first().filter {
            (ownerId == null || it.ownerId == ownerId) &&
                (profileId == null || it.cloudProfileId == profileId) &&
                ps.mgtik.hotspot.core.util.isWithinDateRange(it.createdAt, dateRange.first, dateRange.second)
        }
        val users = userRepository.listManagedUsers(ownerId = ownerId).first()
        // المؤرشفون في مجموعة منفصلة وغير مرتبطة بفلتر المالك؛ فشل عدّهم يحذف الحقل فقط ولا يُفشل التقرير
        val archived = if (ownerId == null) (userRepository.countArchivedUsers() as? AppResult.Success)?.data else null
        val requested = batches.sumOf { it.requestedQuantity }
        val successful = batches.sumOf { it.successfulQuantity }
        AppResult.Success(buildMap<String, Any?> {
            put("batchesCount", batches.size)
            put("requestedCards", requested)
            put("generatedCards", successful)
            put("failedCards", batches.sumOf { it.failedQuantity })
            BillingRules.generationSuccessPercent(requested, successful)?.let { put("successRatePercent", it) }
            put("completedBatches", batches.count { it.status == ps.mgtik.hotspot.domain.model.VoucherBatchStatus.COMPLETED })
            put("failedBatches", batches.count { it.status == ps.mgtik.hotspot.domain.model.VoucherBatchStatus.FAILED })
            put("activeUsers", users.count { it.status == ps.mgtik.hotspot.domain.model.UserRecordStatus.ACTIVE })
            put("disabledUsers", users.count { it.status == ps.mgtik.hotspot.domain.model.UserRecordStatus.DISABLED })
            put("expiredUsers", users.count { it.status == ps.mgtik.hotspot.domain.model.UserRecordStatus.EXPIRED })
            archived?.let { put("archivedUsers", it) }
            put("byProfile", batches.groupBy { it.profileName }
                .map { (name, list) -> mapOf("label" to name, "generatedCards" to list.sumOf { it.successfulQuantity }, "count" to list.size) }
                .sortedByDescending { it["generatedCards"] as Int })
        })
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.mapThrowable(e))
    }
}

/**
 * GetBillingSettingsUseCase
 * جلب إعدادات الفوترة والشركة (§4.20)
 */
class GetBillingSettingsUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    operator fun invoke(): Flow<BillingSettings?> = billingRepository.getBillingSettings()
}

/**
 * UpdateBillingSettingsUseCase
 * تحديث إعدادات الفوترة (§4.20)
 */
class UpdateBillingSettingsUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    suspend operator fun invoke(
        fieldsToUpdate: Map<String, Any?>,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> = billingRepository.updateBillingSettings(fieldsToUpdate, idempotencyKey)
}

/**
 * FetchFinancialDashboardUseCase
 * جلب مؤشرات لوحة التحكم المالية (§4.10)
 */
class FetchFinancialDashboardUseCase @Inject constructor(private val billingRepository: IBillingRepository) {
    operator fun invoke(): Flow<FinancialDashboard> = billingRepository.getFinancialDashboard()
}
