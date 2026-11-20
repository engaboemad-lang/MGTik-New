package ps.mgtik.hotspot.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.data.cloud.source.ProfileSettingsReader
import ps.mgtik.hotspot.data.cloud.source.ProfileSettingsReader.Result
import ps.mgtik.hotspot.data.mapping.FakeDocFields
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.usecase.OperationalSettingsResolver
import ps.mgtik.hotspot.domain.usecase.RouterProfileSettings

/**
 * المسار الفعلي للإنشاء الفردي والتوليد: وثيقة Firestore ← ProfileSettingsReader ← الحسم ← مقارنة الراوتر.
 * اختبارات الحسم وحدها كانت تبني Profile مباشرة فلم تكشف أن القارئ كان يُسقط حقول الصلاحية وغير المحدود.
 */
class ProfileSettingsReaderTest {

    private fun ok(r: Result) = (r as Result.Ok).settings

    private val doc = mapOf(
        "status" to "active", "shared_users" to 2L, "rate_limit_tx_kbps" to 8_000L, "rate_limit_rx_kbps" to 2_000L,
        "limit_uptime_seconds" to 3_600L, "limit_transfer_bytes" to 1_000L,
        "validity_hours" to 24L, "validity_start" to "assigned", "own_settings" to emptyList<String>()
    )

    @Test
    fun validity_fields_reach_the_resolved_settings() {
        val s = ok(ProfileSettingsReader.resolveFields(FakeDocFields("p", doc - "own_settings"), null))
        assertEquals("assigned", s.validityStart)
        assertEquals(86_400L, s.validitySeconds)
        assertFalse(s.validityUnlimited)
    }

    @Test
    fun assigned_policy_matches_a_router_on_assigned_and_no_longer_blocks_it() {
        val s = ok(ProfileSettingsReader.resolveFields(FakeDocFields("p", doc - "own_settings"), null))
        val router = RouterProfileSettings(startMode = "assigned", validitySeconds = 86_400L, validityKnown = true)
        assertTrue(OperationalSettingsResolver.validityMismatches(s, router).isEmpty())
        // وتُكتشف المخالفة الحقيقية (كانت تمرّ لأن المدة كانت تُقرأ null)
        assertEquals(setOf("validity"), OperationalSettingsResolver.validityMismatches(s, router.copy(validitySeconds = 3_600L)))
        assertEquals(setOf("validity-start"), OperationalSettingsResolver.validityMismatches(s, router.copy(startMode = "first_auth")))
    }

    @Test
    fun unlimited_validity_and_unlimited_devices_are_read_from_the_document() {
        val s = ok(ProfileSettingsReader.resolveFields(
            FakeDocFields("p", doc - "own_settings" + mapOf("validity_unlimited" to true, "shared_unlimited" to true)), null
        ))
        assertTrue(s.validityUnlimited); assertTrue(s.sharedUnlimited); assertNull(s.sharedUsers)
        val router = RouterProfileSettings(8_000, 2_000, 1, validityKnown = true, validityUnlimited = false, sharedUnlimited = false)
        assertEquals(setOf(OperationalSetting.SHARED_USERS), OperationalSettingsResolver.mismatches(s, router))
        assertEquals(setOf("validity"), OperationalSettingsResolver.validityMismatches(s, router))
    }

    @Test
    fun classification_is_inherited_through_the_same_path() {
        val cls = FakeDocFields("cls", mapOf("download_kbps" to 10_000L, "upload_kbps" to 5_000L, "shared_users" to 4L,
            "limit_uptime_seconds" to 7_200L))
        val s = ok(ProfileSettingsReader.resolveFields(FakeDocFields("p", doc + ("classification_key" to "cls")), cls))
        assertEquals(10_000, s.downloadKbps); assertEquals(5_000, s.uploadKbps); assertEquals(4, s.sharedUsers)
        assertEquals(7_200L, s.limits.uptimeSeconds)
        assertEquals(86_400L, s.validitySeconds) // الصلاحية للبروفايل نفسه لا للتصنيف
    }

    @Test
    fun missing_classification_and_invalid_limits_are_explicit_errors() {
        val missing = ProfileSettingsReader.resolveFields(FakeDocFields("p", doc + ("classification_key" to "gone")), null) as Result.Invalid
        assertEquals(OperationalSettingsResolver.ERR_CLASSIFICATION_MISSING, missing.code)
        val bad = ProfileSettingsReader.resolveFields(FakeDocFields("p", doc - "own_settings" + ("limit_uptime_seconds" to 0L)), null) as Result.Invalid
        assertEquals(OperationalSettingsResolver.ERR_LIMIT_INVALID, bad.code)
    }
}
