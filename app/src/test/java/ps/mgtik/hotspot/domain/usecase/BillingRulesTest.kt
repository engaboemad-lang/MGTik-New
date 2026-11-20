package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.core.util.formatMinor
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.Payment
import ps.mgtik.hotspot.domain.model.PaymentStatus
import ps.mgtik.hotspot.domain.model.PeriodUnit
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionStatus

class BillingRulesTest {

    // ───── تسجيل دفعة ─────
    private fun record(
        exists: Boolean = true, status: InvoiceStatus? = InvoiceStatus.DUE,
        total: Long = 1_000, paid: Long = 0, amount: Long = 400
    ) = BillingRules.checkRecordPayment(exists, status, total, paid, amount)

    @Test fun validPaymentPasses() = assertNull(record())
    @Test fun exactRemainingPasses() = assertNull(record(total = 1_000, paid = 600, amount = 400))

    @Test fun nonPositiveRejected() {
        assertEquals(OwnerCreditRules.ERR_INVALID_AMOUNT, record(amount = 0))
        assertEquals(OwnerCreditRules.ERR_INVALID_AMOUNT, record(amount = -1))
    }

    @Test fun missingInvoiceRejected() = assertEquals(BillingRules.ERR_INVOICE_NOT_FOUND, record(exists = false))

    @Test fun canceledPaidOrUnknownStatusRejected() {
        assertEquals(BillingRules.ERR_INVOICE_NOT_PAYABLE, record(status = InvoiceStatus.CANCELED))
        assertEquals(BillingRules.ERR_INVOICE_NOT_PAYABLE, record(status = InvoiceStatus.PAID))
        assertEquals(BillingRules.ERR_INVOICE_NOT_PAYABLE, record(status = null))
        assertEquals(BillingRules.ERR_INVOICE_NOT_PAYABLE, record(total = 1_000, paid = 1_000))
    }

    @Test fun overpaymentRejected() {
        assertEquals(BillingRules.ERR_PAYMENT_EXCEEDS_DUE, record(total = 1_000, paid = 600, amount = 401))
    }

    // ───── الحالة بعد إبطال/استرداد ─────
    @Test fun voidingPartOfPaidInvoiceMakesItPartiallyPaid() {
        val r = BillingRules.afterReversal(InvoiceStatus.PAID, 1_000, 1_000, 300)
        assertEquals(700L, r.paidMinor); assertEquals(300L, r.dueMinor)
        assertEquals("partially_paid", r.statusName); assertFalse(r.isPaid)
    }

    @Test fun voidingAllPaymentsReturnsToDue() {
        val r = BillingRules.afterReversal(InvoiceStatus.PARTIALLY_PAID, 1_000, 400, 400)
        assertEquals(0L, r.paidMinor); assertEquals(1_000L, r.dueMinor); assertEquals("due", r.statusName)
    }

    @Test fun canceledInvoiceStaysCanceledAfterReversal() {
        val r = BillingRules.afterReversal(InvoiceStatus.CANCELED, 1_000, 400, 400)
        assertEquals("canceled", r.statusName); assertFalse(r.isPaid)
    }

    @Test fun paidNeverGoesNegative() {
        val r = BillingRules.afterReversal(InvoiceStatus.PARTIALLY_PAID, 1_000, 100, 500)
        assertEquals(0L, r.paidMinor); assertEquals(1_000L, r.dueMinor)
    }

    // ───── استرداد ─────
    @Test fun refundBoundedByPayment() {
        assertNull(BillingRules.checkRefund(500, 500))
        assertNull(BillingRules.checkRefund(500, 1))
        assertEquals(BillingRules.ERR_REFUND_EXCEEDS_PAYMENT, BillingRules.checkRefund(500, 501))
        assertEquals(OwnerCreditRules.ERR_INVALID_AMOUNT, BillingRules.checkRefund(500, 0))
        assertEquals(OwnerCreditRules.ERR_INVALID_AMOUNT, BillingRules.checkRefund(500, -5))
    }

    @Test fun onlyActivePaymentIsReversible() {
        assertFalse(BillingRules.isReversed(PaymentStatus.ACTIVE))
        assertTrue(BillingRules.isReversed(PaymentStatus.VOID))
        assertTrue(BillingRules.isReversed(PaymentStatus.REFUNDED))
        assertTrue(BillingRules.isReversed(PaymentStatus.CANCELED))
        assertTrue(BillingRules.isReversed(null))
    }

