package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.RouterProfileOption

/** استيراد إعدادات الراوتر الفعلية: الربط، إعادة التوجيه، المزامنة، ومعاينة الفروق (نقي، بلا راوتر حقيقي). */
class RouterProfileImportTest {

    private val router = RouterProfileOption(
        name = "gold", rateLimitRxKbps = 512, rateLimitTxKbps = 2_048, sharedUsers = 1, sharedUnlimited = true,
        validitySeconds = 30 * 86_400L, validityStart = "assigned",
        limitUptimeSeconds = 7_200L, limitTransferBytes = 5_368_709_120L
    )

    private fun ok(r: RouterProfileImport.Result) = (r as RouterProfileImport.Result.Ok).profile

    @Test
    fun link_imports_every_router_setting_including_unlimited_devices_validity_start_and_limits() {
        // قيم شكلية كانت تُحفظ سابقاً في وضع الربط: جهاز واحد، صلاحية 0، بداية first_auth، بلا حدود
        val base = Profile(id = "p", linkedProfileName = "gold", sharedUsers = 1, validityHours = 0, validityStart = "first_auth")
        val p = ok(RouterProfileImport.import(base, router))
        assertTrue(p.sharedUnlimited)
        assertEquals(512, p.rateLimitRxKbps); assertEquals(2_048, p.rateLimitTxKbps)
        assertEquals(720, p.validityHours); assertFalse(p.validityUnlimited)
        assertEquals("assigned", p.validityStart)
        assertEquals(7_200L, p.limitUptimeSeconds); assertEquals(5_368_709_120L, p.limitTransferBytes)
        // بعد الاستيراد لا فرق بين المخزَّن والراوتر
        assertTrue(RouterProfileImport.differing(RouterProfileImport.optionOf(p), router).isEmpty())
    }

    @Test
    fun link_claims_all_settings_so_a_classification_cannot_override_router_values() {
        val p = ok(RouterProfileImport.import(Profile(id = "p", ownSettings = emptySet()), router))
        assertEquals(OperationalSetting.entries.toSet(), p.ownSettings)
    }

    @Test
    fun unlimited_validity_and_unreadable_start_policy() {
        val o = router.copy(validitySeconds = null, validityUnlimited = true, validityStart = null)
        val p = ok(RouterProfileImport.import(Profile(id = "p", validityStart = "assigned", validityHours = 48), o))
        assertTrue(p.validityUnlimited); assertEquals(0, p.validityHours)
        assertEquals("assigned", p.validityStart) // غير مقروءة: لا تُخترع ولا تُصفَّر
    }

    @Test
    fun validity_that_is_not_whole_hours_is_rejected_instead_of_rounded() {
        val r = RouterProfileImport.import(Profile(id = "p"), router.copy(validitySeconds = 5_400L))
        assertEquals(RouterProfileImport.ERR_VALIDITY_NOT_REPRESENTABLE, (r as RouterProfileImport.Result.Rejected).code)
        assertNull(RouterProfileImport.validityHours(router.copy(validitySeconds = 5_400L)))
        assertEquals(1, RouterProfileImport.validityHours(router.copy(validitySeconds = 3_600L)))
    }

    @Test
    fun out_of_range_limits_are_rejected_only_when_limits_are_reliable() {
        val bad = router.copy(limitUptimeSeconds = UsageLimits.MAX_UPTIME_SECONDS + 1)
        assertTrue(RouterProfileImport.import(Profile(id = "p"), bad) is RouterProfileImport.Result.Rejected)
        // Limitation غير مربوطة: الحدود غير موثوقة فتبقى المخزَّنة ولا تُرفض
        val kept = ok(RouterProfileImport.import(Profile(id = "p", limitUptimeSeconds = 60L), bad, limitsReliable = false))
        assertEquals(60L, kept.limitUptimeSeconds)
    }

