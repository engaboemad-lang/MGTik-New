package ps.mgtik.hotspot.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.CategoryStatus
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.ProfileStatus

/** وثيقة اختبارية مبنية على خريطة: تحاكي ما يخزّنه Firestore (الأعداد Long، القوائم List). */
class FakeDocFields(override val id: String, private val m: Map<String, Any?>) : DocFields {
    override fun string(key: String) = m[key] as? String
    override fun long(key: String) = (m[key] as? Number)?.toLong()
    override fun bool(key: String) = m[key] as? Boolean
    override fun list(key: String) = m[key] as? List<*>
    override fun isoTime(key: String) = (m[key] as? String).orEmpty()
}

class ProfileDocMapperTest {

    private val full = mapOf(
        "linked_profile_name" to "gold", "display_name_ar" to "ذهبي", "category_id" to "C1", "classification_key" to "cls",
        "status" to "active", "version" to 3L,
        "rate_limit_tx_kbps" to 8_000L, "rate_limit_rx_kbps" to 2_000L, "price_minor" to 500L,
        "validity_hours" to 24L, "quota_mb" to 100L, "shared_users" to 2L,
        "limit_uptime_seconds" to 3_600L, "limit_transfer_bytes" to 1_000L,
        "own_settings" to listOf("speed", "SHARED_USERS"),
        "validity_start" to "assigned", "validity_unlimited" to true, "shared_unlimited" to true
    )

    @Test
    fun every_operational_field_survives_the_mapping() {
        val p = ProfileDocMapper.profile(FakeDocFields("p1", full), ProfileStatus.ACTIVE)
        assertEquals("p1", p.id); assertEquals("cls", p.classificationKey)
        assertEquals(8_000, p.rateLimitTxKbps); assertEquals(2_000, p.rateLimitRxKbps); assertEquals(2, p.sharedUsers)
        assertEquals(24, p.validityHours); assertEquals(100, p.quotaMb)
        assertEquals(3_600L, p.limitUptimeSeconds); assertEquals(1_000L, p.limitTransferBytes)
        assertEquals(setOf(OperationalSetting.SPEED, OperationalSetting.SHARED_USERS), p.ownSettings)
        assertEquals("assigned", p.validityStart); assertTrue(p.validityUnlimited); assertTrue(p.sharedUnlimited)
    }

    @Test
    fun missing_fields_keep_the_legacy_defaults() {
        val p = ProfileDocMapper.profile(FakeDocFields("p2", emptyMap()), ProfileStatus.ACTIVE)
        assertNull(p.ownSettings)              // بروفايل قديم: لا وراثة بالتخمين
        assertNull(p.limitUptimeSeconds); assertNull(p.limitTransferBytes)
        assertEquals("first_auth", p.validityStart)
        assertFalse(p.validityUnlimited); assertFalse(p.sharedUnlimited)
        assertEquals(1, p.sharedUsers); assertEquals(0, p.validityHours)
        assertEquals(emptySet<OperationalSetting>(), ProfileDocMapper.profile(FakeDocFields("p3", mapOf("own_settings" to emptyList<String>())), ProfileStatus.ACTIVE).ownSettings)
    }

    @Test
    fun unknown_validity_start_falls_back_to_first_auth() {
        assertEquals("first_auth", ProfileDocMapper.profile(FakeDocFields("p", mapOf("validity_start" to "weird")), ProfileStatus.ACTIVE).validityStart)
    }

    @Test
    fun operational_status_is_lenient_but_screen_status_stays_strict_via_caller() {
        assertEquals(ProfileStatus.DISABLED, ProfileDocMapper.statusOrActive(FakeDocFields("p", mapOf("status" to "disabled"))))
        assertEquals(ProfileStatus.ACTIVE, ProfileDocMapper.statusOrActive(FakeDocFields("p", mapOf("status" to "???"))))
        assertEquals(ProfileStatus.ACTIVE, ProfileDocMapper.statusOrActive(FakeDocFields("p", emptyMap())))
    }

    @Test
    fun classification_maps_operational_fields_and_defaults_old_docs_to_undefined() {
        val c = ProfileDocMapper.classification(
            FakeDocFields("cls", mapOf("name_ar" to "ت", "download_kbps" to 8_000L, "upload_kbps" to 2_000L, "shared_users" to 3L,
                "limit_uptime_seconds" to 60L, "limit_transfer_bytes" to 10L, "status" to "archived"))
        )
        assertEquals("cls", c.key); assertEquals(8_000, c.downloadKbps); assertEquals(3, c.sharedUsers)
        assertEquals(60L, c.limitUptimeSeconds); assertEquals(10L, c.limitTransferBytes); assertEquals(CategoryStatus.ARCHIVED, c.status)
        val old = ProfileDocMapper.classification(FakeDocFields("old", mapOf("name_ar" to "قديم")))
        assertNull(old.downloadKbps); assertNull(old.sharedUsers); assertNull(old.limitUptimeSeconds)
        assertEquals(CategoryStatus.ACTIVE, old.status)
    }
}
