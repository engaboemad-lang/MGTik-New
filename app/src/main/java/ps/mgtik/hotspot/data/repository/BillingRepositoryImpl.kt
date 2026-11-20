package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.map
import ps.mgtik.hotspot.data.offline.CancelInvoicePayload
import ps.mgtik.hotspot.data.offline.CommandCodec
import ps.mgtik.hotspot.data.offline.InvoiceDiscountPayload
import ps.mgtik.hotspot.data.offline.InvoiceIdPayload
import ps.mgtik.hotspot.data.offline.InvoiceUpdatePayload
import ps.mgtik.hotspot.data.offline.OwnerCreditPayload
import ps.mgtik.hotspot.data.offline.PaymentUpdatePayload
import ps.mgtik.hotspot.data.offline.RecordPaymentPayload
import ps.mgtik.hotspot.data.offline.RefundPaymentPayload
import ps.mgtik.hotspot.data.offline.UnapplyCreditPayload
import ps.mgtik.hotspot.data.offline.VoidPaymentPayload
import ps.mgtik.hotspot.data.cloud.source.CloudBillingDataSource
import ps.mgtik.hotspot.data.cloud.source.CloudSubscriptionDataSource
import ps.mgtik.hotspot.data.local.cache.LocalCacheManager
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IBillingRepository
import ps.mgtik.hotspot.domain.repository.IOfflineCommandRepository
import ps.mgtik.hotspot.domain.repository.IOfflineScheduler
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.usecase.BillingRules
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BillingRepositoryImpl - تطبيق مستودع الفوترة المحدث (§4.16 - §4.20).
 * skipQueue: false → طابور/WorkManager، true → تنفيذ مباشر (المعنى ثابت في كل المستودعات).
 */
