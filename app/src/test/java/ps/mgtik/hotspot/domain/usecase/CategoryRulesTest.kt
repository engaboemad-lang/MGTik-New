package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.CategoryStatus
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SystemClass

class CategoryRulesTest {
    private val cat = ProfileCategory(
        id = "c", nameAr = "ذهبية", systemClass = SystemClass.NORMAL,
        currency = "₪", retailPriceMinor = 2_000, wholesalePriceMinor = 1_500
    )

    @Test
    fun `new category requires system type and currency`() {
        assertTrue(CategoryRules.validate(cat, isNew = true).isEmpty())
        assertEquals(SystemClassRules.ERR_CATEGORY_CLASS_REQUIRED, CategoryRules.validate(cat.copy(systemClass = null), true)["systemClass"])
        assertEquals(CategoryRules.ERR_CURRENCY_REQUIRED, CategoryRules.validate(cat.copy(currency = ""), true)["currency"])
    }

    @Test
    fun `prices are optional but never zero or negative`() {
        assertTrue(CategoryRules.validate(cat.copy(retailPriceMinor = null, wholesalePriceMinor = null), true).isEmpty())
        assertEquals(CategoryRules.ERR_PRICE_INVALID, CategoryRules.validate(cat.copy(retailPriceMinor = 0), true)["retailPrice"])
        assertEquals(CategoryRules.ERR_PRICE_INVALID, CategoryRules.validate(cat.copy(wholesalePriceMinor = -1), true)["wholesalePrice"])
    }

    @Test
    fun `system type cannot change once set - legacy unclassified may be set once`() {
        assertEquals(SystemClassRules.ERR_CATEGORY_CLASS_IMMUTABLE, SystemClassRules.checkClassImmutable(SystemClass.NORMAL, SystemClass.SUBSCRIPTION))
        assertNull(SystemClassRules.checkClassImmutable(SystemClass.NORMAL, SystemClass.NORMAL))
        assertNull(SystemClassRules.checkClassImmutable(null, SystemClass.SUBSCRIPTION))
    }

    @Test
    fun `currency cannot change once set`() {
        assertEquals(CategoryRules.ERR_CURRENCY_IMMUTABLE, CategoryRules.checkCurrencyImmutable("₪", "$"))
        assertNull(CategoryRules.checkCurrencyImmutable("₪", "₪"))
        assertNull(CategoryRules.checkCurrencyImmutable(null, "$"))
    }

    @Test
    fun `archived or missing category accepts no new links`() {
        assertNull(CategoryRules.checkAcceptsNewLinks(cat))
        assertEquals(ReferenceGuard.ERR_CATEGORY_INACTIVE, CategoryRules.checkAcceptsNewLinks(cat.copy(status = CategoryStatus.ARCHIVED)))
        assertEquals(ReferenceGuard.ERR_CATEGORY_INACTIVE, CategoryRules.checkAcceptsNewLinks(null))
    }

    @Test
    fun `incompatible system type is rejected`() {
        assertNull(CategoryRules.checkSystemTypeCompatible(SystemClass.NORMAL, cat))
        assertEquals(SystemClassRules.ERR_PROFILE_CLASS_MISMATCH, CategoryRules.checkSystemTypeCompatible(SystemClass.SUBSCRIPTION, cat))
        assertEquals(SystemClassRules.ERR_CATEGORY_UNCLASSIFIED, CategoryRules.checkSystemTypeCompatible(SystemClass.NORMAL, cat.copy(systemClass = null)))
    }
}
