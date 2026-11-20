package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.ProfileClassification
import ps.mgtik.hotspot.domain.model.ProfileUsage
import ps.mgtik.hotspot.domain.model.PricingRule
import ps.mgtik.hotspot.domain.model.SaleContext
import ps.mgtik.hotspot.domain.usecase.ProfileDetailRules as R

/** البند 8/23: الاستقلال الكامل بين الاختصاص التشغيلي (تصنيف) والتجاري (فئة). */
class ProfileIndependenceTest {
    private val operationalKeys = setOf(
        "own_settings", "rate_limit_tx_kbps", "rate_limit_rx_kbps", "shared_users", "limit_uptime_seconds", "limit_transfer_bytes"
    )
    private val commercialKeys = setOf("price_minor", "category_id")
    private val routerKeys = setOf("shared_users", "rate_limit_rx_kbps", "rate_limit_tx_kbps")

    private val cls = ProfileClassification(key = "c1", downloadKbps = 5_000, uploadKbps = 1_000, sharedUsers = 1, limitUptimeSeconds = 3_600L)
    private val cat = ProfileCategory(id = "k", nameAr = "الذهبية", currency = "ILS", retailPriceMinor = 2000, wholesalePriceMinor = 1500)
    private fun profile(id: String) = Profile(
        id = id, linkedProfileName = "p$id", displayNameAr = "P$id", classificationKey = "c1", categoryId = "k",
        ownSettings = emptySet(), rateLimitTxKbps = 5_000, rateLimitRxKbps = 1_000, sharedUsers = 1, limitUptimeSeconds = 3_600L, priceMinor = 2000
    )

    private val opInput = R.OperationalInput(true, "10M", "2M", true, "2", true, "5", UptimeUnit.HOURS, true, "1", TransferUnit.GB)

    @Test fun operational_edit_never_carries_price_or_category_fields() {
        val out = R.planOperational(profile("1"), cls, opInput) as R.OperationalOutcome.Ok
        assertTrue(out.plan.fields.isNotEmpty())
        assertTrue(out.plan.fields.keys.all { it in operationalKeys })
        assertTrue(out.plan.fields.keys.none { it in commercialKeys })
    }

    @Test fun commercial_edit_never_touches_operational_or_router_fields() {
        val legacy = profile("1").copy(categoryId = null)
        val out = R.planCommercial(legacy, null, R.CommercialInput("30", "")) as R.CommercialOutcome.Ok
        assertEquals(setOf("price_minor"), out.profileFields.keys)
        assertTrue(out.profileFields.keys.none { it in operationalKeys || it in routerKeys })
        // تعديل فئة: مستند الفئة وحده يتغير، والبروفايل لا يحمل أي حقل
        val c = R.planCommercial(profile("1"), cat, R.CommercialInput("25", "20")) as R.CommercialOutcome.Ok
        assertTrue(c.profileFields.isEmpty()); assertEquals(2500L, c.category!!.retailPriceMinor)
    }

    @Test fun admin_category_change_does_not_change_classification_or_router_fields() {
        val ok = R.planAdmin(profile("1"), R.AdminInput("P1", "", "", "other-cat", "c1"), listOf(cls)) as R.AdminPlan.Ok
        assertEquals(setOf("category_id"), ok.fields.keys)
    }

    @Test fun many_profiles_share_one_classification_and_one_category() {
        val ps = (1..4).map { profile("$it") }
        val eff = ps.map { ClassificationRules.effective(it, cls) }
        assertTrue(eff.all { it.txKbps == 5_000 && it.rxKbps == 1_000 && it.sharedUsers == 1 })
        assertTrue(ps.all { it.categoryId == cat.id })
        val usage = ps.associate { it.id to ProfileUsage(3, 1, 0) }
        assertEquals(16, ProfileUsageRules.sum(ps, usage)!!.users)
    }

    /** مثال التكليف: تجزئة 20، جملة 15، سعر مالك خاص 12 — يُختار بالسياق والمالك لا بأحدث قاعدة عشوائياً، ولا يتأثر بالتصنيف. */
    @Test fun final_price_depends_on_context_and_owner_only() {
        val c = cat.copy(retailPriceMinor = 2000, wholesalePriceMinor = 1500)
        val special = PricingRule(
            id = "r", categoryId = "k", ownerId = "o1", context = SaleContext.RETAIL, retailPriceMinor = 1200, currency = "ILS", active = true
        )
        val retail = PricingPolicy.resolveFor(SaleContext.RETAIL, null, c, 0, emptyList(), "ILS")!!
        val wholesale = PricingPolicy.resolveFor(SaleContext.WHOLESALE, null, c, 0, emptyList(), "ILS")!!
        val owner = PricingPolicy.resolveFor(SaleContext.RETAIL, "o1", c, 0, listOf(special), "ILS")!!
        val otherOwner = PricingPolicy.resolveFor(SaleContext.RETAIL, "o2", c, 0, listOf(special), "ILS")!!
        assertEquals(2000L, retail.unitMinor); assertEquals(PricingPolicy.PriceSource.RETAIL, retail.source)
        assertEquals(1500L, wholesale.unitMinor); assertEquals(PricingPolicy.PriceSource.WHOLESALE, wholesale.source)
        assertEquals(1200L, owner.unitMinor); assertEquals(PricingPolicy.PriceSource.OWNER_SPECIAL, owner.source)
        assertEquals(2000L, otherOwner.unitMinor)
    }

    @Test fun inactive_special_price_is_ignored() {
        val off = PricingRule(id = "r", categoryId = "k", ownerId = "o1", context = SaleContext.RETAIL, retailPriceMinor = 1200, currency = "ILS", active = false)
        assertEquals(2000L, PricingPolicy.resolveFor(SaleContext.RETAIL, "o1", cat, 0, listOf(off), "ILS")!!.unitMinor)
    }
}