    // ───── الخصم ─────
    @Test fun percentageDiscountUsesIntegerMath() {
        assertEquals(100L, BillingRules.discountAmountMinor("percentage", 10, 1_000))
        assertEquals(33L, BillingRules.discountAmountMinor("percentage", 33, 100)) // لا كسور
        assertNull(BillingRules.discountAmountMinor("percentage", 101, 1_000))
        assertNull(BillingRules.discountAmountMinor("percentage", -1, 1_000))
    }

    @Test fun fixedDiscountBoundedBySubtotal() {
        assertEquals(1_000L, BillingRules.discountAmountMinor("fixed", 1_000, 1_000))
        assertNull(BillingRules.discountAmountMinor("fixed", 1_001, 1_000))
        assertNull(BillingRules.discountAmountMinor("fixed", -1, 1_000))
        assertNull(BillingRules.discountAmountMinor("unknown", 5, 1_000))
    }

    @Test fun discountRecalculatesTotalDueAndStatus() {
        val (err, r) = BillingRules.applyDiscount(InvoiceStatus.DUE, 1_000, 0, "percentage", 10)
        assertNull(err); assertNotNull(r)
        assertEquals(900L, r!!.totalMinor); assertEquals(900L, r.dueMinor); assertEquals("due", r.amounts.statusName)
    }

    @Test fun discountCoveringRemainderSettlesInvoice() {
        val (err, r) = BillingRules.applyDiscount(InvoiceStatus.PARTIALLY_PAID, 1_000, 600, "fixed", 400)
        assertNull(err)
        assertEquals(0L, r!!.dueMinor); assertEquals("paid", r.amounts.statusName); assertTrue(r.amounts.isPaid)
    }

    @Test fun discountBelowAlreadyPaidRejected() {
        val (err, r) = BillingRules.applyDiscount(InvoiceStatus.PARTIALLY_PAID, 1_000, 600, "fixed", 401)
        assertEquals(BillingRules.ERR_DISCOUNT_BELOW_PAID, err); assertNull(r)
    }

    @Test fun discountOnPaidOrCanceledInvoiceRejected() {
        assertEquals(BillingRules.ERR_INVOICE_NOT_PAYABLE, BillingRules.applyDiscount(InvoiceStatus.PAID, 1_000, 1_000, "fixed", 1).first)
        assertEquals(BillingRules.ERR_INVOICE_NOT_PAYABLE, BillingRules.applyDiscount(InvoiceStatus.CANCELED, 1_000, 0, "fixed", 1).first)
        assertEquals(BillingRules.ERR_INVOICE_NOT_PAYABLE, BillingRules.applyDiscount(null, 1_000, 0, "fixed", 1).first)
    }

    // ───── إلغاء وإعادة فتح وتعديل الحقول ─────
    @Test fun cancelOnlyWhenNothingPaid() {
        assertNull(BillingRules.checkCancel(InvoiceStatus.DUE, 0))
        assertNull(BillingRules.checkCancel(InvoiceStatus.DRAFT, 0))
        assertEquals(BillingRules.ERR_INVOICE_HAS_PAYMENTS, BillingRules.checkCancel(InvoiceStatus.PARTIALLY_PAID, 100))
        assertEquals(BillingRules.ERR_INVOICE_HAS_PAYMENTS, BillingRules.checkCancel(InvoiceStatus.PAID, 1_000))
        assertEquals(BillingRules.ERR_INVOICE_NOT_FOUND, BillingRules.checkCancel(null, 0))
    }

    @Test fun reopenGoesToDraftWhenUnpaid() {
        assertEquals(InvoiceStatus.DRAFT, BillingRules.reopenStatus(1_000, 0))
        assertEquals(InvoiceStatus.PARTIALLY_PAID, BillingRules.reopenStatus(1_000, 200))
        assertEquals(InvoiceStatus.PAID, BillingRules.reopenStatus(1_000, 1_000))
    }

