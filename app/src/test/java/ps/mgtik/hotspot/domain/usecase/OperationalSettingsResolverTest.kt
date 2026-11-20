package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.generation.UserLimits
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileClassification

class OperationalSettingsResolverTest {

    private val cls = ProfileClassification(
        key = "c1", downloadKbps = 8_000, uploadKbps = 2_000, sharedUsers = 3,
        limitUptimeSeconds = 3_600L, limitTransferBytes = 1_073_741_824L
    )

    private fun ok(o: OperationalSettingsResolver.Outcome) = (o as OperationalSettingsResolver.Outcome.Ok).settings

    @Test
    fun inherited_settings_come_from_classification_not_from_stale_profile_fields() {
        val p = Profile(
            id = "p", classificationKey = "c1", ownSettings = emptySet(),
            rateLimitRxKbps = 1, rateLimitTxKbps = 1, sharedUsers = 1, limitUptimeSeconds = 10L, limitTransferBytes = 10L
        )
        val s = ok(OperationalSettingsResolver.resolve(p, cls))
        assertEquals(UserLimits(3_600L, 1_073_741_824L), s.limits)
        assertEquals(8_000, s.downloadKbps); assertEquals(2_000, s.uploadKbps); assertEquals(3, s.sharedUsers)
    }

    @Test
    fun own_settings_win_and_unspecified_stays_null_without_invented_defaults() {
        val p = Profile(
            id = "p", classificationKey = "c1",
            ownSettings = setOf(OperationalSetting.LIMIT_UPTIME, OperationalSetting.LIMIT_TRANSFER, OperationalSetting.SPEED),
            sharedUsers = 1
        )
        val s = ok(OperationalSettingsResolver.resolve(p, cls))
        assertNull(s.limits.uptimeSeconds); assertNull(s.limits.transferBytes)
        assertNull(s.downloadKbps); assertNull(s.uploadKbps)
        assertEquals(3, s.sharedUsers) // غير خاص بالبروفايل ⇒ موروث
    }

    @Test
    fun missing_classification_is_an_explicit_error() {
        val p = Profile(id = "p", classificationKey = "gone", ownSettings = emptySet())
        val o = OperationalSettingsResolver.resolve(p, null) as OperationalSettingsResolver.Outcome.Invalid
        assertEquals(OperationalSettingsResolver.ERR_CLASSIFICATION_MISSING, o.code)
    }

    @Test
    fun profile_without_classification_uses_its_own_values() {
        val p = Profile(id = "p", limitUptimeSeconds = 60L, rateLimitTxKbps = 500, rateLimitRxKbps = 250)
        val s = ok(OperationalSettingsResolver.resolve(p, null))
        assertEquals(60L, s.limits.uptimeSeconds); assertEquals(500, s.downloadKbps); assertEquals(250, s.uploadKbps)
    }

    @Test
    fun zero_or_out_of_range_limits_are_rejected() {
        val p = Profile(id = "p", limitUptimeSeconds = 0L)
        val o = OperationalSettingsResolver.resolve(p, null) as OperationalSettingsResolver.Outcome.Invalid
        assertEquals(OperationalSettingsResolver.ERR_LIMIT_INVALID, o.code)
    }

    @Test
    fun router_profile_mismatch_detection() {
        val expected = ResolvedUserSettings(UserLimits.NONE, downloadKbps = 8_000, uploadKbps = 2_000, sharedUsers = 3)
        assertTrue(OperationalSettingsResolver.mismatches(expected, RouterProfileSettings(8_000, 2_000, 3)).isEmpty())
        assertEquals(setOf(OperationalSetting.SPEED), OperationalSettingsResolver.mismatches(expected, RouterProfileSettings(1_000, 2_000, 3)))
        assertEquals(setOf(OperationalSetting.SHARED_USERS), OperationalSettingsResolver.mismatches(expected, RouterProfileSettings(8_000, 2_000, 1)))
        // بلا سرعة محسومة يقابلها 0 على الراوتر
        assertTrue(OperationalSettingsResolver.mismatches(ResolvedUserSettings(sharedUsers = 1), RouterProfileSettings(0, 0, 1)).isEmpty())
        assertEquals(setOf(OperationalSetting.SPEED), OperationalSettingsResolver.mismatches(ResolvedUserSettings(sharedUsers = 1), RouterProfileSettings(5_000, 0, 1)))
    }

    @Test
    fun limits_match_requires_exact_equality() {
        assertTrue(OperationalSettingsResolver.limitsMatch(UserLimits(60L, null), 60L, null))
        assertTrue(!OperationalSettingsResolver.limitsMatch(UserLimits(60L, null), null, null))
    }

    @Test
    fun router_limits_are_compared_only_when_known() {
        val expected = ResolvedUserSettings(UserLimits(3_600L, 1_000L), sharedUsers = 1)
        assertTrue(OperationalSettingsResolver.mismatches(expected, RouterProfileSettings(0, 0, 1)).isEmpty())
        assertEquals(
            setOf(OperationalSetting.LIMIT_UPTIME, OperationalSetting.LIMIT_TRANSFER),
            OperationalSettingsResolver.mismatches(expected, RouterProfileSettings(0, 0, 1, limitsKnown = true))
        )
        assertTrue(
            OperationalSettingsResolver.mismatches(
                expected, RouterProfileSettings(0, 0, 1, uptimeSeconds = 3_600L, transferBytes = 1_000L, limitsKnown = true)
            ).isEmpty()
        )
    }

