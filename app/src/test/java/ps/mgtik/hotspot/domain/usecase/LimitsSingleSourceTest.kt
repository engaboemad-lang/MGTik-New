package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.generation.UserLimits
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileClassification

/** مصدر واحد للحدود: ما يُدفع للباقة على الراوتر (بعد materialize) هو نفسه ما يتوقعه الحاسم عند التحقق. */
class LimitsSingleSourceTest {
    private val cls = ProfileClassification(key = "c", limitUptimeSeconds = 7_200L, limitTransferBytes = 2_097_152L, downloadKbps = 1000, uploadKbps = 500, sharedUsers = 2)

    private fun resolved(p: Profile, c: ProfileClassification?) =
        (OperationalSettingsResolver.resolve(p, c) as OperationalSettingsResolver.Outcome.Ok).settings.limits

    /** ما يدفعه toPackageSpec: الحقول بعد materialize ثم ownTransferBytes. */
    private fun pushed(p: Profile, c: ProfileClassification?): UserLimits {
        val m = ClassificationRules.materialize(p.copy(ownSettings = p.ownSettings ?: emptySet()), c)
        return UserLimits(m.limitUptimeSeconds, m.ownTransferBytes)
    }

    private fun assertConsistent(p: Profile, c: ProfileClassification?) = assertEquals(resolved(p, c), pushed(p, c))

    @Test fun direct_limits() = assertConsistent(Profile(id = "p", limitUptimeSeconds = 3_600L, limitTransferBytes = 1_048_576L, ownSettings = emptySet()), null)

    @Test fun legacy_quota_mb_is_seen_by_both_sides() {
        val p = Profile(id = "p", quotaMb = 500, ownSettings = null)
        assertEquals(500L * 1_048_576L, resolved(p, null).transferBytes)
        assertConsistent(p, null)
    }

    @Test fun inherited_limits_ignore_stale_own_fields_and_quota() {
        val p = Profile(id = "p", classificationKey = "c", ownSettings = emptySet(), limitUptimeSeconds = 1L, limitTransferBytes = 1L, quotaMb = 900)
        assertEquals(UserLimits(7_200L, 2_097_152L), resolved(p, cls))
        assertConsistent(p, cls)
    }

    @Test fun classification_edit_changes_both_sides_together() {
        val p = Profile(id = "p", classificationKey = "c", ownSettings = emptySet())
        val edited = cls.copy(limitUptimeSeconds = 10_800L, limitTransferBytes = null)
        assertEquals(UserLimits(10_800L, null), resolved(p, edited))
        assertConsistent(p, edited)
    }

    @Test fun own_transfer_with_inherited_uptime_mix() {
        val p = Profile(id = "p", classificationKey = "c", ownSettings = setOf(OperationalSetting.LIMIT_TRANSFER), limitTransferBytes = 5_242_880L)
        assertEquals(UserLimits(7_200L, 5_242_880L), resolved(p, cls))
        assertConsistent(p, cls)
    }

    @Test fun quota_fallback_does_not_create_false_pending_changes() {
        val p = Profile(id = "p", quotaMb = 100, ownSettings = null)
        assertTrue(ClassificationRules.pendingChanges(p, null).isEmpty())
    }

    @Test fun mismatch_between_router_and_package_limits_is_detected() {
        val expected = UserLimits(3_600L, null)
        assertTrue(OperationalSettingsResolver.limitsMatch(expected, 3_600L, null))
        assertTrue(!OperationalSettingsResolver.limitsMatch(expected, 3_600L, 1L))
        assertTrue(!OperationalSettingsResolver.limitsMatch(expected, null, null))
    }
}
