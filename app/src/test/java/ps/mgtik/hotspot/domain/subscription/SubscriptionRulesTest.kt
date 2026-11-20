package ps.mgtik.hotspot.domain.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.NewSubscription
import ps.mgtik.hotspot.domain.model.PeriodUnit
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionExecution
import ps.mgtik.hotspot.domain.model.SubscriptionOp
import ps.mgtik.hotspot.domain.model.SubscriptionPayStatus
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SubscriptionRulesTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val t0 = Instant.parse("2026-01-31T10:00:00Z")

    private fun sub(
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        start: String = "2026-01-31T10:00:00.000Z",
        expires: String = "2026-02-28T10:00:00.000Z",
        unit: PeriodUnit = PeriodUnit.MONTHS,
        count: Int = 1,
        auto: Boolean = false,
        period: Int = 1
    ) = Subscription(
        id = "s1", status = status, startDate = start, expiresAt = expires,
        periodUnit = unit, periodCount = count, autoRenewal = auto, periodNumber = period, version = 3
    )

    // ───── المدة ─────
    @Test fun monthEndClampsToLastDayOfNextMonth() =
        assertEquals(Instant.parse("2026-02-28T10:00:00Z"), SubscriptionRules.expiryFrom(t0, PeriodUnit.MONTHS, 1, zone))

    @Test fun daysAreContiguous24HourBlocks() =
        assertEquals(Instant.parse("2026-03-02T10:00:00Z"), SubscriptionRules.expiryFrom(t0, PeriodUnit.DAYS, 30, zone))

    @Test fun cycleLabels() {
        assertEquals("monthly", SubscriptionRules.cycleLabel(PeriodUnit.MONTHS, 1))
        assertEquals("3_months", SubscriptionRules.cycleLabel(PeriodUnit.MONTHS, 3))
        assertEquals("15_days", SubscriptionRules.cycleLabel(PeriodUnit.DAYS, 15))
    }

    @Test fun renewalBeforeExpiryExtendsFromCurrentExpiryAndAfterExpiryStartsNow() {
        val s = sub()
        assertEquals(Instant.parse("2026-02-28T10:00:00Z"), SubscriptionRules.renewalStart(s, Instant.parse("2026-02-10T00:00:00Z")))
        assertEquals(Instant.parse("2026-03-05T00:00:00Z"), SubscriptionRules.renewalStart(s, Instant.parse("2026-03-05T00:00:00Z")))
        assertEquals(Instant.parse("2026-03-05T00:00:00Z"), SubscriptionRules.renewalStart(sub(status = SubscriptionStatus.EXPIRED), Instant.parse("2026-03-05T00:00:00Z")))
    }

    // ───── التحقق ─────
    private fun draft(
        name: String = "أحمد", user: String = "ahmad_01", pass: String = "abcd", profile: String = "p1",
        unit: PeriodUnit = PeriodUnit.MONTHS, count: Int = 1, price: Long = 5_000, discount: Long = 0, start: String? = null
    ) = NewSubscription(name, "0599123456", user, pass, profile, "شهري", "monthly", price, discount, null, null, unit, count, start, false, "")

    @Test fun validDraftPasses() = assertNull(SubscriptionRules.validateNew(draft(), t0))

    @Test fun invalidInputsMapToSpecificCodes() {
        assertEquals(SubscriptionRules.ERR_NAME_REQUIRED, SubscriptionRules.validateNew(draft(name = " "), t0))
        assertEquals(SubscriptionRules.ERR_USERNAME_INVALID, SubscriptionRules.validateNew(draft(user = "a b"), t0))
        assertEquals(SubscriptionRules.ERR_USERNAME_INVALID, SubscriptionRules.validateNew(draft(user = "ab"), t0))
        assertEquals(SubscriptionRules.ERR_PASSWORD_INVALID, SubscriptionRules.validateNew(draft(pass = "ab"), t0))
        assertEquals(SubscriptionRules.ERR_PASSWORD_INVALID, SubscriptionRules.validateNew(draft(pass = "ab cd"), t0))
        assertEquals(SubscriptionRules.ERR_PROFILE_REQUIRED, SubscriptionRules.validateNew(draft(profile = ""), t0))
        assertEquals(SubscriptionRules.ERR_PERIOD_INVALID, SubscriptionRules.validateNew(draft(count = 0), t0))
        assertEquals(SubscriptionRules.ERR_PERIOD_INVALID, SubscriptionRules.validateNew(draft(count = 121), t0))
        assertEquals(SubscriptionRules.ERR_PERIOD_INVALID, SubscriptionRules.validateNew(draft(unit = PeriodUnit.DAYS, count = 3651), t0))
        assertEquals(SubscriptionRules.ERR_PRICE_INVALID, SubscriptionRules.validateNew(draft(price = -1), t0))
        assertEquals(SubscriptionRules.ERR_DISCOUNT_INVALID, SubscriptionRules.validateNew(draft(discount = 5_001), t0))
        assertEquals(SubscriptionRules.ERR_START_INVALID, SubscriptionRules.validateNew(draft(start = "غد"), t0))
    }

    @Test fun fullDiscountIsAllowedAndPriceZeroToo() {
        assertNull(SubscriptionRules.validateNew(draft(discount = 5_000), t0))
        assertNull(SubscriptionRules.validateNew(draft(price = 0), t0))
    }

    // ───── نية الراوتر والتعارض ─────
    @Test fun routerIntentFollowsAdministrativeStatus() {
        assertEquals(RouterIntent.ENABLE, SubscriptionRules.intentOf(SubscriptionStatus.ACTIVE))
        listOf(SubscriptionStatus.EXPIRED, SubscriptionStatus.SUSPENDED, SubscriptionStatus.CANCELLED)
            .forEach { assertEquals(RouterIntent.DISABLE, SubscriptionRules.intentOf(it)) }
        listOf(SubscriptionStatus.DRAFT, SubscriptionStatus.SCHEDULED)
            .forEach { assertEquals(RouterIntent.NONE, SubscriptionRules.intentOf(it)) }
    }

    @Test fun onlyActivateAndRenewResetCounters() {
        assertTrue(SubscriptionRules.resetsCounters(SubscriptionOp.ACTIVATE))
        assertTrue(SubscriptionRules.resetsCounters(SubscriptionOp.RENEW))
        assertFalse(SubscriptionRules.resetsCounters(SubscriptionOp.RESUME))
        assertFalse(SubscriptionRules.resetsCounters(SubscriptionOp.EXPIRE))
    }

    @Test fun conflictWhenExpiredButRouterAccountStillEnabled() {
        val s = sub(status = SubscriptionStatus.EXPIRED).copy(execution = SubscriptionExecution.SYNCED, routerObservedDisabled = false)
        assertTrue(SubscriptionRules.hasConflict(s))
    }

    @Test fun conflictWhenActiveButRouterAccountDisabled() {
        assertTrue(SubscriptionRules.hasConflict(sub().copy(routerObservedDisabled = true)))
    }

    @Test fun noConflictWhenAlignedUnknownOrStillPending() {
        assertFalse(SubscriptionRules.hasConflict(sub().copy(routerObservedDisabled = false)))
        assertFalse(SubscriptionRules.hasConflict(sub().copy(routerObservedDisabled = null)))
        assertFalse(SubscriptionRules.hasConflict(sub().copy(routerObservedDisabled = true, execution = SubscriptionExecution.PENDING)))
        assertFalse(SubscriptionRules.hasConflict(sub(status = SubscriptionStatus.SCHEDULED).copy(routerObservedDisabled = false)))
    }

    @Test fun expiringSoonWindow() {
        val now = Instant.parse("2026-02-26T10:00:00Z")
        assertTrue(SubscriptionRules.isExpiringSoon(sub(), now))
        assertFalse(SubscriptionRules.isExpiringSoon(sub(), Instant.parse("2026-02-10T10:00:00Z")))
        assertFalse(SubscriptionRules.isExpiringSoon(sub(), Instant.parse("2026-03-01T10:00:00Z")))
        assertFalse(SubscriptionRules.isExpiringSoon(sub(status = SubscriptionStatus.SUSPENDED), now))
    }

    // ───── الانتقالات الزمنية ─────
    @Test fun scheduledActivatesOnlyWhenStartArrived() {
        val s = sub(status = SubscriptionStatus.SCHEDULED, start = "2026-03-01T00:00:00.000Z")
        assertNull(SubscriptionRules.activateOnTime(s, Instant.parse("2026-02-28T23:59:59Z")))
        val a = SubscriptionRules.activateOnTime(s, Instant.parse("2026-03-01T00:00:01Z"))!!
        assertEquals(SubscriptionStatus.ACTIVE, a.status)
        assertEquals(SubscriptionOp.ACTIVATE, a.pendingOp)
        assertEquals(SubscriptionExecution.PENDING, a.execution)
        assertEquals(4, a.version)
    }

    @Test fun expireOnlyWhenStillActiveAndPastExpiry() {
        val past = Instant.parse("2026-03-01T00:00:00Z")
        val e = SubscriptionRules.expireOnTime(sub(), past)!!
        assertEquals(SubscriptionStatus.EXPIRED, e.status)
        assertEquals(SubscriptionOp.EXPIRE, e.pendingOp)
        assertNull(SubscriptionRules.expireOnTime(sub(), Instant.parse("2026-02-20T00:00:00Z")))
    }

    @Test fun expireIsRefusedForRenewedSuspendedOrCancelledSubscriptions() {
        val now = Instant.parse("2026-03-01T00:00:00Z")
        // جُدِّد: موعد الانتهاء الجديد في المستقبل ⇒ لا إيقاف بأمر قديم
        assertNull(SubscriptionRules.expireOnTime(sub(expires = "2026-03-28T10:00:00.000Z"), now))
        assertNull(SubscriptionRules.expireOnTime(sub(status = SubscriptionStatus.SUSPENDED), now))
        assertNull(SubscriptionRules.expireOnTime(sub(status = SubscriptionStatus.CANCELLED), now))
    }

    @Test fun autoRenewDueOnlyForActiveAutoRenewalPastExpiry() {
        val now = Instant.parse("2026-03-01T00:00:00Z")
        assertTrue(SubscriptionRules.isAutoRenewDue(sub(auto = true), now))
        assertFalse(SubscriptionRules.isAutoRenewDue(sub(auto = false), now))
        assertFalse(SubscriptionRules.isAutoRenewDue(sub(auto = true, status = SubscriptionStatus.SUSPENDED), now))
        assertFalse(SubscriptionRules.isAutoRenewDue(sub(auto = true), Instant.parse("2026-02-01T00:00:00Z")))
    }

    // ───── التجديد ─────
    @Test fun renewBeforeExpiryKeepsRemainingDaysAndBumpsPeriod() {
        val r = SubscriptionRules.renew(sub(), Instant.parse("2026-02-10T00:00:00Z"), "k1", zone) as RenewalResult.Ok
        assertEquals(Instant.parse("2026-02-28T10:00:00Z"), r.periodStart)
        assertEquals(Instant.parse("2026-03-28T10:00:00Z"), r.periodEnd)
        assertEquals(2, r.sub.periodNumber)
        assertEquals(SubscriptionStatus.ACTIVE, r.sub.status)
        assertEquals(SubscriptionOp.RENEW, r.sub.pendingOp)
        assertEquals("k1", r.sub.lastRenewKey)
        assertEquals(SubscriptionRules.iso(r.periodEnd), r.sub.expiresAt)
    }

    @Test fun renewAfterExpiryStartsFromRenewalMoment() {
        val now = Instant.parse("2026-03-10T08:00:00Z")
        val r = SubscriptionRules.renew(sub(status = SubscriptionStatus.EXPIRED), now, "k2", zone) as RenewalResult.Ok
        assertEquals(now, r.periodStart)
        assertEquals(Instant.parse("2026-04-10T08:00:00Z"), r.periodEnd)
    }

    @Test fun renewIsRejectedFromSuspendedScheduledDraftAndCancelled() {
        listOf(SubscriptionStatus.SUSPENDED, SubscriptionStatus.SCHEDULED, SubscriptionStatus.DRAFT, SubscriptionStatus.CANCELLED).forEach {
            assertEquals(SubscriptionRules.ERR_INVALID_STATE, (SubscriptionRules.renew(sub(status = it), t0, "k", zone) as RenewalResult.Rejected).code)
        }
    }

    @Test fun autoRenewalKeepsNextRenewalDateAligned() {
        val r = SubscriptionRules.renew(sub(auto = true), Instant.parse("2026-02-10T00:00:00Z"), "k", zone) as RenewalResult.Ok
        assertEquals(r.sub.expiresAt, r.sub.nextRenewal)
    }

    // ───── إيقاف / استئناف / إلغاء ─────
    @Test fun suspendOnlyFromActive() {
        val ok = SubscriptionRules.suspend(sub(), t0) as Change.Ok
        assertEquals(SubscriptionStatus.SUSPENDED, ok.sub.status)
        assertEquals(SubscriptionOp.SUSPEND, ok.sub.pendingOp)
        assertTrue(SubscriptionRules.suspend(sub(status = SubscriptionStatus.EXPIRED), t0) is Change.Rejected)
    }

    @Test fun resumeKeepsExpiryButRejectsWhenExpiredDuringSuspension() {
        val suspended = sub(status = SubscriptionStatus.SUSPENDED)
        val ok = SubscriptionRules.resume(suspended, Instant.parse("2026-02-10T00:00:00Z")) as Change.Ok
        assertEquals(SubscriptionStatus.ACTIVE, ok.sub.status)
        assertEquals(SubscriptionOp.RESUME, ok.sub.pendingOp)
        assertEquals(suspended.expiresAt, ok.sub.expiresAt)
        val late = SubscriptionRules.resume(suspended, Instant.parse("2026-03-10T00:00:00Z")) as Change.Rejected
        assertEquals(SubscriptionRules.ERR_EXPIRED_RENEW, late.code)
    }

    @Test fun cancelDisablesRouterAccountExceptWhenNeverCreatedThere() {
        val active = SubscriptionRules.cancel(sub()) as Change.Ok
        assertEquals(SubscriptionOp.CANCEL, active.sub.pendingOp)
        val scheduled = SubscriptionRules.cancel(sub(status = SubscriptionStatus.SCHEDULED)) as Change.Ok
        assertNull(scheduled.sub.pendingOp)
        assertEquals(SubscriptionExecution.SYNCED, scheduled.sub.execution)
        assertTrue(SubscriptionRules.cancel(sub(status = SubscriptionStatus.CANCELLED)) is Change.Rejected)
    }

    // ───── المجدول ─────
    @Test fun dueActionsOrdersTimeTransitionsBeforeSyncRetries() {
        val now = Instant.parse("2026-03-01T00:00:00Z")
        val scheduled = sub(status = SubscriptionStatus.SCHEDULED, start = "2026-02-01T00:00:00.000Z").copy(id = "a")
        val expired = sub().copy(id = "b")
        val auto = sub(auto = true).copy(id = "c")
        val stuck = sub(status = SubscriptionStatus.SUSPENDED).copy(id = "d", execution = SubscriptionExecution.FAILED, pendingOp = SubscriptionOp.SUSPEND)
        val fine = sub(expires = "2026-04-01T00:00:00.000Z").copy(id = "e")
        val due = SubscriptionRules.dueActions(listOf(stuck, fine, expired, auto, scheduled), now)
        assertEquals(
            listOf<Due>(
                Due.Time("b", Due.Kind.EXPIRE), Due.Time("c", Due.Kind.AUTO_RENEW), Due.Time("a", Due.Kind.ACTIVATE), Due.Sync("d")
            ).toSet(),
            due.toSet()
        )
        assertTrue(due.take(3).all { it is Due.Time })
        assertTrue(due.last() is Due.Sync)
    }

    @Test fun syncedSubscriptionsWithNoPendingOpAreNotDue() =
        assertTrue(SubscriptionRules.dueActions(listOf(sub(expires = "2026-04-01T00:00:00.000Z")), Instant.parse("2026-03-01T00:00:00Z")).isEmpty())

    @Test fun deterministicIdsMakeRetriesIdempotent() {
        assertEquals("s1_p2", SubscriptionBilling.invoiceId("s1", 2))
        assertEquals("auto_s1_p3", SubscriptionRules.autoRenewKey("s1", 3))
        assertEquals("s1_RENEW_v2", SubscriptionRules.opId("s1", SubscriptionOp.RENEW, 2))
    }
}