    @Test
    fun reconcile_mode_does_not_touch_own_settings_and_keeps_rate_when_router_has_none() {
        val base = Profile(id = "p", ownSettings = emptySet(), rateLimitRxKbps = 100, rateLimitTxKbps = 200, sharedUsers = 4)
        val p = ok(
            RouterProfileImport.import(
                base, router.copy(rateLimitRxKbps = 0, rateLimitTxKbps = 0, sharedUnlimited = false, sharedUsers = 3),
                keepRateIfRouterUnlimited = true, claimOwnership = false
            )
        )
        assertEquals(emptySet<OperationalSetting>(), p.ownSettings)
        assertEquals(100, p.rateLimitRxKbps); assertEquals(200, p.rateLimitTxKbps)
        assertEquals(3, p.sharedUsers); assertFalse(p.sharedUnlimited)
    }

    @Test
    fun unlimited_router_never_overwrites_stored_count_with_one() {
        val p = ok(RouterProfileImport.import(Profile(id = "p", sharedUsers = 5), router, claimOwnership = false))
        assertTrue(p.sharedUnlimited); assertEquals(5, p.sharedUsers)
    }

    @Test
    fun cloud_fields_contain_only_changed_operational_fields() {
        val old = Profile(id = "p", sharedUsers = 1, validityStart = "first_auth", ownSettings = null)
        val new = ok(RouterProfileImport.import(old, router))
        val f = RouterProfileImport.cloudFields(old, new)
        assertEquals(true, f["shared_unlimited"]); assertEquals(720, f["validity_hours"]); assertEquals("assigned", f["validity_start"])
        assertEquals(7_200L, f["limit_uptime_seconds"]); assertEquals(5_368_709_120L, f["limit_transfer_bytes"])
        assertEquals(listOf("speed", "shared_users", "limit_uptime", "limit_transfer"), f["own_settings"])
        assertFalse("shared_users" in f) // العدد لم يتغير
        assertTrue(RouterProfileImport.cloudFields(new, new).isEmpty())
    }

    @Test
    fun differing_detects_unlimited_validity_start_and_limits_not_only_device_count() {
        val stored = RouterProfileOption("gold", 512, 2_048, sharedUsers = 1)
        // نفس السرعة وعدد الأجهزة 1 لكن الراوتر غير محدود: كانت المعاينة القديمة تقول «لا فرق»
        assertEquals(setOf(RouterProfileImport.DIFF_SHARED), RouterProfileImport.differing(stored, stored.copy(sharedUnlimited = true)))
        assertEquals(setOf(RouterProfileImport.DIFF_VALIDITY), RouterProfileImport.differing(stored, stored.copy(validitySeconds = 3_600L)))
        assertEquals(setOf(RouterProfileImport.DIFF_VALIDITY), RouterProfileImport.differing(stored.copy(validitySeconds = 3_600L), stored.copy(validityUnlimited = true)))
        assertEquals(setOf(RouterProfileImport.DIFF_START), RouterProfileImport.differing(stored.copy(validityStart = "first_auth"), stored.copy(validityStart = "assigned")))
        assertEquals(setOf(RouterProfileImport.DIFF_UPTIME, RouterProfileImport.DIFF_TRANSFER), RouterProfileImport.differing(stored, stored.copy(limitUptimeSeconds = 1L, limitTransferBytes = 2L)))
        // سياسة بداية غير مقروءة من الراوتر لا تُعدّ فرقاً
        assertTrue(RouterProfileImport.differing(stored.copy(validityStart = "assigned"), stored.copy(validityStart = null)).isEmpty())
        // العدد لا يُقارَن مع الراوتر غير المحدود
        assertTrue(RouterProfileImport.differing(stored.copy(sharedUnlimited = true, sharedUsers = 7), stored.copy(sharedUnlimited = true)).isEmpty())
    }

    @Test
    fun optionOf_ignores_hours_when_validity_is_unlimited_and_uses_legacy_quota() {
        val p = Profile(id = "p", validityHours = 5, validityUnlimited = true, quotaMb = 100)
        val o = RouterProfileImport.optionOf(p)
        assertTrue(o.validityUnlimited); assertNull(o.validitySeconds)
        assertEquals(100L * 1_048_576L, o.limitTransferBytes)
    }
}