@Singleton
class BillingRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudBillingDataSource,
    private val subscriptionSource: CloudSubscriptionDataSource,
    private val localCache: LocalCacheManager,
    private val offlineRepo: IOfflineCommandRepository,
    private val offlineScheduler: IOfflineScheduler,
    private val auditRepo: IAuditRepository
) : IBillingRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // مهمة مزامنة لكل (مالك، حالة): استدعاء بمعاملات أخرى لم يعد يلغي مزامنة غيره
    private val invoicesJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    override fun getInvoices(ownerId: String?, status: InvoiceStatus?): Flow<List<Invoice>> {
        refreshInvoicesInBackground(ownerId, status)
        return localCache.getCachedInvoicesFlow(ownerId, status)
    }

    private fun refreshInvoicesInBackground(ownerId: String?, status: InvoiceStatus?) {
        val key = "${ownerId.orEmpty()}|${status?.name.orEmpty()}"
        invoicesJobs.remove(key)?.cancel()
        invoicesJobs[key] = repositoryScope.launch {
            try {
                cloudDataSource.getInvoices(ownerId, status).collect { cloudList ->
                    localCache.saveInvoices(cloudList)
                }
            } catch (e: Exception) {
                auditRepo.logAction("SYNC_ERROR", "Invoices refresh failed: ${e.message}", LogSeverity.ERROR)
            }
        }
    }

    override fun getInvoiceDetails(invoiceId: String): Flow<Invoice?> =
        cloudDataSource.getInvoiceDetails(invoiceId) // استماع مباشر للسحابة لضمان دقة البيانات

    /**
     * الملخص المالي من الفواتير والدفعات الفعلية (BillingRules.totals) + الإيراد الشهري المتكرر من الاشتراكات الفعّالة.
     * لا مستند ملخص مخزَّن يمكن أن يتأخر أو يغيب فتظهر أصفار؛ ولا يُخفى فشل التحميل بقيم افتراضية (الخطأ يصل للمستهلك).
     */
    override fun getFinancialDashboard(): Flow<FinancialDashboard> =
        combine(
            cloudDataSource.getInvoices(null, null),
            cloudDataSource.listAllPayments(null, null),
            subscriptionSource.getSubscriptions()
        ) { invoices, payments, subscriptions -> BillingRules.dashboard(invoices, payments, subscriptions) }

    // المعنى الثابت لـ skipQueue: false → طابور/WorkManager، true → تنفيذ مباشر (المنفّذ يستدعي بـ true).

    private suspend fun queue(type: OfflineCommandType, payload: String, key: String): AppResult<Unit> {
        offlineRepo.addCommand(OfflineCommand(type = type, payloadJson = payload, idempotencyKey = key))
        offlineScheduler.scheduleSync()
        auditRepo.logAction("QUEUE_${type.name}", "Queued financial command $type", LogSeverity.INFO)
        return AppResult.Success(Unit)
    }

    /**
     * في مسار الطابور لا يوجد Payment حقيقي بعد؛ نعيد دفعة مؤقتة بنفس المعرّف الذي سيُنشأ به السجل سحابياً
     * (CloudBillingDataSource يستخدم idempotencyKey معرّفاً للدفعة)، فيمكن للواجهة تتبعها.
     */
    override suspend fun recordPayment(
        invoiceId: String, amountMinor: Long, method: String, reference: String?, notes: String?, idempotencyKey: String, skipQueue: Boolean
    ): AppResult<Payment> {
        if (!skipQueue) {
            val payload = RecordPaymentPayload(invoiceId, amountMinor, method, reference, notes)
            return queue(OfflineCommandType.RECORD_PAYMENT, CommandCodec.encode(payload), idempotencyKey).map {
                Payment(id = idempotencyKey, invoiceId = invoiceId, amountMinor = amountMinor, method = method, reference = reference, notes = notes)
            }
        }
        auditRepo.logAction("RECORD_PAYMENT", "Payment of $amountMinor for invoice $invoiceId", LogSeverity.INFO)
        return cloudDataSource.recordPayment(invoiceId, amountMinor, method, reference, notes, idempotencyKey)
    }

    override suspend fun voidPayment(paymentId: String, reason: String, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        if (!skipQueue) return queue(OfflineCommandType.VOID_PAYMENT, CommandCodec.encode(VoidPaymentPayload(paymentId, reason)), idempotencyKey)
        auditRepo.logAction("VOID_PAYMENT", "Voiding payment $paymentId for: $reason", LogSeverity.WARNING)
        return cloudDataSource.voidPayment(paymentId, reason, idempotencyKey)
    }

    override suspend fun refundPayment(paymentId: String, refundAmountMinor: Long, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        if (!skipQueue) return queue(OfflineCommandType.REFUND_PAYMENT, CommandCodec.encode(RefundPaymentPayload(paymentId, refundAmountMinor)), idempotencyKey)
        auditRepo.logAction("REFUND_PAYMENT", "Refunding $refundAmountMinor from payment $paymentId", LogSeverity.CRITICAL)
        return cloudDataSource.refundPayment(paymentId, refundAmountMinor, idempotencyKey)
    }

    override suspend fun cancelInvoice(invoiceId: String, force: Boolean, resolution: String?, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        if (!skipQueue) return queue(OfflineCommandType.CANCEL_INVOICE, CommandCodec.encode(CancelInvoicePayload(invoiceId, force, resolution)), idempotencyKey)
        auditRepo.logAction("CANCEL_INVOICE", "Cancelling invoice $invoiceId, force=$force", LogSeverity.WARNING)
        return cloudDataSource.cancelInvoice(invoiceId, force, resolution, idempotencyKey)
    }

    override suspend fun applyInvoiceDiscount(invoiceId: String, discountType: String, discountValue: Long, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        if (!skipQueue) return queue(OfflineCommandType.APPLY_INVOICE_DISCOUNT, CommandCodec.encode(InvoiceDiscountPayload(invoiceId, discountType, discountValue)), idempotencyKey)
        auditRepo.logAction("APPLY_DISCOUNT", "Discount on $invoiceId: $discountType=$discountValue", LogSeverity.INFO)
        return cloudDataSource.applyInvoiceDiscount(invoiceId, discountType, discountValue, idempotencyKey)
    }

    override fun listAllPayments(ownerId: String?, dateRange: Pair<String, String>?): Flow<List<Payment>> =
        cloudDataSource.listAllPayments(ownerId, dateRange)

    override suspend fun updateInvoice(invoiceId: String, fieldsToUpdate: Map<String, Any?>, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        if (!skipQueue) {
            val payload = InvoiceUpdatePayload(invoiceId, CommandCodec.fieldsToJson(fieldsToUpdate))
            return queue(OfflineCommandType.UPDATE_INVOICE, CommandCodec.encode(payload), idempotencyKey)
        }
        return cloudDataSource.updateInvoice(invoiceId, fieldsToUpdate, idempotencyKey)
    }

    override suspend fun reopenCancelledInvoice(invoiceId: String, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        if (!skipQueue) return queue(OfflineCommandType.REOPEN_INVOICE, CommandCodec.encode(InvoiceIdPayload(invoiceId)), idempotencyKey)
        auditRepo.logAction("REOPEN_INVOICE", "Reopening cancelled invoice $invoiceId", LogSeverity.INFO)
        return cloudDataSource.reopenInvoice(invoiceId, idempotencyKey)
    }

    override suspend fun applyOwnerCredit(invoiceId: String, amountMinor: Long, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        if (!skipQueue) return queue(OfflineCommandType.APPLY_OWNER_CREDIT, CommandCodec.encode(OwnerCreditPayload(invoiceId, amountMinor)), idempotencyKey)
        auditRepo.logAction("APPLY_CREDIT", "Applying credit $amountMinor to invoice $invoiceId", LogSeverity.INFO)
        // معاملة واحدة: دفعة credit + خصم رصيد المالك + قيد INVOICE_CHARGE (لا دفعة عادية بلا خصم)
        return cloudDataSource.applyCreditToInvoice(invoiceId, amountMinor, idempotencyKey)
    }

    override suspend fun unapplyOwnerCredit(invoiceId: String, ledgerEntryId: String, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        if (!skipQueue) return queue(OfflineCommandType.UNAPPLY_OWNER_CREDIT, CommandCodec.encode(UnapplyCreditPayload(invoiceId, ledgerEntryId)), idempotencyKey)
        auditRepo.logAction("UNAPPLY_CREDIT", "Unapplying credit from invoice $invoiceId", LogSeverity.WARNING)
        // ledgerEntryId = معرّف الدفعة نفسه (الدفعة والقيد يشتركان في مفتاح العملية)؛ الإلغاء يعيد الرصيد ذرّياً ويكتب CHARGE_REVERSAL
        return voidPayment(ledgerEntryId, "Unapply Credit", idempotencyKey, skipQueue = true)
    }

    override fun listInvoicePayments(invoiceId: String): Flow<List<Payment>> = cloudDataSource.listInvoicePayments(invoiceId)

    override fun listInvoiceEvents(invoiceId: String): Flow<List<InvoiceEventLog>> = cloudDataSource.listInvoiceEvents(invoiceId)
    
    override suspend fun getInvoiceForOrder(orderId: String): AppResult<Invoice?> = cloudDataSource.getInvoiceForOrder(orderId)
    
    override suspend fun updatePayment(paymentId: String, fieldsToUpdate: Map<String, Any?>, idempotencyKey: String, skipQueue: Boolean): AppResult<Unit> {
        if (!skipQueue) {
            val payload = PaymentUpdatePayload(paymentId, CommandCodec.fieldsToJson(fieldsToUpdate))
            return queue(OfflineCommandType.UPDATE_PAYMENT, CommandCodec.encode(payload), idempotencyKey)
        }
        auditRepo.logAction("UPDATE_PAYMENT", "Updating payment record $paymentId", LogSeverity.INFO)
        return cloudDataSource.updatePayment(paymentId, fieldsToUpdate, idempotencyKey)
    }

    /**
     * التقارير المالية تُحسب من بيانات السحابة الفعلية (الفواتير والدفعات) ضمن المدى؛ لا قيم افتراضية.
     * أنواع التقارير غير المالية (router_uptime / inventory_status) تُركَّب في GetBillingReportUseCase من مستودعاتها.
     * [profileId] غير مدعوم مالياً: لا تحمل الفاتورة/الدفعة معرّف باقة، فيُرفض صراحة بدل تجاهله بصمت.
     */
    override suspend fun getBillingReport(reportType: String, dateRange: Pair<String, String>, ownerId: String?, profileId: String?): AppResult<Map<String, Any?>> {
        if (profileId != null) {
            return AppResult.Error(ps.mgtik.hotspot.core.result.ApiError(
                code = ps.mgtik.hotspot.core.result.ApiError.CODE_VALIDATION_FAILED,
                message = "Billing reports cannot be filtered by profile"
            ))
        }
        if (reportType != REPORT_SALES_SUMMARY && reportType != REPORT_OWNER_PERFORMANCE) {
            return AppResult.Error(ps.mgtik.hotspot.core.result.ApiError(
                code = ps.mgtik.hotspot.core.result.ApiError.CODE_VALIDATION_FAILED,
                message = "Unsupported billing report type: $reportType"
            ))
        }
        return try {
            val (start, end) = dateRange
            val invoices = cloudDataSource.getInvoices(ownerId, null).first()
                .filter { it.status != InvoiceStatus.CANCELED && ps.mgtik.hotspot.core.util.isWithinDateRange(it.createdAt, start, end) }
            // الصافي المحصَّل: الفعّالة كاملة + المستردة ناقص ما استُرد؛ الملغاة (VOID) خارج التقرير
            val allPayments = cloudDataSource.listAllPayments(ownerId, dateRange).first()
            val payments = allPayments.filter { BillingRules.collectedMinor(it) > 0 }

            if (reportType == REPORT_SALES_SUMMARY) {
                AppResult.Success(mapOf(
                    "invoicesCount" to invoices.size,
                    "totalInvoicedMinor" to invoices.sumOf { it.totalMinor },
                    "totalOutstandingMinor" to invoices.sumOf { BillingRules.outstandingMinor(it) },
                    "paymentsCount" to payments.size,
                    "totalCollectedMinor" to payments.sumOf { BillingRules.collectedMinor(it) },
                    "totalRefundedMinor" to allPayments.filter { it.status == PaymentStatus.REFUNDED }.sumOf { it.refundedMinor },
                    "byMethod" to payments.groupBy { it.method }
                        .map { (method, list) -> mapOf("label" to method, "amountMinor" to list.sumOf { BillingRules.collectedMinor(it) }, "count" to list.size) }
                        .sortedByDescending { it["amountMinor"] as Long }
                ))
            } else {
                val names = invoices.filter { it.ownerNameSnapshot.isNotBlank() }.associate { it.ownerId to it.ownerNameSnapshot }
                val owners = (invoices.map { it.ownerId } + payments.map { it.ownerId }).filter { it.isNotBlank() }.distinct()
                val rows = owners.map { id ->
                    mapOf(
                        "label" to (names[id] ?: ""),
                        "invoicedMinor" to invoices.filter { it.ownerId == id }.sumOf { it.totalMinor },
                        "collectedMinor" to payments.filter { it.ownerId == id }.sumOf { BillingRules.collectedMinor(it) },
                        "outstandingMinor" to invoices.filter { it.ownerId == id }.sumOf { BillingRules.outstandingMinor(it) }
                    )
                }.sortedByDescending { it["collectedMinor"] as Long }
                AppResult.Success(mapOf("ownersCount" to rows.size, "owners" to rows))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Error(ps.mgtik.hotspot.core.result.ApiError.mapThrowable(e))
        }
    }

    private companion object {
        const val REPORT_SALES_SUMMARY = "sales_summary"
        const val REPORT_OWNER_PERFORMANCE = "owner_performance"
    }
    
    override fun getBillingSettings(): Flow<BillingSettings?> = cloudDataSource.getBillingSettings()
    
    override suspend fun updateBillingSettings(fieldsToUpdate: Map<String, Any?>, idempotencyKey: String): AppResult<Unit> = 
        cloudDataSource.updateBillingSettings(fieldsToUpdate, idempotencyKey)
}
