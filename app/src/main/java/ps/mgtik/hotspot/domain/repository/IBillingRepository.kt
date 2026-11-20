package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.*

/**
 * IBillingRepository - عقد إدارة الفواتير والدفعات والتقارير (§4.16 - §4.20)
 * تم التحديث لدعم skipQueue لمنع حلقات التكرار في نظام الـ Offline.
 * skipQueue (معنى ثابت في كل المستودعات): false → تمر العملية عبر الطابور/WorkManager، true → تنفيذ مباشر بلا طابور.
 */
interface IBillingRepository {
    
    // --- الفواتير (Invoices - §4.16) ---
    fun getInvoices(ownerId: String? = null, status: InvoiceStatus? = null): Flow<List<Invoice>>
    fun getInvoiceDetails(invoiceId: String): Flow<Invoice?>
    
    suspend fun updateInvoice(invoiceId: String, fieldsToUpdate: Map<String, Any?>, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun cancelInvoice(invoiceId: String, force: Boolean, resolution: String?, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun reopenCancelledInvoice(invoiceId: String, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun applyInvoiceDiscount(invoiceId: String, discountType: String, discountValue: Long, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun applyOwnerCredit(invoiceId: String, amountMinor: Long, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun unapplyOwnerCredit(invoiceId: String, ledgerEntryId: String, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    
    fun listInvoiceEvents(invoiceId: String): Flow<List<InvoiceEventLog>>
    suspend fun getInvoiceForOrder(orderId: String): AppResult<Invoice?>

    // --- الدفعات (Payments - §4.17) ---
    suspend fun recordPayment(invoiceId: String, amountMinor: Long, method: String, reference: String?, notes: String?, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Payment>
    suspend fun updatePayment(paymentId: String, fieldsToUpdate: Map<String, Any?>, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun voidPayment(paymentId: String, reason: String, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun refundPayment(paymentId: String, refundAmountMinor: Long, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>

    fun listInvoicePayments(invoiceId: String): Flow<List<Payment>>
    fun listAllPayments(ownerId: String? = null, dateRange: Pair<String, String>? = null): Flow<List<Payment>>

    // --- التقارير (Reports - §4.19) ---
    suspend fun getBillingReport(reportType: String, dateRange: Pair<String, String>, ownerId: String? = null, profileId: String? = null): AppResult<Map<String, Any?>>

    // --- الإعدادات (Settings - §4.20) ---
    fun getBillingSettings(): Flow<BillingSettings?>
    suspend fun updateBillingSettings(fieldsToUpdate: Map<String, Any?>, idempotencyKey: String): AppResult<Unit>

    // --- لوحة التحكم المالية ---
    fun getFinancialDashboard(): Flow<FinancialDashboard>
}
