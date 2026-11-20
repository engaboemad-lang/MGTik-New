package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.CategoryStatus
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.ProfileClassification
import ps.mgtik.hotspot.domain.model.ProfileStatus
import ps.mgtik.hotspot.domain.model.SettingSource
import ps.mgtik.hotspot.domain.model.SystemClass

class ClassificationRulesTest {

    private val gold = ProfileClassification(
        key = "c1", nameAr = "ذهبي", downloadKbps = 5_000, uploadKbps = 2_000,
        sharedUsers = 1, limitUptimeSeconds = 30 * 86_400L, limitTransferBytes = 10L shl 30
    )

    private fun profile(
        own: Set<OperationalSetting>? = emptySet(),
        key: String? = "c1",
        rx: Int = 0, tx: Int = 0, shared: Int = 1, up: Long? = null, bytes: Long? = null,
        price: Long = 0, category: String? = "cat"
    ) = Profile(
        id = "p", classificationKey = key, ownSettings = own, categoryId = category,
        rateLimitRxKbps = rx, rateLimitTxKbps = tx, sharedUsers = shared,
        limitUptimeSeconds = up, limitTransferBytes = bytes, priceMinor = price
    )

    @Test
    fun `valid classification has no errors and rejects bad values`() {
        assertTrue(ClassificationRules.validate(gold).isEmpty())
        assertEquals(ClassificationRules.ERR_NAME_REQUIRED, ClassificationRules.validate(gold.copy(nameAr = " "))["name"])
        assertEquals(ClassificationRules.ERR_SPEED_INVALID, ClassificationRules.validate(gold.copy(downloadKbps = 0))["speed"])
        assertEquals(ClassificationRules.ERR_SHARED_USERS_INVALID, ClassificationRules.validate(gold.copy(sharedUsers = 0))["sharedUsers"])
        assertEquals(ClassificationRules.ERR_UPTIME_INVALID, ClassificationRules.validate(gold.copy(limitUptimeSeconds = 0))["uptime"])
        assertEquals(ClassificationRules.ERR_TRANSFER_INVALID, ClassificationRules.validate(gold.copy(limitTransferBytes = -1))["transfer"])
    }

    @Test
    fun `profile without own settings inherits everything the classification defines`() {
        val e = ClassificationRules.effective(profile(), gold)
        assertEquals(2_000, e.rxKbps)
        assertEquals(5_000, e.txKbps)
        assertEquals(30 * 86_400L, e.limitUptimeSeconds)
        assertTrue(e.sources.values.all { it == SettingSource.INHERITED })
    }

    @Test
    fun `own setting wins for that setting only`() {
        val e = ClassificationRules.effective(profile(own = setOf(OperationalSetting.SPEED), rx = 100, tx = 200), gold)
        assertEquals(100, e.rxKbps)
        assertEquals(200, e.txKbps)
        assertEquals(SettingSource.PROFILE, e.sources[OperationalSetting.SPEED])
        assertEquals(30 * 86_400L, e.limitUptimeSeconds)
        assertEquals(SettingSource.INHERITED, e.sources[OperationalSetting.LIMIT_UPTIME])
    }

    @Test
    fun `legacy profile (ownSettings null) and profile without classification inherit nothing`() {
        val legacy = ClassificationRules.effective(profile(own = null, rx = 7, tx = 8), gold)
        assertEquals(7, legacy.rxKbps)
        assertTrue(legacy.sources.values.all { it == SettingSource.PROFILE })
        val none = ClassificationRules.effective(profile(key = null, rx = 7, tx = 8), null)
        assertEquals(8, none.txKbps)
    }

    @Test
    fun `setting the classification does not define stays with the profile`() {
        val c = gold.copy(limitUptimeSeconds = null)
        val e = ClassificationRules.effective(profile(up = 3600), c)
        assertEquals(3600L, e.limitUptimeSeconds)
        assertEquals(SettingSource.PROFILE, e.sources[OperationalSetting.LIMIT_UPTIME])
    }

    @Test
    fun `editing a classification leaves stored profile values untouched until applied`() {
        val applied = ClassificationRules.materialize(profile(), gold)
        assertTrue(ClassificationRules.pendingChanges(applied, gold).isEmpty())
        val edited = gold.copy(downloadKbps = 8_000)
        assertEquals(5_000, applied.rateLimitTxKbps)
        assertEquals(setOf(OperationalSetting.SPEED), ClassificationRules.pendingChanges(applied, edited))
    }

    @Test
    fun `classification change never alters price or category (independence)`() {
        val p = profile(price = 2_000, category = "cat")
        val m = ClassificationRules.materialize(p, gold)
        assertEquals(2_000L, m.priceMinor)
        assertEquals("cat", m.categoryId)
        assertEquals("c1", m.classificationKey)
    }

    @Test
    fun `plan covers only inheriting non archived profiles with a change`() {
        val subCat = ProfileCategory(id = "sub", nameAr = "اشتراك", systemClass = SystemClass.SUBSCRIPTION)
        val normalCat = ProfileCategory(id = "cat", nameAr = "عادي", systemClass = SystemClass.NORMAL)
        val stale = profile().copy(id = "a")
        val upToDate = ClassificationRules.materialize(profile(), gold).copy(id = "b")
        val legacy = profile(own = null).copy(id = "c")
        val archived = profile().copy(id = "d", status = ProfileStatus.ARCHIVED)
        val other = profile(key = "zzz").copy(id = "e")
        val subscription = profile(category = "sub").copy(id = "f")
        val plan = ClassificationRules.planApply(gold, listOf(stale, upToDate, legacy, archived, other, subscription), listOf(subCat, normalCat))
        assertEquals(listOf("a", "f"), plan.items.map { it.profile.id })
        val a = plan.items.first { it.profile.id == "a" }
        assertTrue(a.touchesRouterProfile && a.touchesUserLimits && !a.userLimitsManagedBySubscription)
        assertTrue(plan.items.first { it.profile.id == "f" }.userLimitsManagedBySubscription)
    }

    @Test
    fun `only active classifications accept new links and names are unique`() {
        assertNull(ClassificationRules.checkAssignable(gold))
        assertEquals(ClassificationRules.ERR_INACTIVE, ClassificationRules.checkAssignable(gold.copy(status = CategoryStatus.ARCHIVED)))
        assertEquals(ClassificationRules.ERR_INACTIVE, ClassificationRules.checkAssignable(null))
        assertTrue(ClassificationRules.nameClash(gold.copy(key = "c2", nameAr = " ذهبي "), listOf(gold)))
        assertFalse(ClassificationRules.nameClash(gold, listOf(gold)))
    }
}
