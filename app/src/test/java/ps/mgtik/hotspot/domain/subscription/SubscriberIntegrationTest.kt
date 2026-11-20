package ps.mgtik.hotspot.domain.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.Subscription
import ps.mgtik.hotspot.domain.model.SubscriptionExecution
import ps.mgtik.hotspot.domain.model.SubscriptionStatus
import ps.mgtik.hotspot.domain.model.SystemClass
import ps.mgtik.hotspot.domain.model.UserRecordStatus
import ps.mgtik.hotspot.domain.model.UserSyncStatus
import java.time.Instant
import java.time.ZoneOffset

class SubscriberIntegrationTest {

    private val now = Instant.parse("2026-02-10T10:00:00Z")

    private fun user(id: String = "s1", name: String = "ali", cls: SystemClass = SystemClass.SUBSCRIPTION) =
        LocalUserRecord(recordUuid = id, routerUsername = name, ownerId = "", systemClass = cls)

    private fun sub(
        id: String = "s1",
        name: String = "ali",
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        created: String = "2026-01-01T00:00:00.000Z",
        expires: String = "2026-03-01T00:00:00.000Z"
    ) = Subscription(
        id = id, customerUsername = name, status = status, createdAt = created,
        startDate = "2026-02-01T00:00:00.000Z", expiresAt = expires, version = 1, periodNumber = 1
    )

    // ───── الانتقال من بطاقة المستخدم إلى تفاصيل الاشتراك ─────

    @Test fun `matching id and username opens that subscription`() {
        val link = SubscriptionLinkResolver.resolve(user(), listOf(sub(id = "other", name = "x"), sub()))
        assertEquals("s1", (link as SubscriptionLink.Found).subscription.id)
    }

    @Test fun `id match alone is not trusted when the username differs`() {
        val link = SubscriptionLinkResolver.resolve(user(name = "ali"), listOf(sub(id = "s1", name = "someone-else")))
        assertEquals(SubscriptionLink.Missing, link)
    }

    @Test fun `falls back to the unique username match when ids differ`() {
        val link = SubscriptionLinkResolver.resolve(user(id = "u9"), listOf(sub(id = "s7")))
        assertEquals("s7", (link as SubscriptionLink.Found).subscription.id)
    }

    @Test fun `prefers the newest non cancelled subscription of the same username`() {
        val subs = listOf(
            sub(id = "old", status = SubscriptionStatus.CANCELLED, created = "2026-01-01T00:00:00.000Z"),
            sub(id = "cur", status = SubscriptionStatus.ACTIVE, created = "2026-01-10T00:00:00.000Z"),
            sub(id = "cancelledNewest", status = SubscriptionStatus.CANCELLED, created = "2026-02-01T00:00:00.000Z")
        )
        assertEquals("cur", (SubscriptionLinkResolver.resolve(user(id = "u9"), subs) as SubscriptionLink.Found).subscription.id)
    }

    @Test fun `only cancelled history still resolves to the newest one`() {
        val subs = listOf(
            sub(id = "a", status = SubscriptionStatus.CANCELLED, created = "2026-01-01T00:00:00.000Z"),
            sub(id = "b", status = SubscriptionStatus.CANCELLED, created = "2026-01-05T00:00:00.000Z")
        )
        assertEquals("b", (SubscriptionLinkResolver.resolve(user(id = "u9"), subs) as SubscriptionLink.Found).subscription.id)
    }

    // ───── عدم وجود اشتراك مرتبط ─────

    @Test fun `subscriber without any linked subscription is reported missing`() {
        assertEquals(SubscriptionLink.Missing, SubscriptionLinkResolver.resolve(user(), emptyList()))
        assertEquals(SubscriptionLink.Missing, SubscriptionLinkResolver.resolve(user(), listOf(sub(id = "z", name = "nobody"))))
    }

    @Test fun `blank subscription ids are never a link target`() {
        assertEquals(SubscriptionLink.Missing, SubscriptionLinkResolver.resolve(user(id = "u9"), listOf(sub(id = ""))))
    }

    @Test fun `regular users have no subscription link even if a username matches`() {
        assertEquals(SubscriptionLink.NotSubscriber, SubscriptionLinkResolver.resolve(user(cls = SystemClass.NORMAL), listOf(sub())))
    }

    // ───── التجديد والإيقاف والاستئناف والإلغاء + اتساق السجل ومنطق الراوتر ─────

