package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileClassification
import ps.mgtik.hotspot.domain.model.SettingSource

/** عدد الأجهزة غير المحدود قرار الباقة: لا يستبدله عدد التصنيف حتى لو لم يُدرج SHARED_USERS في ownSettings. */
class ClassificationSharedUnlimitedTest {
    private val cls = ProfileClassification(key = "c", downloadKbps = 1_000, uploadKbps = 500, sharedUsers = 3)

    @Test
    fun unlimited_profile_owns_shared_users_even_if_not_listed_as_own() {
        val p = Profile(id = "p", classificationKey = "c", ownSettings = emptySet(), sharedUnlimited = true, sharedUsers = 1, rateLimitRxKbps = 500, rateLimitTxKbps = 1_000)
        val e = ClassificationRules.effective(p, cls)
        assertEquals(SettingSource.PROFILE, e.sources[OperationalSetting.SHARED_USERS])
        assertEquals(1, e.sharedUsers)
        assertTrue(ClassificationRules.pendingChanges(p, cls).isEmpty())
        assertEquals(1, ClassificationRules.materialize(p, cls).sharedUsers)
    }

    @Test
    fun limited_profile_still_inherits_classification_count() {
        val p = Profile(id = "p", classificationKey = "c", ownSettings = emptySet(), sharedUsers = 1, rateLimitRxKbps = 500, rateLimitTxKbps = 1_000)
        assertEquals(setOf(OperationalSetting.SHARED_USERS), ClassificationRules.pendingChanges(p, cls))
        assertEquals(3, ClassificationRules.effective(p, cls).sharedUsers)
    }

    @Test
    fun apply_plan_skips_unlimited_profiles_for_shared_users() {
        val p = Profile(id = "p", classificationKey = "c", ownSettings = emptySet(), sharedUnlimited = true, sharedUsers = 1, rateLimitRxKbps = 500, rateLimitTxKbps = 1_000)
        assertTrue(ClassificationRules.planApply(cls, listOf(p), emptyList()).items.isEmpty())
    }
}
