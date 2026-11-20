package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.PricingRule

class PricingPolicyTest {

    private fun special(owner: String, price: Long, active: Boolean = true, at: String = "1000", id: String = "r-$owner") =
        PricingRule(id = id, cloudProfileId = "P", retailPriceMinor = price, effectiveFrom = at, ownerId = owner, active = active)

    @Test
    fun `agent with valid special price uses it - others use base (spec example 5 vs 4)`() {
        val rules = listOf(special("A", 400))
        val a = PricingPolicy.resolve(500, "A", rules)!!
        assertEquals(400L, a.unitMinor)
        assertEquals(PricingPolicy.Source.SPECIAL, a.source)
        assertEquals("r-A", a.ruleId)
        val b = PricingPolicy.resolve(500, "B", rules)!!
        assertEquals(500L, b.unitMinor)
        assertEquals(PricingPolicy.Source.BASE, b.source)
    }

    @Test
    fun `special price of another agent is never used`() {
        assertEquals(500L, PricingPolicy.resolve(500, "B", listOf(special("A", 100)))!!.unitMinor)
    }

    @Test
    fun `disabled special price falls back to base`() {
        val r = PricingPolicy.resolve(500, "A", listOf(special("A", 400, active = false)))!!
        assertEquals(500L, r.unitMinor)
        assertEquals(PricingPolicy.Source.BASE, r.source)
    }

    @Test
    fun `invalid special price (zero or negative) is ignored`() {
        assertEquals(500L, PricingPolicy.resolve(500, "A", listOf(special("A", 0)))!!.unitMinor)
        assertEquals(500L, PricingPolicy.resolve(500, "A", listOf(special("A", -5)))!!.unitMinor)
    }

    @Test
    fun `no owner means base only`() {
        assertEquals(500L, PricingPolicy.resolve(500, null, listOf(special("A", 100)))!!.unitMinor)
        assertEquals(500L, PricingPolicy.resolve(500, "", listOf(special("A", 100)))!!.unitMinor)
    }

    @Test
    fun `no valid price at all blocks the operation`() {
        assertNull(PricingPolicy.resolve(0, "A", emptyList()))
        assertNull(PricingPolicy.resolve(-1, "A", emptyList()))
        assertNull(PricingPolicy.resolve(0, "A", listOf(special("A", 0))))
    }

    @Test
    fun `legacy general rules without owner are not used - base has a single source`() {
        val legacy = PricingRule(id = "old", cloudProfileId = "P", retailPriceMinor = 999, effectiveFrom = "5")
        assertEquals(500L, PricingPolicy.resolve(500, "A", listOf(legacy))!!.unitMinor)
    }

    @Test
    fun `zero policy is explicit - free price not allowed`() {
        assertFalse(PricingPolicy.isPriceValid(0))
        assertFalse(PricingPolicy.isPriceValid(-1))
        assertTrue(PricingPolicy.isPriceValid(1))
    }

    @Test
    fun `changing base later does not affect a locked value - resolve is pure`() {
        val locked = PricingPolicy.resolve(500, "A", listOf(special("A", 400)))!!.unitMinor
        val afterBaseChange = PricingPolicy.resolve(600, "A", listOf(special("A", 400)))!!.unitMinor
        assertEquals(400L, locked)
        assertEquals(400L, afterBaseChange) // السعر الخاص مستقل عن الأساسي
    }

    @Test
    fun `special price doc id is deterministic so duplicates are impossible`() {
        assertEquals("P__A", PricingPolicy.specialPriceDocId("P", "A"))
    }

    @Test
    fun `category names are compared after whitespace and case normalization`() {
        assertEquals(PricingPolicy.normalizeName("  بطاقات   يومية "), PricingPolicy.normalizeName("بطاقات يومية"))
        assertEquals(PricingPolicy.normalizeName("Daily  Cards"), PricingPolicy.normalizeName("daily cards"))
    }
}
