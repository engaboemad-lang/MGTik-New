package ps.mgtik.hotspot.data.cloud.source

import ps.mgtik.hotspot.core.util.toSortableIso
import com.google.firebase.firestore.DocumentSnapshot
import ps.mgtik.hotspot.data.mapping.SyncErrorReporter
import ps.mgtik.hotspot.data.mapping.toEnumOrNull
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.Payment
import ps.mgtik.hotspot.domain.model.PaymentStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * تحويل مستندات الفواتير والدفعات (مشترك بين CloudBillingDataSource وCloudOwnerDataSource).
 * الحالة الناقصة أو المجهولة ترفض السجل (null) وتُسجَّل SYNC_ERROR؛ لا يُحوَّل شيء إلى DRAFT/ACTIVE،
 * ويستخدم المستدعون mapNotNull فيبقى بقية القائمة سليماً ولا يسقط الـ Flow.
 */
@Singleton
class BillingDocMapper @Inject constructor(
    private val reporter: SyncErrorReporter
) {
    fun invoice(doc: DocumentSnapshot): Invoice? {
        val rawStatus = doc.getString("status")
        val status = rawStatus.toEnumOrNull<InvoiceStatus>()
        if (status == null) {
            reporter.rejected("invoices", doc.id, reasonFor(rawStatus))
            return null
        }
        return Invoice(
            id = doc.id,
            invoiceNumber = doc.getString("invoiceNumber") ?: "",
            orderId = doc.getString("orderId") ?: "",
            ownerId = doc.getString("ownerId") ?: "",
            status = status,
            subtotalMinor = doc.getLong("subtotalMinor") ?: 0,
            discountType = doc.getString("discountType"),
            discountValueMinor = doc.getLong("discountValueMinor"),
            totalMinor = doc.getLong("totalMinor") ?: 0,
            amountPaidMinor = doc.getLong("amountPaidMinor") ?: 0,
            amountDueMinor = doc.getLong("amountDueMinor") ?: 0,
            dueDate = iso(doc, "dueDate"),
            notes = doc.getString("notes"),
            createdAt = iso(doc, "createdAt") ?: "",
            updatedAt = iso(doc, "updatedAt") ?: "",
            businessId = doc.getString("businessId") ?: "",
            ownerNameSnapshot = doc.getString("ownerNameSnapshot") ?: "",
            currency = doc.getString("currency") ?: "₪",
            paymentMethod = doc.getString("paymentMethod") ?: "",
            isPaid = doc.getBoolean("isPaid") ?: false,
            issueDate = iso(doc, "issueDate") ?: ""
        )
    }

    fun payment(doc: DocumentSnapshot): Payment? {
        val rawStatus = doc.getString("status")
        val status = rawStatus.toEnumOrNull<PaymentStatus>()
        if (status == null) {
            reporter.rejected("payments", doc.id, reasonFor(rawStatus))
            return null
        }
        return Payment(
            id = doc.id,
            invoiceId = doc.getString("invoiceId") ?: "",
            amountMinor = doc.getLong("amountMinor") ?: 0L,
            method = doc.getString("method") ?: "cash",
            reference = doc.getString("reference"),
            notes = doc.getString("notes"),
            status = status,
            ownerId = doc.getString("ownerId") ?: "",
            createdAt = iso(doc, "createdAt") ?: "",
            refundedMinor = doc.getLong("refundAmountMinor") ?: 0L
        )
    }

    private fun reasonFor(raw: String?) =
        if (raw.isNullOrBlank()) "missing status" else "unknown status '$raw'"

    /**
     * createdAt يُكتب بـ FieldValue.serverTimestamp(): قبل تأكيد الخادم يكون null في اللقطة المحلية، فكانت الدفعة
     * الجديدة تُقرأ بتاريخ فارغ وتسقط من «دخل اليوم» حتى يصل التأكيد. ESTIMATE يعيد الوقت المحلي التقديري للكتابة
     * المعلّقة فقط، ثم يُستبدل بقيمة الخادم الفعلية فور وصولها.
     */
    private fun iso(doc: DocumentSnapshot, field: String): String? =
        doc.getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.toSortableIso()
}
