package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.PricingRule
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SaleContext

class OwnerLifecycleAndPriceBookTest {

    @Test fun activeOwnerMayOperate() = assertNull(OwnerCreditRules.checkOwnerCanOperate(true, "ACTIVE"))

    @Test fun suspendedAndArchivedOwnersAreBlocked() {
        assertEquals(OwnerCreditRules.ERR_OWNER_NOT_ACTIVE, OwnerCreditRules.checkOwnerCanOperate(true, "SUSPENDED"))
        assertEquals(OwnerCreditRules.ERR_OWNER_NOT_ACTIVE, OwnerCreditRules.checkOwnerCanOperate(true, "ARCHIVED"))
        assertEquals(OwnerCreditRules.ERR_OWNER_NOT_ACTIVE, OwnerCreditRules.checkOwnerCanOperate(true, null))
    }

    @Test fun missingOwnerIsNotFound() =
        assertEquals(OwnerCreditRules.ERR_OWNER_NOT_FOUND, OwnerCreditRules.checkOwnerCanOperate(false, null))

    @Test fun identityUpdateValidation() {
        assertNull(OwnerCreditRules.validateIdentityUpdate(mapOf("name" to "أحمد", "status" to "SUSPENDED")))
        assertEquals(OwnerCreditRules.ERR_OWNER_NAME_REQUIRED, OwnerCreditRules.validateIdentityUpdate(mapOf("name" to "  ")))
        assertEquals(OwnerCreditRules.ERR_OWNER_STATUS_INVALID, OwnerCreditRules.validateIdentityUpdate(mapOf("status" to "DELETED")))
        assertEquals(OwnerCreditRules.ERR_OWNER_STATUS_INVALID, OwnerCreditRules.validateIdentityUpdate(mapOf("status" to null)))
        assertNull(OwnerCreditRules.validateIdentityUpdate(mapOf("phone" to "0599000000")))
    }

    private val cat = ProfileCategory(id = "c1", nameAr = "يومي", currency = "₪", retailPriceMinor = 500, wholesalePriceMinor = 300)

    @Test fun priceBookShowsOnlyThisOwnersRulesWithBase() {
        val rules = listOf(
            PricingRule(id = "c1__o1__wholesale", ownerId = "o1", categoryId = "c1", context = SaleContext.WHOLESALE, retailPriceMinor = 250, currency = "₪"),
            PricingRule(id = "c1__o2__wholesale", ownerId = "o2", categoryId = "c1", context = SaleContext.WHOLESALE, retailPriceMinor = 100)
        )
        val book = OwnerPriceBookBuilder.build("o1", listOf(cat), rules)
        assertEquals(1, book.rows.size)
        val row = book.rows.single()
        assertEquals(300L, row.baseMinor)
        assertEquals(-50L, row.deltaMinor)
        assertTrue(row.applied)
    }

    @Test fun disabledOrInvalidRuleIsNotApplied() {
        val rules = listOf(
            PricingRule(id = "a", ownerId = "o1", categoryId = "c1", context = SaleContext.RETAIL, retailPriceMinor = 400, active = false),
            PricingRule(id = "b", ownerId = "o1", categoryId = "c1", context = SaleContext.WHOLESALE, retailPriceMinor = 0)
        )
        val book = OwnerPriceBookBuilder.build("o1", listOf(cat), rules)
        assertEquals(0, book.appliedCount)
        assertEquals(1, book.disabledCount)
        assertFalse(book.rows.any { it.applied })
    }

    @Test fun legacyRuleHasNoBaseAndSortsLast() {
        val rules = listOf(
            PricingRule(id = "legacy", ownerId = "o1", categoryId = "", retailPriceMinor = 200),
            PricingRule(id = "new", ownerId = "o1", categoryId = "c1", context = SaleContext.RETAIL, retailPriceMinor = 450)
        )
        val rows = OwnerPriceBookBuilder.build("o1", listOf(cat), rules).rows
        assertEquals(listOf("new", "legacy"), rows.map { it.ruleId })
        assertNull(rows.last().baseMinor)
        assertTrue(rows.last().legacy)
    }
}
