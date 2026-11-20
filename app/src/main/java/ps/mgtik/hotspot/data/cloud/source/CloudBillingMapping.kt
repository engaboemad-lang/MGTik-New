package ps.mgtik.hotspot.data.cloud.source

import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.CancellationException
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.LogSeverity
import ps.mgtik.hotspot.domain.model.Payment
import ps.mgtik.hotspot.domain.model.PaymentStatus
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * نتيجة تحويل سجل سحابي واحد: إمّا قيمة صالحة أو رفض صريح بسبب (بدل fallback صامت إلى حالة "صحيحة").
 * السجل المرفوض لا يُسقط بقية السجلات؛ يُسجَّل كـ SYNC_ERROR عبر [SyncErrorReporter].
 */
internal sealed class MapOutcome<out T> {
    data class Ok<T>(val value: T) : MapOutcome<T>()
    data class Rejected(val id: String, val reason: String) : MapOutcome<Nothing>()
}

/**
 * يسجّل السجلات المرفوضة في audit_logs بالفعل "SYNC_ERROR" مع المعرّف والسبب.
 * يمنع تكرار نفس (النوع/المعرّف/السبب) خلال عمر العملية، لأن Firestore snapshots تُعيد إصدار نفس
 * السجل التالف عند كل تغيير في المجموعة وكان ذلك سيُغرق السجل.
 */
internal class SyncErrorReporter(private val auditRepo: IAuditRepository) {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    suspend fun report(entity: String, rejected: MapOutcome.Rejected) {
        if (!seen.add("$entity|${rejected.id}|${rejected.reason}")) return
        try {
            auditRepo.logAction(
                "SYNC_ERROR",
                "$entity record rejected: id=${rejected.id}, reason=${rejected.reason}",
                LogSeverity.ERROR
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // فشل التسجيل لا يجوز أن يُسقط تدفق المزامنة نفسه
        }
    }

    /** يُرجع القيمة الصالحة أو null بعد تسجيل الرفض. */
    suspend fun <T> unwrap(entity: String, outcome: MapOutcome<T>): T? = when (outcome) {
        is MapOutcome.Ok -> outcome.value
        is MapOutcome.Rejected -> { report(entity, outcome); null }
    }
}

/** null إذا كانت القيمة ناقصة أو غير معروفة (لا default). الأسماء تُكتب سحابياً بأحرف صغيرة أو كبيرة (CloudGateway يكتب "DUE"/"PAID"). */
internal fun parseInvoiceStatus(raw: String?): InvoiceStatus? {
    val key = raw?.trim().orEmpty()
    if (key.isEmpty()) return null
    return InvoiceStatus.values().firstOrNull { it.name.equals(key, ignoreCase = true) }
}

internal fun parsePaymentStatus(raw: String?): PaymentStatus? {
    val key = raw?.trim().orEmpty()
    if (key.isEmpty()) return null
    return PaymentStatus.values().firstOrNull { it.name.equals(key, ignoreCase = true) }
}

private fun DocumentSnapshot.iso(field: String): String? =
    getTimestamp(field)?.toDate()?.toInstant()?.toString()

/**
 * تحويل فاتورة سحابية. يُرفض السجل عند: حالة مفقودة/غير معروفة، أو غياب totalMinor (سجل مالي ناقص).
 * بقية الحقول الاختيارية تبقى بقيمها الافتراضية كما كانت.
 */
internal fun DocumentSnapshot.toInvoiceOutcome(): MapOutcome<Invoice> {
    val rawStatus = getString("status")
    val status = parseInvoiceStatus(rawStatus)
        ?: return MapOutcome.Rejected(id, "invoice status missing or unknown: '${rawStatus ?: "<null>"}'")
    val total = getLong("totalMinor")
        ?: return MapOutcome.Rejected(id, "invoice totalMinor missing")
    return MapOutcome.Ok(
        Invoice(
            id = id,
            invoiceNumber = getString("invoiceNumber") ?: "",
            orderId = getString("orderId") ?: "",
            ownerId = getString("ownerId") ?: "",
            status = status,
            subtotalMinor = getLong("subtotalMinor") ?: 0,
            discountType = getString("discountType"),
            discountValueMinor = getLong("discountValueMinor"),
            totalMinor = total,
            amountPaidMinor = getLong("amountPaidMinor") ?: 0,
            amountDueMinor = getLong("amountDueMinor") ?: 0,
            dueDate = iso("dueDate"),
            notes = getString("notes"),
            createdAt = iso("createdAt") ?: "",
            updatedAt = iso("updatedAt") ?: "",
            businessId = getString("businessId") ?: "",
            ownerNameSnapshot = getString("ownerNameSnapshot") ?: "",
            currency = getString("currency") ?: "₪",
            paymentMethod = getString("paymentMethod") ?: "",
            isPaid = getBoolean("isPaid") ?: false,
            issueDate = iso("issueDate") ?: ""
        )
    )
}

/** يُرفض السجل عند: حالة مفقودة/غير معروفة، أو غياب invoiceId أو amountMinor. */
internal fun DocumentSnapshot.toPaymentOutcome(): MapOutcome<Payment> {
    val rawStatus = getString("status")
    val status = parsePaymentStatus(rawStatus)
        ?: return MapOutcome.Rejected(id, "payment status missing or unknown: '${rawStatus ?: "<null>"}'")
    val invoiceId = getString("invoiceId")
    if (invoiceId.isNullOrBlank()) return MapOutcome.Rejected(id, "payment invoiceId missing")
    val amount = getLong("amountMinor")
        ?: return MapOutcome.Rejected(id, "payment amountMinor missing")
    return MapOutcome.Ok(
        Payment(
            id = id,
            invoiceId = invoiceId,
            amountMinor = amount,
            method = getString("method") ?: "cash",
            reference = getString("reference"),
            notes = getString("notes"),
            status = status,
            ownerId = getString("ownerId") ?: "",
            createdAt = iso("createdAt") ?: ""
        )
    )
}
