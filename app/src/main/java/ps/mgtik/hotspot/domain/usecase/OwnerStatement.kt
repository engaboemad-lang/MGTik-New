package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.OwnerLedgerEntry
import java.util.Locale

/** كشف حساب المالك: تجميع نقي (بلا Android) + نص قابل للمشاركة. المبالغ بالوحدة الصغرى (قروش/أغورات). */
object OwnerStatement {

    data class Summary(
        val invoicesCount: Int,
        val invoicedMinor: Long,
        val paidMinor: Long,
        val dueMinor: Long,
        val creditInMinor: Long,
        val creditOutMinor: Long
    )

    /** الفواتير المحتسبة: غير الملغاة وغير المسودات. [fromIso] = بداية الفترة (نص مرتَّب) أو null لكل الفترة. */
    fun summarize(invoices: List<Invoice>, ledger: List<OwnerLedgerEntry>, fromIso: String?): Summary {
        val inRange = invoices.filter {
            it.status != InvoiceStatus.CANCELED && it.status != InvoiceStatus.DRAFT &&
                (fromIso == null || it.createdAt >= fromIso)
        }
        val entries = ledger.filter { fromIso == null || it.createdAt >= fromIso }
        return Summary(
            invoicesCount = inRange.size,
            invoicedMinor = inRange.sumOf { it.totalMinor },
            paidMinor = inRange.sumOf { it.amountPaidMinor },
            dueMinor = inRange.sumOf { maxOf(it.amountDueMinor, 0L) },
            creditInMinor = entries.filter { it.amountMinor > 0 }.sumOf { it.amountMinor },
            creditOutMinor = entries.filter { it.amountMinor < 0 }.sumOf { -it.amountMinor }
        )
    }

    /** فواتير مستحقة فعلاً للتسوية (DUE / PARTIALLY_PAID بمستحق موجب)، للمالك المعني. */
    fun payableDues(invoices: List<Invoice>): List<Invoice> =
        invoices.filter { (it.status == InvoiceStatus.DUE || it.status == InvoiceStatus.PARTIALLY_PAID) && it.amountDueMinor > 0 }

    fun toDueInvoices(invoices: List<Invoice>): List<OwnerCreditRules.DueInvoice> =
        payableDues(invoices).map { OwnerCreditRules.DueInvoice(it.id, it.amountDueMinor, it.createdAt) }

    data class Labels(
        val title: String, val owner: String, val phone: String, val period: String,
        val invoices: String, val invoiced: String, val paid: String, val due: String,
        val creditIn: String, val creditOut: String, val balance: String, val currency: String
    )

    fun money(minor: Long): String = ps.mgtik.hotspot.core.util.formatMinor(minor)

    fun toText(ownerName: String, phone: String, balanceMinor: Long, periodLabel: String, s: Summary, l: Labels): String =
        buildString {
            appendLine(l.title)
            appendLine("${l.owner}: $ownerName")
            if (phone.isNotBlank()) appendLine("${l.phone}: $phone")
            appendLine("${l.period}: $periodLabel")
            appendLine("—")
            appendLine("${l.invoices}: ${s.invoicesCount}")
            appendLine("${l.invoiced}: ${money(s.invoicedMinor)} ${l.currency}")
            appendLine("${l.paid}: ${money(s.paidMinor)} ${l.currency}")
            appendLine("${l.due}: ${money(s.dueMinor)} ${l.currency}")
            appendLine("${l.creditIn}: ${money(s.creditInMinor)} ${l.currency}")
            appendLine("${l.creditOut}: ${money(s.creditOutMinor)} ${l.currency}")
            append("${l.balance}: ${money(balanceMinor)} ${l.currency}")
        }
}
