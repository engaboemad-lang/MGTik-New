package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ps.mgtik.hotspot.domain.model.PricingRule
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.SaleContext
import ps.mgtik.hotspot.domain.usecase.PricingPolicy.PriceSource

class PricingContextTest {
    private val cat = ProfileCategory(id = "G", nameAr = "ذهبية", currency = "₪", retailPriceMinor = 2_000, wholesalePriceMinor = 1_500)

    private fun rule(owner: String, price: Long, ctx: SaleContext = SaleContext.WHOLESALE, active: Boolean = true,
                     at: String = "1", id: String = "G__$owner", categoryId: String = "G", currency: String = "₪") =
        PricingRule(id = id, categoryId = categoryId, retailPriceMinor = price, ownerId = owner, active = active,
            effectiveFrom = at, context = ctx, currency = currency)

    private fun q(ctx: SaleContext, owner: String?, rules: List<PricingRule> = emptyList(), c: ProfileCategory? = cat, legacy: Long = 0) =
        PricingPolicy.resolveFor(ctx, owner, c, legacy, rules, "₪")

    @Test
    fun `spec example - owner 12, wholesale 15, retail 20`() {
        val rules = listOf(rule("O", 1_200))
        val owner = q(SaleContext.WHOLESALE, "O", rules)!!
        assertEquals(1_200L, owner.unitMinor); assertEquals(PriceSource.OWNER_SPECIAL, owner.source)
        assertEquals(1_500L, q(SaleContext.WHOLESALE, "other", rules)!!.unitMinor)
        assertEquals(2_000L, q(SaleContext.RETAIL, null, rules)!!.unitMinor)
        assertEquals(PriceSource.RETAIL, q(SaleContext.RETAIL, null, rules)!!.source)
    }

    @Test
    fun `special price applies only in its own context`() {
        val rules = listOf(rule("O", 1_200, SaleContext.WHOLESALE))
        assertEquals(2_000L, q(SaleContext.RETAIL, "O", rules)!!.unitMinor)
    }

    @Test
    fun `inactive, invalid or wrong currency special price is rejected`() {
        assertEquals(1_500L, q(SaleContext.WHOLESALE, "O", listOf(rule("O", 1_200, active = false)))!!.unitMinor)
        assertEquals(1_500L, q(SaleContext.WHOLESALE, "O", listOf(rule("O", 0)))!!.unitMinor)
        assertEquals(1_500L, q(SaleContext.WHOLESALE, "O", listOf(rule("O", 1_200, currency = "$")))!!.unitMinor)
    }

    @Test
    fun `conflicting rules resolve deterministically - newest then id`() {
        val a = rule("O", 1_100, at = "5", id = "x1")
        val b = rule("O", 1_000, at = "9", id = "x0")
        assertEquals(1_000L, q(SaleContext.WHOLESALE, "O", listOf(a, b))!!.unitMinor)
        assertEquals(1_000L, q(SaleContext.WHOLESALE, "O", listOf(b, a))!!.unitMinor)
        val t1 = rule("O", 1_100, at = "5", id = "b"); val t2 = rule("O", 1_000, at = "5", id = "a")
        assertEquals(1_000L, q(SaleContext.WHOLESALE, "O", listOf(t1, t2))!!.unitMinor)
    }

    @Test
    fun `rule of another category is never used`() {
        assertEquals(1_500L, q(SaleContext.WHOLESALE, "O", listOf(rule("O", 1, categoryId = "Z")))!!.unitMinor)
    }

    @Test
    fun `missing price for the context blocks - no copy from the other price`() {
        assertNull(q(SaleContext.WHOLESALE, null, c = cat.copy(wholesalePriceMinor = null)))
        assertNull(q(SaleContext.RETAIL, null, c = cat.copy(retailPriceMinor = null)))
    }

    @Test
    fun `legacy category without commercial prices falls back to profile price`() {
        val legacyCat = ProfileCategory(id = "G", nameAr = "قديمة")
        val r = q(SaleContext.RETAIL, null, c = legacyCat, legacy = 700)!!
        assertEquals(700L, r.unitMinor); assertEquals(PriceSource.LEGACY_PROFILE, r.source); assertEquals("₪", r.currency)
        assertNull(q(SaleContext.RETAIL, null, c = legacyCat, legacy = 0))
        assertNull(q(SaleContext.RETAIL, null, c = null, legacy = 0))
    }

    @Test
    fun `legacy profile level owner price is honored only without a category rule`() {
        val legacyRule = PricingRule(id = "P__O", cloudProfileId = "P", retailPriceMinor = 900, ownerId = "O", effectiveFrom = "1")
        assertEquals(PriceSource.LEGACY_OWNER_SPECIAL, q(SaleContext.WHOLESALE, "O", listOf(legacyRule))!!.source)
        val both = q(SaleContext.WHOLESALE, "O", listOf(legacyRule, rule("O", 1_200)))!!
        assertEquals(PriceSource.OWNER_SPECIAL, both.source)
    }

    @Test
    fun `snapshot carries price, currency, source, type, category, profile and owner`() {
        val s = q(SaleContext.WHOLESALE, "O", listOf(rule("O", 1_200)))!!.snapshotFields("P1")
        assertEquals(1_200L, s["price_snapshot_minor"]); assertEquals("₪", s["price_currency"])
        assertEquals("owner_special", s["price_source"]); assertEquals("wholesale", s["pricing_type"])
        assertEquals("G", s["price_category_id"]); assertEquals("P1", s["price_profile_id"]); assertEquals("O", s["price_owner_id"])
    }

    @Test
    fun `changing category price later does not alter an already taken quote`() {
        val before = q(SaleContext.WHOLESALE, null)!!
        q(SaleContext.WHOLESALE, null, c = cat.copy(wholesalePriceMinor = 9_999))
        assertEquals(1_500L, before.unitMinor)
    }
}
