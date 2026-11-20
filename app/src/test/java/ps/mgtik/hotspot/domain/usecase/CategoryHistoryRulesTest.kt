package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.CategoryHistoryAction
import ps.mgtik.hotspot.domain.model.FieldChange
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileUsage

class CategoryHistoryRulesTest {
    private fun p(id: String) = Profile(id = id, linkedProfileName = id, displayNameAr = id)

    @Test fun diff_records_old_and_new_values_only_for_changed_tracked_fields() {
        val old = mapOf<String, Any?>("retail_price_minor" to 1000L, "wholesale_price_minor" to 800L, "currency" to "ILS", "name_ar" to "x")
        val new = mapOf<String, Any?>("retail_price_minor" to 1200L, "wholesale_price_minor" to 800L, "currency" to "ILS", "name_ar" to "y")
        assertEquals(listOf(FieldChange("retail_price_minor", "1000", "1200")), CategoryHistoryRules.diff(old, new))
    }

    @Test fun diff_creation_records_new_values_with_null_old() {
        val d = CategoryHistoryRules.diff(null, mapOf("retail_price_minor" to 500L, "currency" to "USD"))
        assertEquals(listOf(FieldChange("retail_price_minor", null, "500"), FieldChange("currency", null, "USD")), d)
    }

    @Test fun diff_clearing_a_price_is_a_change_to_null() {
        val d = CategoryHistoryRules.diff(mapOf("wholesale_price_minor" to 800L), mapOf("wholesale_price_minor" to null))
        assertEquals(listOf(FieldChange("wholesale_price_minor", "800", null)), d)
    }

    @Test fun diff_ignores_untouched_fields_and_int_long_equivalence() {
        assertTrue(CategoryHistoryRules.diff(mapOf("retail_price_minor" to 5), mapOf("retail_price_minor" to 5L)).isEmpty())
        assertTrue(CategoryHistoryRules.diff(mapOf("retail_price_minor" to 5L), mapOf("name_ar" to "z")).isEmpty())
    }

    @Test fun wire_roundtrip_preserves_changes_and_null_values() {
        val ch = listOf(FieldChange("owner_price_minor", null, "1200"), FieldChange("active", "true", "false"))
        assertEquals(ch, CategoryHistoryRules.fromWire(CategoryHistoryRules.toWire(ch)))
        assertTrue(CategoryHistoryRules.fromWire("garbage").isEmpty())
    }

    @Test fun action_wire_roundtrip() {
        CategoryHistoryAction.entries.forEach { assertEquals(it, CategoryHistoryAction.fromWire(it.wire)) }
        assertNull(CategoryHistoryAction.fromWire("nope"))
    }

    @Test fun usage_sum_adds_all_profiles() {
        val u = mapOf("a" to ProfileUsage(3, 1, 2), "b" to ProfileUsage(5, 0, 1))
        val s = ProfileUsageRules.sum(listOf(p("a"), p("b")), u)!!
        assertEquals(8, s.normalUsers); assertEquals(1, s.subscriptions); assertEquals(3, s.batches); assertEquals(9, s.users)
    }

    @Test fun usage_sum_is_null_when_any_profile_count_missing_not_a_partial_total() {
        assertNull(ProfileUsageRules.sum(listOf(p("a"), p("c")), mapOf("a" to ProfileUsage(3, 1, 2))))
    }

    @Test fun usage_sum_of_empty_group_is_zero_not_null() {
        assertEquals(0, ProfileUsageRules.sum(emptyList(), emptyMap())!!.users)
    }
}
