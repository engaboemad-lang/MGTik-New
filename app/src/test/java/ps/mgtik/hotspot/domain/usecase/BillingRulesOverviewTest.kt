package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ps.mgtik.hotspot.domain.model.Invoice
import ps.mgtik.hotspot.domain.model.InvoiceStatus
import ps.mgtik.hotspot.domain.model.OwnerAccount
import ps.mgtik.hotspot.domain.model.OwnerStatus
import ps.mgtik.hotspot.domain.model.Payment
import ps.mgtik.hotspot.domain.model.PaymentStatus
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionPayment

class BillingRulesOverviewTest {

    private fun pay(amount: Long, at: String, status: PaymentStatus = PaymentStatus.ACTIVE, refunded: Long = 0L) =
        Payment(id = at + amount, amountMinor = amount, createdAt = at, status = status, refundedMinor = refunded)

    @Test fun dashboardMatchesTotalsAndCountsOnlyActiveSubscriptions() {
        val invoices = listOf(
            Invoice(id = "a", totalMinor = 1000, amountPaidMinor = 400, status = InvoiceStatus.PARTIALLY_PAID),
            Invoice(id = "b", totalMinor = 500, amountPaidMinor = 0, status = InvoiceStatus.CANCELED)
        )
        val payments = listOf(
            pay(400, "2026-11-18T10:00:00.000Z"),
            pay(200, "2026-11-18T11:00:00.000Z", PaymentStatus.REFUNDED, refunded = 50)
        )
        val d = BillingRules.dashboard(invoices, payments, emptyList())
        assertEquals(1000L, d.totalInvoicedMinor)      // الملغاة خارج المفوتر
        assertEquals(550L, d.totalPaidMinor)           // 400 + (200 - 50)
        assertEquals(600L, d.totalOutstandingMinor)    // الملغاة لا مستحق عليها
        assertEquals(50L, d.totalRefundedMinor)
        assertEquals(0, d.activeSubscriptionsCount)
    }

    @Test fun incomeForLocalDayUsesLocalDayBoundaries() {
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Jerusalem"))
            val payments = listOf(
                pay(100, "2026-11-17T22:30:00.000Z"), // 00:30 محلي 18 نوفمبر
                pay(200, "2026-11-18T21:30:00.000Z"), // 23:30 محلي 18 نوفمبر
                pay(400, "2026-11-18T22:00:00.000Z")  // 00:00 محلي 19 نوفمبر
            )
            assertEquals(300L, BillingRules.incomeForLocalDay(payments, "2026-11-18"))
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test fun ownerBalancesSumsDebtPerOwnerAndSkipsArchived() {
        val owners = listOf(
            OwnerAccount(ownerId = "o1", name = "أحمد", availableCreditMinor = 300),
            OwnerAccount(ownerId = "o2", name = "سامي", availableCreditMinor = 0),
            OwnerAccount(ownerId = "o3", name = "مؤرشف", status = OwnerStatus.ARCHIVED, availableCreditMinor = 999)
        )
        val invoices = listOf(
            Invoice(id = "1", ownerId = "o1", totalMinor = 500, amountPaidMinor = 100, status = InvoiceStatus.PARTIALLY_PAID),
            Invoice(id = "2", ownerId = "o2", totalMinor = 900, amountPaidMinor = 0, status = InvoiceStatus.DUE),
            Invoice(id = "3", ownerId = "o2", totalMinor = 100, amountPaidMinor = 0, status = InvoiceStatus.CANCELED)
        )
        val r = BillingRules.ownerBalances(owners, invoices)
        assertEquals(2, r["ownersCount"])
        assertEquals(300L, r["totalCreditMinor"])
        assertEquals(1300L, r["totalOutstandingMinor"])
        @Suppress("UNCHECKED_CAST")
        val rows = r["owners"] as List<Map<String, Any?>>
        assertEquals("سامي", rows.first()["label"]) // الأكثر ديناً أولاً
        assertEquals(900L, rows.first()["outstandingMinor"])
    }

    @Test fun dashboardAddsSubscriptionCollectionSeparatelyFromOrderInvoices() {
        val subs = listOf(
            Subscription(id = "s1", totalInvoicedMinor = 1000, totalPaidMinor = 400),
            Subscription(id = "s2", totalInvoicedMinor = 500, totalPaidMinor = 500),
            Subscription(id = "s3", totalInvoicedMinor = 100, totalPaidMinor = 300) // دفع زائد لا يُنتج مستحقاً سالباً
        )
        val d = BillingRules.dashboard(emptyList(), emptyList(), subs)
        assertEquals(1200L, d.subscriptionsCollectedMinor)
        assertEquals(600L, d.subscriptionsOutstandingMinor)
        assertEquals(0L, d.totalPaidMinor) // لا يختلط بتحصيل فواتير الطلبات
        assertEquals(0L, d.totalOutstandingMinor)
    }

    @Test fun periodSummaryCountsOnlyMovementInsideTheRange() {
        // منتصف النهار UTC في منتصف الشهر: آمن لأي منطقة زمنية
        val oct = "2026-10-15T12:00:00.000Z"
        val nov = "2026-11-15T12:00:00.000Z"
        val invoices = listOf(
            Invoice(id = "i1", totalMinor = 1000, createdAt = nov, status = InvoiceStatus.DUE),
            Invoice(id = "i2", totalMinor = 400, createdAt = nov, status = InvoiceStatus.CANCELED), // الملغاة لا تُفوتَر
            Invoice(id = "i3", totalMinor = 700, createdAt = oct, status = InvoiceStatus.PAID)
        )
        val payments = listOf(
            pay(600, nov),
            pay(300, nov, PaymentStatus.REFUNDED, refunded = 100), // صافي 200 ومسترد 100
            pay(900, oct)
        )
        val subs = listOf(
            SubscriptionPayment(id = "s1", amountMinor = 50, createdAt = nov),
            SubscriptionPayment(id = "s2", amountMinor = 70, createdAt = nov, isVoid = true),
            SubscriptionPayment(id = "s3", amountMinor = 90, createdAt = oct)
        )
        val nowRange = "2026-11-01" to "2026-11-30"
        val m = BillingRules.periodSummary(invoices, payments, subs, nowRange)
        assertEquals(1, m.invoicesCount)
        assertEquals(1000L, m.invoicedMinor)
        assertEquals(800L, m.collectedMinor)
        assertEquals(2, m.paymentsCount)
        assertEquals(100L, m.refundedMinor)
        assertEquals(50L, m.subscriptionsCollectedMinor)

        val all = BillingRules.periodSummary(invoices, payments, subs, null)
        assertEquals(1700L, all.invoicedMinor)
        assertEquals(1700L, all.collectedMinor)
        assertEquals(140L, all.subscriptionsCollectedMinor)

        // مصدر دفعات الاشتراكات غير واصل ⇒ null لا صفر مضلل
        assertEquals(null, BillingRules.periodSummary(invoices, payments, null, nowRange).subscriptionsCollectedMinor)
    }

    @Test fun generationSuccessPercent() {
        assertEquals(80, BillingRules.generationSuccessPercent(10, 8))
        assertEquals(66, BillingRules.generationSuccessPercent(3, 2))
        assertNull(BillingRules.generationSuccessPercent(0, 0))
    }
}