    @Test fun onlyNonFinancialFieldsUpdatable() {
        assertTrue(BillingRules.disallowedKeys(mapOf("notes" to "x", "dueDate" to null), BillingRules.UPDATABLE_INVOICE_FIELDS).isEmpty())
        assertEquals(setOf("totalMinor", "status"),
            BillingRules.disallowedKeys(mapOf("notes" to "x", "totalMinor" to 1, "status" to "paid"), BillingRules.UPDATABLE_INVOICE_FIELDS))
        assertEquals(setOf("amountMinor"),
            BillingRules.disallowedKeys(mapOf("amountMinor" to 1, "reference" to "r"), BillingRules.UPDATABLE_PAYMENT_FIELDS))
    }

    // ───── الإجماليات ─────
    private fun inv(status: InvoiceStatus, total: Long, paid: Long) =
        Invoice(id = "i$total$paid${status.name}", status = status, totalMinor = total, amountPaidMinor = paid, amountDueMinor = total - paid)

    private fun pay(status: PaymentStatus, amount: Long, refunded: Long = 0) =
        Payment(id = "p$amount${status.name}", status = status, amountMinor = amount, refundedMinor = refunded)

    @Test fun totalsIgnoreCanceledAndVoidAndNetRefunds() {
        val t = BillingRules.totals(
            invoices = listOf(inv(InvoiceStatus.PAID, 1_000, 1_000), inv(InvoiceStatus.PARTIALLY_PAID, 2_000, 500),
                inv(InvoiceStatus.DUE, 300, 0), inv(InvoiceStatus.CANCELED, 9_999, 0)),
            payments = listOf(pay(PaymentStatus.ACTIVE, 1_000), pay(PaymentStatus.ACTIVE, 500),
                pay(PaymentStatus.VOID, 700), pay(PaymentStatus.REFUNDED, 400, refunded = 150))
        )
        assertEquals(3_300L, t.invoicedMinor)
        assertEquals(1_000L + 500L + 250L, t.collectedMinor)
        assertEquals(1_500L + 300L, t.outstandingMinor)
        assertEquals(150L, t.refundedMinor)
    }

    @Test fun emptyDataGivesZeroTotals() {
        val t = BillingRules.totals(emptyList(), emptyList())
        assertEquals(0L, t.invoicedMinor + t.collectedMinor + t.outstandingMinor + t.refundedMinor)
    }

    private fun sub(status: SubscriptionStatus, price: Long, unit: PeriodUnit, count: Int, final: Long = 0, discount: Long = 0) =
        Subscription(id = "s$price$count$unit", status = status, priceMinor = price, discountMinor = discount,
            finalPriceMinor = final, periodUnit = unit, periodCount = count)

    @Test fun mrrCountsOnlyActiveAndNormalizesPeriods() {
        val mrr = BillingRules.monthlyRecurringMinor(listOf(
            sub(SubscriptionStatus.ACTIVE, 3_000, PeriodUnit.MONTHS, 1, final = 3_000),
            sub(SubscriptionStatus.ACTIVE, 6_000, PeriodUnit.MONTHS, 3, final = 6_000),   // 2000/شهر
            sub(SubscriptionStatus.ACTIVE, 1_000, PeriodUnit.DAYS, 15, final = 1_000),    // 2000/30 يوماً
            sub(SubscriptionStatus.ACTIVE, 2_000, PeriodUnit.MONTHS, 1, discount = 500),  // بلا final: 1500
            sub(SubscriptionStatus.EXPIRED, 9_000, PeriodUnit.MONTHS, 1, final = 9_000),
            sub(SubscriptionStatus.SUSPENDED, 9_000, PeriodUnit.MONTHS, 1, final = 9_000)
        ))
        assertEquals(3_000L + 2_000L + 2_000L + 1_500L, mrr)
    }

    // ───── تنسيق المبلغ (أعداد صحيحة) ─────
    @Test fun formatMinorIsExactIntegerMath() {
        assertEquals("0.00", formatMinor(0))
        assertEquals("0.05", formatMinor(5))
        assertEquals("1.00", formatMinor(100))
        assertEquals("123.45", formatMinor(12_345))
        assertEquals("-0.50", formatMinor(-50))
        assertEquals("-12.34", formatMinor(-1_234))
        assertEquals("90071992547409.93", formatMinor(9_007_199_254_740_993L)) // أكبر من 2^53: Double كان سيُخطئ الرقم الأخير
    }
}
