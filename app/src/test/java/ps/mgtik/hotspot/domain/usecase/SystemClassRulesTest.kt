package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.data.local.entity.LocalUserEntity
import ps.mgtik.hotspot.domain.model.LocalUserRecord
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SystemClass

class SystemClassRulesTest {

    private val normalCat = ProfileCategory(id = "c1", nameAr = "عادية", systemClass = SystemClass.NORMAL)
    private val subCat = ProfileCategory(id = "c2", nameAr = "شهرية", systemClass = SystemClass.SUBSCRIPTION)
    private val legacyCat = ProfileCategory(id = "c3", nameAr = "قديمة") // بلا تصنيف

    @Test fun `wire values round-trip and unknown is null`() {
        assertEquals("normal", SystemClass.NORMAL.wire)
        assertEquals(SystemClass.SUBSCRIPTION, SystemClass.fromWire("SUBSCRIPTION"))
        assertEquals(SystemClass.NORMAL, SystemClass.fromWire("normal"))
        assertNull(SystemClass.fromWire("vip"))
        assertNull(SystemClass.fromWire(null))
    }

    @Test fun `profile inherits class from its category and legacy stays null`() {
        val cats = listOf(normalCat, subCat, legacyCat)
        assertEquals(SystemClass.SUBSCRIPTION, SystemClassRules.classOf(Profile(id = "p", categoryId = "c2"), cats))
        assertNull(SystemClassRules.classOf(Profile(id = "p", categoryId = "c3"), cats))
        assertNull(SystemClassRules.classOf(Profile(id = "p", categoryId = null), cats))
        assertNull(SystemClassRules.classOf(Profile(id = "p", categoryId = "missing"), cats))
    }

    @Test fun `new profile needs an existing classified category`() {
        assertEquals(SystemClassRules.ERR_PROFILE_CATEGORY_REQUIRED, SystemClassRules.checkCategoryForNewProfile(false, null))
        assertEquals(SystemClassRules.ERR_CATEGORY_UNCLASSIFIED, SystemClassRules.checkCategoryForNewProfile(true, legacyCat))
        assertEquals(SystemClassRules.ERR_CATEGORY_UNCLASSIFIED, SystemClassRules.checkCategoryForNewProfile(true, null))
        assertNull(SystemClassRules.checkCategoryForNewProfile(true, normalCat))
    }

    @Test fun `profile must match the required class for the operation`() {
        assertNull(SystemClassRules.checkProfileFor(SystemClass.SUBSCRIPTION, true, subCat))
        assertEquals(SystemClassRules.ERR_PROFILE_CLASS_MISMATCH, SystemClassRules.checkProfileFor(SystemClass.SUBSCRIPTION, true, normalCat))
        assertEquals(SystemClassRules.ERR_CATEGORY_UNCLASSIFIED, SystemClassRules.checkProfileFor(SystemClass.SUBSCRIPTION, true, legacyCat))
        assertEquals(SystemClassRules.ERR_PROFILE_CATEGORY_REQUIRED, SystemClassRules.checkProfileFor(SystemClass.NORMAL, false, null))
    }

    @Test fun `moving a profile never changes its system class`() {
        assertTrue(SystemClassRules.moveAllowed(SystemClass.NORMAL, SystemClass.NORMAL))
        assertFalse(SystemClassRules.moveAllowed(SystemClass.NORMAL, SystemClass.SUBSCRIPTION))
        assertFalse(SystemClassRules.moveAllowed(SystemClass.SUBSCRIPTION, SystemClass.NORMAL))
        assertTrue(SystemClassRules.moveAllowed(null, SystemClass.SUBSCRIPTION)) // فئة قديمة غير مصنّفة لا تقيّد
    }

    @Test fun `legacy user records default to NORMAL and class is stored stably`() {
        val legacy = LocalUserRecord(recordUuid = "u", routerUsername = "x", ownerId = "o")
        assertEquals(SystemClass.NORMAL, legacy.systemClass)

        val sub = legacy.copy(systemClass = SystemClass.SUBSCRIPTION)
        val roundTrip = LocalUserEntity.fromDomain(sub).toDomainOrNull()
        assertEquals(SystemClass.SUBSCRIPTION, roundTrip?.systemClass)

        // قيمة تالفة في الكاش لا تتحول إلى NORMAL بصمت
        assertNull(LocalUserEntity.fromDomain(sub).copy(systemClass = "bogus").toDomainOrNull())
    }
}