class SubscriptionBillingTest {
    @Test fun netAndStatus() {
        assertEquals(4_000L, SubscriptionBilling.netMinor(5_000, 1_000))
        assertEquals(SubscriptionPayStatus.UNPAID, SubscriptionBilling.payStatus(4_000, 0))
        assertEquals(SubscriptionPayStatus.PARTIAL, SubscriptionBilling.payStatus(4_000, 1))
        assertEquals(SubscriptionPayStatus.PAID, SubscriptionBilling.payStatus(4_000, 4_000))
        assertEquals(SubscriptionPayStatus.PAID, SubscriptionBilling.payStatus(0, 0))
    }

    @Test fun paymentChecks() {
        assertNull(SubscriptionBilling.checkPayment(4_000, 1_000, 3_000))
        assertEquals(SubscriptionBilling.ERR_AMOUNT_INVALID, SubscriptionBilling.checkPayment(4_000, 0, 0))
        assertEquals(SubscriptionBilling.ERR_AMOUNT_INVALID, SubscriptionBilling.checkPayment(4_000, 0, -1))
        assertEquals(SubscriptionBilling.ERR_OVERPAY, SubscriptionBilling.checkPayment(4_000, 1_000, 3_001))
        assertEquals(SubscriptionBilling.ERR_INVOICE_SETTLED, SubscriptionBilling.checkPayment(4_000, 4_000, 1))
    }

    @Test fun voidNeverGoesBelowZero() {
        assertEquals(500L, SubscriptionBilling.paidAfterVoid(1_500, 1_000))
        assertEquals(0L, SubscriptionBilling.paidAfterVoid(500, 1_000))
    }

    @Test fun overdueNeedsBalanceAndPastDueDate() {
        val now = Instant.parse("2026-03-01T00:00:00Z")
        assertTrue(SubscriptionBilling.isOverdue("2026-02-01T00:00:00.000Z", 1, now))
        assertFalse(SubscriptionBilling.isOverdue("2026-02-01T00:00:00.000Z", 0, now))
        assertFalse(SubscriptionBilling.isOverdue("2026-04-01T00:00:00.000Z", 1, now))
        assertFalse(SubscriptionBilling.isOverdue("", 1, now))
    }
}
