package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.Batch
import ps.mgtik.hotspot.domain.model.BatchStatus
import ps.mgtik.hotspot.domain.model.BillingSettings
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.NetworkCompanyInfo
import ps.mgtik.hotspot.domain.model.Payment
import ps.mgtik.hotspot.domain.model.PaymentStatus

class InvoiceDocumentTest {

    private fun inv(
        total: Long = 2_000, subtotal: Long = 2_000, paid: Long = 0, status: InvoiceStatus = InvoiceStatus.DUE,
        due: String? = null, due2: Long? = null
    ) = Invoice(
        id = "INV-o1", invoiceNumber = "INV-000007", orderId = "o1", ownerNameSnapshot = "وكيل", status = status,
        subtotalMinor = subtotal, totalMinor = total, amountPaidMinor = paid, amountDueMinor = due2 ?: (total - paid),
        dueDate = due, createdAt = "2026-10-01 10:00:00", currency = "₪"
    )

    private fun batch(q: Int, unit: Long, status: BatchStatus = BatchStatus.COMPLETED, name: String = "يومية") =
        Batch(id = "b$q", orderId = "o1", profileNameSnapshot = name, successfulQuantity = q, status = status, wholesalePriceSnapshot = unit, totalAmountMinor = q * unit)

    private fun build(i: Invoice, batches: List<Batch> = emptyList(), pays: List<Payment> = emptyList(), today: String = "2026-10-29") =
        InvoiceDocumentBuilder.build(i, batches, pays, NetworkCompanyInfo(companyName = "شبكتي"), BillingSettings(invoiceFooterNote = "شكراً"), today)

    private fun ok(r: InvoiceDocumentBuilder.Result) = (r as InvoiceDocumentBuilder.Result.Ok).document

    // ───── البنود تأتي من لقطة سعر الدفعة، وتُعرض فقط إن طابقت الإجمالي المخزَّن ─────
    @Test fun linesUseLockedBatchPriceAndReconcile() {
        val d = ok(build(inv(total = 2_000, subtotal = 2_000), listOf(batch(2, 500), batch(2, 500, name = "أسبوعية").copy(wholesalePriceSnapshot = 500))))
        assertTrue(d.linesComplete)
        assertEquals(2, d.lines.size)
        assertEquals(1_000L, d.lines[0].totalMinor)
        assertEquals(2_000L, d.lines.sumOf { it.totalMinor })
    }

    @Test fun linesNotInventedWhenTheyDoNotMatchSubtotal() {
        val d = ok(build(inv(total = 2_000, subtotal = 2_000), listOf(batch(3, 500))))   // 1500 ≠ 2000
        assertFalse(d.linesComplete)
        assertTrue(d.lines.isEmpty())
        assertEquals(2_000L, d.subtotalMinor)
    }

    @Test fun noBatchesMeansNoLinesButInvoiceStillPrintable() {
        val d = ok(build(inv()))
        assertFalse(d.linesComplete); assertTrue(d.lines.isEmpty())
    }

    @Test fun failedAndPendingBatchesAreNotInvoiced() {
        val d = ok(build(inv(total = 1_000, subtotal = 1_000), listOf(batch(2, 500), batch(5, 500, BatchStatus.FAILED), batch(1, 500, BatchStatus.PENDING))))
        assertTrue(d.linesComplete); assertEquals(1, d.lines.size)
    }

    // ───── الإجماليات من الفاتورة المحفوظة لا من أي سعر حالي ─────
    @Test fun amountsComeFromStoredInvoiceWithDiscount() {
        val d = ok(build(inv(total = 1_800, subtotal = 2_000, paid = 500)))
        assertEquals(200L, d.discountMinor)
        assertEquals(1_800L, d.totalMinor); assertEquals(500L, d.paidMinor); assertEquals(1_300L, d.remainingMinor)
    }

    @Test fun canceledInvoiceHasZeroRemaining() {
        val d = ok(build(inv(status = InvoiceStatus.CANCELED, paid = 0)))
        assertEquals(0L, d.remainingMinor)
    }

    // ───── تعارض البيانات = خطأ لا نتيجة مضللة ─────
    @Test fun inconsistentInvoiceIsRejected() {
        assertEquals(InvoiceDocumentBuilder.ERR_INVOICE_INCONSISTENT, (build(inv(total = 1_000, paid = 1_500, due2 = 0)) as InvoiceDocumentBuilder.Result.Error).code)
        assertEquals(InvoiceDocumentBuilder.ERR_INVOICE_INCONSISTENT, (build(inv(total = 1_000, subtotal = 900)) as InvoiceDocumentBuilder.Result.Error).code)     // خصم سالب
        assertEquals(InvoiceDocumentBuilder.ERR_INVOICE_INCONSISTENT, (build(inv(total = 1_000, subtotal = 1_000, paid = 100, due2 = 999)) as InvoiceDocumentBuilder.Result.Error).code) // due ≠ total-paid
    }

    // ───── المتأخرة ─────
    @Test fun overdueOnlyWhenOpenWithPastDueDateAndBalance() {
        assertTrue(BillingRules.isOverdue(inv(due = "2026-10-01 00:00:00"), "2026-10-29"))
        assertFalse(BillingRules.isOverdue(inv(due = "2026-11-01 00:00:00"), "2026-10-29"))
        assertFalse(BillingRules.isOverdue(inv(due = null), "2026-10-29"))                       // لا نخترع استحقاقاً
        assertFalse(BillingRules.isOverdue(inv(due = "2026-10-01 00:00:00", status = InvoiceStatus.PAID, paid = 2_000), "2026-10-29"))
        assertFalse(BillingRules.isOverdue(inv(due = "2026-10-01 00:00:00", status = InvoiceStatus.CANCELED), "2026-10-29"))
        assertFalse(BillingRules.isOverdue(inv(due = "2026-10-29 23:59:59"), "2026-10-29"))      // يوم الاستحقاق نفسه ليس تأخراً
        assertTrue(BillingRules.isOverdue(inv(due = "2026-10-01 00:00:00", status = InvoiceStatus.PARTIALLY_PAID, paid = 500), "2026-10-29"))
    }

    // ───── الدفعات والترويسة ─────
    @Test fun paymentsOrderedOldestFirstAndCompanyFallsBack() {
        val p1 = Payment(id = "1", amountMinor = 300, createdAt = "2026-10-03", method = "cash")
        val p2 = Payment(id = "2", amountMinor = 200, createdAt = "2026-10-02", method = "transfer", status = PaymentStatus.VOID)
        val d = ok(build(inv(paid = 300, status = InvoiceStatus.PARTIALLY_PAID), pays = listOf(p1, p2)))
        assertEquals(listOf("2026-10-02", "2026-10-03"), d.payments.map { it.dateIso })
        assertEquals("شبكتي", d.company.name)
        assertEquals("شكراً", d.footerNote)
        assertNull(d.notes)
    }
}
