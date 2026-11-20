package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.Profile

/** التحقق المركزي: نفسه للحفظ المباشر ولما يصل من الطابور (أنواع الأرقام كما يفكّها CommandCodec: Long/Double). */
class ProfileFieldRulesTest {

    @Test
    fun valid_fields_pass_with_queue_number_types() {
        val f = mapOf<String, Any?>(
            "shared_users" to 3L, "shared_unlimited" to false, "rate_limit_rx_kbps" to 512.0, "rate_limit_tx_kbps" to 0,
            "validity_hours" to 24L, "validity_unlimited" to false, "validity_start" to "assigned",
            "limit_uptime_seconds" to null, "limit_transfer_bytes" to 1_073_741_824L
        )
        assertTrue(ProfileFieldRules.validateFields(f).isEmpty())
    }

    @Test
    fun shape_values_are_rejected() {
        val bad = ProfileFieldRules.validateFields(
            mapOf(
                "shared_users" to 0, "rate_limit_rx_kbps" to -1, "validity_hours" to 1.5, "validity_start" to "now",
                "shared_unlimited" to "true", "limit_uptime_seconds" to 0L, "limit_transfer_bytes" to -5L
            )
        )
        assertEquals(
            setOf("shared_users", "rate_limit_rx_kbps", "validity_hours", "validity_start", "shared_unlimited", "limit_uptime_seconds", "limit_transfer_bytes"),
            bad.keys
        )
        assertEquals(ProfileFieldRules.ERR_TYPE, bad["shared_unlimited"])
        assertEquals(ProfileFieldRules.ERR_SHARED_USERS, ProfileFieldRules.validateFields(mapOf("shared_users" to "3"))["shared_users"])
        assertEquals(ProfileFieldRules.ERR_SHARED_USERS, ProfileFieldRules.validateFields(mapOf("shared_users" to 10_000))["shared_users"])
    }

    @Test
    fun unlimited_validity_with_hours_is_a_conflict_only_when_those_fields_are_touched() {
        val stale = Profile(id = "p", validityUnlimited = true, validityHours = 48) // بيانات قديمة متناقضة
        // تعديل غير مرتبط لا يُحجب بسبب بيانات قديمة
        assertTrue(ProfileFieldRules.validateCombination(stale, setOf("display_name_ar")).isEmpty())
        assertEquals(
            ProfileFieldRules.ERR_VALIDITY_CONFLICT,
            ProfileFieldRules.validateCombination(stale, setOf("validity_hours"))["validity_unlimited"]
        )
    }

    @Test
    fun whole_profile_validation_ignores_stored_count_when_unlimited() {
        assertTrue(ProfileFieldRules.validateProfile(Profile(id = "p", sharedUnlimited = true, sharedUsers = 0)).isEmpty())
        assertEquals(setOf("shared_users"), ProfileFieldRules.validateProfile(Profile(id = "p", sharedUsers = 0)).keys)
    }
}
