package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus

class OwnerCreditRulesTest {

    private fun check(owner: String? = "o1", payable: Boolean = true, due: Long = 1_000, credit: Long = 1_000, amount: Long = 500) =
        OwnerCreditRules.checkApply(owner, payable, due, credit, amount)

    @Test fun validApplyPasses() = assertNull(check())
    @Test fun fullSettlementPasses() = assertNull(check(due = 1_000, credit = 1_000, amount = 1_000))

    @Test fun nonPositiveAmountRejected() {
        assertEquals(OwnerCreditRules.ERR_INVALID_AMOUNT, check(amount = 0))
        assertEquals(OwnerCreditRules.ERR_INVALID_AMOUNT, check(amount = -5))
    }

    @Test fun invoiceWithoutOwnerOrNotPayableOrSettledRejected() {
        assertEquals(OwnerCreditRules.ERR_INVOICE_NOT_PAYABLE, check(owner = null))
        assertEquals(OwnerCreditRules.ERR_INVOICE_NOT_PAYABLE, check(owner = " "))
        assertEquals(OwnerCreditRules.ERR_INVOICE_NOT_PAYABLE, check(payable = false))
        assertEquals(OwnerCreditRules.ERR_INVOICE_NOT_PAYABLE, check(due = 0))
    }

    @Test fun amountAboveDueRejectedBeforeCreditCheck() =
        assertEquals(OwnerCreditRules.ERR_EXCEEDS_DUE, check(due = 400, credit = 100, amount = 500))

    @Test fun amountAboveCreditRejected() =
        assertEquals(OwnerCreditRules.ERR_INSUFFICIENT_CREDIT, check(due = 1_000, credit = 499, amount = 500))

    @Test fun zeroCreditNeverCoversAnything() =
        assertEquals(OwnerCreditRules.ERR_INSUFFICIENT_CREDIT, check(credit = 0))

    @Test fun partialPaymentLeavesInvoicePartiallyPaid() {
        val r = OwnerCreditRules.afterPayment(totalMinor = 1_000, currentPaidMinor = 200, amountMinor = 300)
        assertEquals(500L, r.paidMinor); assertEquals(500L, r.dueMinor)
        assertEquals("partially_paid", r.statusName); assertFalse(r.isPaid)
    }

    @Test fun exactPaymentSettlesInvoice() {
        val r = OwnerCreditRules.afterPayment(1_000, 200, 800)
        assertEquals(0L, r.dueMinor); assertEquals("paid", r.statusName); assertTrue(r.isPaid)
    }

    @Test fun chargeThenRestoreReturnsToOriginalCredit() {
        val charged = OwnerCreditRules.creditAfterCharge(1_000, 400)
        assertEquals(600L, charged)
        assertEquals(1_000L, OwnerCreditRules.creditAfterRestore(charged, 400))
    }

    @Test fun openingBalanceAddsToExistingCreditInsteadOfReplacingIt() =
        assertEquals(1_500L, OwnerCreditRules.creditAfterOpening(availableCreditMinor = 500, openingMinor = 1_000))

    @Test fun ledgerEnforcedFieldsAreNotUpdatable() {
        assertTrue("name" in OwnerCreditRules.UPDATABLE_OWNER_FIELDS)
        assertFalse("availableCreditMinor" in OwnerCreditRules.UPDATABLE_OWNER_FIELDS)
        assertFalse("openingBalanceMinor" in OwnerCreditRules.UPDATABLE_OWNER_FIELDS)
    }
}

class OwnerAllocationAndStatementTest {
    private fun due(id: String, due: Long, at: String) = OwnerCreditRules.DueInvoice(id, due, at)

    @Test fun allocatesOldestFirstAndStopsWhenBudgetEnds() {
        val plan = OwnerCreditRules.allocateOldestFirst(
            700, listOf(due("c", 500, "2026-03"), due("a", 400, "2026-01"), due("b", 300, "2026-02"))
        )
        assertEquals(listOf("a" to 400L, "b" to 300L), plan.map { it.invoiceId to it.amountMinor })
    }

    @Test fun partiallyCoversTheLastInvoiceAndNeverExceedsBudget() {
        val plan = OwnerCreditRules.allocateOldestFirst(550, listOf(due("a", 400, "1"), due("b", 400, "2")))
        assertEquals(listOf(400L, 150L), plan.map { it.amountMinor })
        assertEquals(550L, plan.sumOf { it.amountMinor })
    }

    @Test fun budgetAboveTotalDueSettlesEverythingOnly() =
        assertEquals(700L, OwnerCreditRules.allocateOldestFirst(10_000, listOf(due("a", 400, "1"), due("b", 300, "2"))).sumOf { it.amountMinor })

    @Test fun zeroBudgetOrNothingDueYieldsEmptyPlan() {
        assertTrue(OwnerCreditRules.allocateOldestFirst(0, listOf(due("a", 400, "1"))).isEmpty())
        assertTrue(OwnerCreditRules.allocateOldestFirst(500, listOf(due("a", 0, "1"))).isEmpty())
    }

    @Test fun sameTimestampTiesBreakByIdDeterministically() {
        val plan = OwnerCreditRules.allocateOldestFirst(100, listOf(due("b", 100, "t"), due("a", 100, "t")))
        assertEquals("a", plan.single().invoiceId)
    }

    @Test fun internationalDigitsForWhatsApp() {
        assertEquals("970599123456", OwnerInputValidator.toInternationalDigits("0599-123-456"))
        assertEquals("970599123456", OwnerInputValidator.toInternationalDigits("+970 599 123 456"))
        assertEquals("972599123456", OwnerInputValidator.toInternationalDigits("00972599123456"))
        assertEquals("", OwnerInputValidator.toInternationalDigits("12"))
    }

    @Test fun summaryIgnoresCanceledAndDraftAndFiltersByPeriod() {
        fun inv(id: String, st: InvoiceStatus, total: Long, paid: Long, at: String) =
            Invoice(id = id, status = st, totalMinor = total, amountPaidMinor = paid, amountDueMinor = total - paid, createdAt = at)
        val invoices = listOf(
            inv("1", InvoiceStatus.DUE, 1_000, 0, "2026-02-01"), inv("2", InvoiceStatus.PAID, 500, 500, "2026-02-02"),
            inv("3", InvoiceStatus.CANCELED, 900, 0, "2026-02-03"), inv("4", InvoiceStatus.DRAFT, 800, 0, "2026-02-04"),
            inv("5", InvoiceStatus.PARTIALLY_PAID, 400, 100, "2026-01-01")
        )
        val all = OwnerStatement.summarize(invoices, emptyList(), null)
        assertEquals(3, all.invoicesCount); assertEquals(1_900L, all.invoicedMinor)
        assertEquals(600L, all.paidMinor); assertEquals(1_300L, all.dueMinor)
        val feb = OwnerStatement.summarize(invoices, emptyList(), "2026-02-01")
        assertEquals(2, feb.invoicesCount); assertEquals(1_000L, feb.dueMinor)
        assertEquals(listOf("1", "5"), OwnerStatement.payableDues(invoices).map { it.id })
    }
}