    private fun transitions(): List<Pair<String, Subscription>> {
        val active = sub()
        val suspended = (SubscriptionRules.suspend(active, now) as Change.Ok).sub
        val resumed = (SubscriptionRules.resume(suspended, now) as Change.Ok).sub
        val cancelled = (SubscriptionRules.cancel(active) as Change.Ok).sub
        val cancelledScheduled = (SubscriptionRules.cancel(sub(status = SubscriptionStatus.SCHEDULED)) as Change.Ok).sub
        val renewed = (SubscriptionRules.renew(active, now, "k1", ZoneOffset.UTC) as RenewalResult.Ok).sub
        val expired = SubscriptionRules.expireOnTime(sub(expires = "2026-02-05T00:00:00.000Z"), now)!!
        val activated = SubscriptionRules.activateOnTime(sub(status = SubscriptionStatus.SCHEDULED), now)!!
        return listOf(
            "suspend" to suspended, "resume" to resumed, "cancel" to cancelled, "cancelScheduled" to cancelledScheduled,
            "renew" to renewed, "expire" to expired, "activate" to activated
        )
    }

    @Test fun `every transition schedules the router action matching the administrative state`() {
        for ((name, s) in transitions()) {
            val op = s.pendingOp
            if (name == "cancelScheduled") {
                // لا حساب على الراوتر بعد: لا أمر معلّق ولا يبقى PENDING
                assertNull(name, op)
                assertEquals(name, SubscriptionExecution.SYNCED, s.execution)
            } else {
                assertNotNull("$name must schedule a router op", op)
                assertEquals(name, SubscriptionExecution.PENDING, s.execution)
                assertEquals("$name: op intent must equal state intent", SubscriptionRules.intentOf(s.status), SubscriptionRules.intentOf(op!!))
            }
        }
    }

    @Test fun `mirrored user record agrees with the subscription and the router intent`() {
        for ((name, s) in transitions()) {
            val status = SubscriberRecordRules.statusOf(s)
            when (SubscriptionRules.intentOf(s.status)) {
                RouterIntent.ENABLE -> assertEquals(name, UserRecordStatus.ACTIVE, status)
                RouterIntent.DISABLE, RouterIntent.NONE -> assertTrue(name, status != UserRecordStatus.ACTIVE)
            }
            val expectedSync = if (s.execution == SubscriptionExecution.SYNCED) UserSyncStatus.SYNCED else UserSyncStatus.PENDING
            assertEquals(name, expectedSync, SubscriberRecordRules.syncOf(s))
        }
    }

    @Test fun `failed router sync is mirrored as failed and expired maps to expired`() {
        assertEquals(UserSyncStatus.FAILED, SubscriberRecordRules.syncOf(sub().copy(execution = SubscriptionExecution.FAILED)))
        assertEquals(UserRecordStatus.EXPIRED, SubscriberRecordRules.statusOf(sub(status = SubscriptionStatus.EXPIRED)))
    }

    @Test fun `renewal keeps invoice period and subscription period consistent`() {
        val ok = SubscriptionRules.renew(sub(), now, "k1", ZoneOffset.UTC) as RenewalResult.Ok
        assertEquals(2, ok.sub.periodNumber)
        assertEquals(SubscriptionRules.iso(ok.periodEnd), ok.sub.expiresAt)
        // الفاتورة تُشتق بمعرّف حتمي من (الاشتراك، رقم الفترة) فلا تتكرر عند إعادة المحاولة
        assertEquals("s1_p2", SubscriptionBilling.invoiceId(ok.sub.id, ok.sub.periodNumber))
        // نفس المفتاح = إعادة محاولة: لا فترة ثانية
        assertEquals("k1", ok.sub.lastRenewKey)
    }

    @Test fun `suspended or cancelled subscriptions cannot be renewed and resume is blocked after expiry`() {
        val suspended = (SubscriptionRules.suspend(sub(), now) as Change.Ok).sub
        assertTrue(SubscriptionRules.renew(suspended, now, "k", ZoneOffset.UTC) is RenewalResult.Rejected)
        val cancelled = (SubscriptionRules.cancel(sub()) as Change.Ok).sub
        assertTrue(SubscriptionRules.renew(cancelled, now, "k", ZoneOffset.UTC) is RenewalResult.Rejected)
        val lapsed = suspended.copy(expiresAt = "2026-02-05T00:00:00.000Z")
        assertEquals(SubscriptionRules.ERR_EXPIRED_RENEW, (SubscriptionRules.resume(lapsed, now) as Change.Rejected).code)
    }
}
