package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.Batch
import ps.mgtik.hotspot.domain.model.BatchStatus
import ps.mgtik.hotspot.domain.model.BillingSettings
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.NetworkCompanyInfo
import ps.mgtik.hotspot.domain.model.Payment

/**
 * مستند الفاتورة للطباعة/PDF (نقي). كل القيم من الفاتورة المحفوظة ودفعاتها ولقطة سعر دفعات الطلب
 * (price_per_voucher_minor المثبَّت لحظة التحضير) — لا من أسعار الباقات الحالية ولا من تقديرات الواجهة.
 */
data class InvoiceDocLine(val description: String, val quantity: Int, val unitMinor: Long, val totalMinor: Long)

data class InvoiceDocPayment(val dateIso: String, val method: String, val amountMinor: Long, val refundedMinor: Long, val status: ps.mgtik.hotspot.domain.model.PaymentStatus, val reference: String?)

data class InvoiceDocCompany(val name: String, val logoUrl: String?, val address: String, val phone: String, val whatsapp: String)

data class InvoiceDocument(
    val invoiceNumber: String,
    val status: InvoiceStatus,
    val overdue: Boolean,
    val issueDateIso: String,
    val dueDateIso: String?,
    val ownerName: String,
    val ownerBusinessId: String,
    val currency: String,
    /** فارغة + [linesComplete]=false ⇒ لا بنود موثوقة؛ يطبع المولّد سطر الإجمالي فقط ولا يخترع بنوداً. */
    val lines: List<InvoiceDocLine>,
    val linesComplete: Boolean,
    val subtotalMinor: Long,
    val discountMinor: Long,
    val totalMinor: Long,
    val paidMinor: Long,
    val remainingMinor: Long,
    val payments: List<InvoiceDocPayment>,
    val notes: String?,
    val company: InvoiceDocCompany,
    val footerNote: String?
)

object InvoiceDocumentBuilder {
    const val ERR_INVOICE_INCONSISTENT = "ERR_INVOICE_INCONSISTENT"

    sealed interface Result {
        data class Ok(val document: InvoiceDocument) : Result
        data class Error(val code: String) : Result
    }

    fun build(
        invoice: Invoice,
        batches: List<Batch>,
        payments: List<Payment>,
        company: NetworkCompanyInfo,
        billing: BillingSettings?,
        todayIso: String
    ): Result {
        val canceled = invoice.status == InvoiceStatus.CANCELED
        // اتساق أرقام الفاتورة المخزَّنة: لا نطبع نتيجة مالية مضللة
        val discount = invoice.subtotalMinor - invoice.totalMinor
        if (invoice.totalMinor < 0 || invoice.subtotalMinor < 0 || discount < 0 || invoice.amountPaidMinor < 0 ||
            (!canceled && invoice.amountPaidMinor > invoice.totalMinor)
        ) return Result.Error(ERR_INVOICE_INCONSISTENT)
        if (!canceled && invoice.amountDueMinor != invoice.totalMinor - invoice.amountPaidMinor) return Result.Error(ERR_INVOICE_INCONSISTENT)

        val completed = batches.filter { it.status == BatchStatus.COMPLETED && it.successfulQuantity > 0 }
        val lines = completed.map {
            InvoiceDocLine(it.profileNameSnapshot, it.successfulQuantity, it.wholesalePriceSnapshot, it.successfulQuantity.toLong() * it.wholesalePriceSnapshot)
        }
        // البنود تُعرض فقط إن طابق مجموعها الإجمالي الفرعي المخزَّن (وإلا فالبيانات ناقصة/متعارضة)
        val complete = lines.isNotEmpty() && lines.sumOf { it.totalMinor } == invoice.subtotalMinor

        val name = company.companyName.ifBlank { billing?.companyName.orEmpty() }
        val logo = company.logoUrl.ifBlank { billing?.companyLogoUrl.orEmpty() }.ifBlank { null }
        return Result.Ok(
            InvoiceDocument(
                invoiceNumber = invoice.invoiceNumber.ifBlank { invoice.businessId },
                status = invoice.status,
                overdue = BillingRules.isOverdue(invoice, todayIso),
                issueDateIso = invoice.issueDate.ifBlank { invoice.createdAt },
                dueDateIso = invoice.dueDate,
                ownerName = invoice.ownerNameSnapshot,
                ownerBusinessId = invoice.businessId,
                currency = invoice.currency,
                lines = if (complete) lines else emptyList(),
                linesComplete = complete,
                subtotalMinor = invoice.subtotalMinor,
                discountMinor = discount,
                totalMinor = invoice.totalMinor,
                paidMinor = invoice.amountPaidMinor,
                remainingMinor = BillingRules.outstandingMinor(invoice),
                payments = payments.sortedBy { it.createdAt }.map {
                    InvoiceDocPayment(it.createdAt, it.method, it.amountMinor, it.refundedMinor, it.status, it.reference)
                },
                notes = invoice.notes?.takeIf { it.isNotBlank() },
                company = InvoiceDocCompany(name, logo, company.address, company.phone, company.whatsapp),
                footerNote = billing?.invoiceFooterNote?.takeIf { it.isNotBlank() }
            )
        )
    }
}