    @Test
    fun validity_policy_is_compared_only_when_known() {
        val expected = ResolvedUserSettings(validityStart = "assigned", validitySeconds = 86_400L)
        assertTrue(OperationalSettingsResolver.validityMismatches(expected, RouterProfileSettings(startMode = "first_auth")).isEmpty())
        assertEquals(
            setOf("validity-start", "validity"),
            OperationalSettingsResolver.validityMismatches(expected, RouterProfileSettings(startMode = "first_auth", validitySeconds = 3_600L, validityKnown = true))
        )
        assertTrue(
            OperationalSettingsResolver.validityMismatches(expected, RouterProfileSettings(startMode = "assigned", validitySeconds = 86_400L, validityKnown = true)).isEmpty()
        )
        assertEquals(
            setOf("validity"),
            OperationalSettingsResolver.validityMismatches(ResolvedUserSettings(validityUnlimited = true), RouterProfileSettings(validitySeconds = 5L, validityKnown = true))
        )
    }

    // ── sharedUnlimited: غير محدود ≠ 1، في الحسم والمقارنة ───────────────────────

    @Test
    fun shared_unlimited_profile_resolves_to_unlimited_not_to_a_number() {
        val p = Profile(id = "p", classificationKey = "c1", ownSettings = setOf(OperationalSetting.SHARED_USERS), sharedUsers = 1, sharedUnlimited = true)
        val s = ok(OperationalSettingsResolver.resolve(p, cls))
        assertTrue(s.sharedUnlimited); assertNull(s.sharedUsers)
    }

    @Test
    fun shared_unlimited_is_compared_against_the_router_in_both_directions() {
        val unlimited = ResolvedUserSettings(sharedUnlimited = true)
        val one = ResolvedUserSettings(sharedUsers = 1)
        // الراوتر يعيد sharedUsers = 1 للباقة غير المحدودة: لا يكفي مقارنة العدد
        val routerUnlimited = RouterProfileSettings(0, 0, 1, sharedUnlimited = true)
        val routerOne = RouterProfileSettings(0, 0, 1)
        assertTrue(OperationalSettingsResolver.mismatches(unlimited, routerUnlimited).isEmpty())
        assertTrue(OperationalSettingsResolver.mismatches(one, routerOne).isEmpty())
        assertEquals(setOf(OperationalSetting.SHARED_USERS), OperationalSettingsResolver.mismatches(unlimited, routerOne))
        assertEquals(setOf(OperationalSetting.SHARED_USERS), OperationalSettingsResolver.mismatches(one, routerUnlimited))
        // محدود بعدد آخر على راوتر غير محدود، وغير محدد (null) على راوتر غير محدود = مخالفة أيضاً
        assertEquals(setOf(OperationalSetting.SHARED_USERS), OperationalSettingsResolver.mismatches(ResolvedUserSettings(sharedUsers = 3), routerUnlimited))
        assertEquals(setOf(OperationalSetting.SHARED_USERS), OperationalSettingsResolver.mismatches(ResolvedUserSettings(), routerUnlimited))
    }

    // ── limitationLinked: Limitation التطبيق اليتيمة لا تمرّ بصمت ─────────────────

    @Test
    fun orphaned_app_limitation_flags_every_setting_that_depends_on_it() {
        val expected = ResolvedUserSettings(UserLimits(3_600L, 1_000L), downloadKbps = 8_000, uploadKbps = 2_000, sharedUsers = 1)
        // قيم متطابقة تماماً (قادمة من قيد غريب) لكن قيد التطبيق غير مربوط: يجب أن يُكشف لا أن يمرّ
        val actual = RouterProfileSettings(8_000, 2_000, 1, uptimeSeconds = 3_600L, transferBytes = 1_000L, limitsKnown = true, limitationLinked = false)
        assertEquals(
            setOf(OperationalSetting.SPEED, OperationalSetting.LIMIT_UPTIME, OperationalSetting.LIMIT_TRANSFER),
            OperationalSettingsResolver.mismatches(expected, actual)
        )
        // ما لا يعتمد على القيد لا يُبلَّغ: لا سرعة ولا حدود مطلوبة
        assertTrue(OperationalSettingsResolver.mismatches(ResolvedUserSettings(sharedUsers = 1), RouterProfileSettings(0, 0, 1, limitsKnown = true, limitationLinked = false)).isEmpty())
        // غير مقروءة الحدود = لا مقارنة (سلوك المستدعين القدامى)
        assertTrue(OperationalSettingsResolver.mismatches(expected, RouterProfileSettings(8_000, 2_000, 1, limitationLinked = false)).isEmpty())
    }

    @Test
    fun linked_or_absent_limitation_is_not_orphaned() {
        val expected = ResolvedUserSettings(UserLimits(3_600L, null), downloadKbps = 8_000, uploadKbps = 2_000, sharedUsers = 1)
        val actual = RouterProfileSettings(8_000, 2_000, 1, uptimeSeconds = 3_600L, transferBytes = null, limitsKnown = true, limitationLinked = true)
        assertTrue(OperationalSettingsResolver.mismatches(expected, actual).isEmpty())
    }
}
