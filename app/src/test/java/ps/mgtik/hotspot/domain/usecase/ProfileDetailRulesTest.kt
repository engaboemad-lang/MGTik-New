package ps.mgtik.hotspot.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.ProfileClassification
import ps.mgtik.hotspot.domain.usecase.ProfileDetailRules as R

class ProfileDetailRulesTest {

    private val cls = ProfileClassification(
        key = "c1", downloadKbps = 8_000, uploadKbps = 2_000, sharedUsers = 3,
        limitUptimeSeconds = 3_600L, limitTransferBytes = 1_073_741_824L
    )
    private val own = Profile(
        id = "p", linkedProfileName = "gold", displayNameAr = "ذهبية", classificationKey = "c1",
        ownSettings = OperationalSetting.entries.toSet(),
        rateLimitTxKbps = 5_000, rateLimitRxKbps = 1_000, sharedUsers = 2,
        limitUptimeSeconds = 7_200L, limitTransferBytes = null
    )

    private fun input(
        ownSpeed: Boolean = true, dl: String = "5M", ul: String = "1M",
        ownShared: Boolean = true, shared: String = "2",
        ownUptime: Boolean = true, uptime: String? = "2", uptimeUnit: UptimeUnit = UptimeUnit.HOURS,
        ownTransfer: Boolean = true, transfer: String? = "", transferUnit: TransferUnit = TransferUnit.GB
    ) = R.OperationalInput(ownSpeed, dl, ul, ownShared, shared, ownUptime, uptime, uptimeUnit, ownTransfer, transfer, transferUnit)

    private fun plan(p: Profile, i: R.OperationalInput, c: ProfileClassification? = cls) =
        (R.planOperational(p, c, i) as R.OperationalOutcome.Ok).plan

    @Test fun unchanged_input_yields_empty_plan() {
        val pl = plan(own, input())
        assertTrue(pl.isEmpty); assertTrue(pl.changed.isEmpty())
    }

    @Test fun speed_change_touches_router_profile_only() {
        val pl = plan(own, input(dl = "10M"))
        assertEquals(10_000, pl.fields["rate_limit_tx_kbps"]); assertEquals(1_000, pl.fields["rate_limit_rx_kbps"])
        assertTrue(pl.touchesRouterProfile); assertFalse(pl.touchesUserLimits)
    }

    @Test fun limit_change_also_pushes_to_router_limitation() {
        val pl = plan(own, input(uptime = "3"))
        assertEquals(10_800L, pl.fields["limit_uptime_seconds"])
        assertTrue(pl.touchesRouterProfile); assertTrue(pl.touchesUserLimits)
    }

    @Test fun empty_limit_text_clears_to_null_not_zero() {
        val pl = plan(own, input(uptime = ""))
        assertTrue(pl.fields.containsKey("limit_uptime_seconds")); assertNull(pl.fields["limit_uptime_seconds"])
    }

    @Test fun null_text_keeps_unrepresentable_value() {
        val p = own.copy(limitUptimeSeconds = 90L)
        val pl = plan(p, input(uptime = null))
        assertFalse(pl.fields.containsKey("limit_uptime_seconds"))
    }

    @Test fun invalid_values_are_rejected_with_field_codes() {
        val o = R.planOperational(own, cls, input(dl = "abc", shared = "0", uptime = "0", transfer = "-1")) as R.OperationalOutcome.Invalid
        assertEquals(R.ERR_SPEED_INVALID, o.errors[R.Field.SPEED])
        assertEquals(R.ERR_SHARED_USERS_INVALID, o.errors[R.Field.SHARED_USERS])
        assertEquals(R.ERR_UPTIME_INVALID, o.errors[R.Field.UPTIME])
        assertEquals(R.ERR_TRANSFER_INVALID, o.errors[R.Field.TRANSFER])
    }

    @Test fun two_way_speed_text_is_rejected() {
        assertTrue(R.planOperational(own, cls, input(dl = "5M/2M")) is R.OperationalOutcome.Invalid)
    }

    @Test fun switching_to_inherited_changes_effective_values_and_sets_own_settings() {
        val pl = plan(own, input(ownShared = false, shared = "ignored"))
        assertEquals("speed,limit_uptime,limit_transfer", pl.fields["own_settings"])
        assertTrue(OperationalSetting.SHARED_USERS in pl.changed)   // 2 -> 3 موروثة
        assertFalse(pl.fields.containsKey("shared_users"))          // نصوص الإعداد الموروث لا تُقرأ
    }

    @Test fun setting_not_defined_by_classification_cannot_be_inherited() {
        val c = cls.copy(sharedUsers = null)
        val pl = plan(own, input(ownShared = false, shared = "4"), c)
        assertEquals(4, pl.fields["shared_users"])
    }

    @Test fun legacy_profile_is_not_converted_unless_source_changes() {
        val legacy = own.copy(ownSettings = null)
        assertFalse(plan(legacy, input()).fields.containsKey("own_settings"))
        assertTrue(plan(legacy, input(ownShared = false)).fields.containsKey("own_settings"))
    }

    // ---- إداري
    @Test fun admin_requires_name_and_ignores_blank_english_name() {
        val base = R.AdminInput("  ", "", "", own.categoryId, own.classificationKey)
        assertTrue(R.planAdmin(own, base, listOf(cls)) is R.AdminPlan.Invalid)
        val ok = R.planAdmin(own, base.copy(nameAr = "ذهبية"), listOf(cls)) as R.AdminPlan.Ok
        assertTrue(ok.isEmpty)
    }

    @Test fun admin_reclassification_sets_own_settings() {
        val ok = R.planAdmin(own, R.AdminInput("ذهبية", "", "", null, null), listOf(cls)) as R.AdminPlan.Ok
        assertTrue(ok.fields.containsKey("classification_key")); assertNull(ok.fields["classification_key"])
        assertTrue(ok.fields.containsKey("own_settings"))
    }

    // ---- تجاري
    private val cat = ProfileCategory(id = "k", nameAr = "فئة", currency = "ILS", retailPriceMinor = 1000, wholesalePriceMinor = 800)

    @Test fun commercial_category_prices_update_and_empty_means_unset() {
        val o = R.planCommercial(own, cat, R.CommercialInput("12.5", "")) as R.CommercialOutcome.Ok
        assertEquals(R.PriceTarget.CATEGORY, o.target)
        assertEquals(1250L, o.category!!.retailPriceMinor); assertNull(o.category!!.wholesalePriceMinor)
        assertEquals("ILS", o.category!!.currency) // العملة لا تتغير
    }

    @Test fun commercial_no_change_detected() {
        assertTrue((R.planCommercial(own, cat, R.CommercialInput("10", "8")) as R.CommercialOutcome.Ok).noChange)
    }

    @Test fun commercial_invalid_price_rejected() {
        val o = R.planCommercial(own, cat, R.CommercialInput("x", "-3")) as R.CommercialOutcome.Invalid
        assertEquals(R.ERR_PRICE_INVALID, o.errors[R.Field.RETAIL]); assertEquals(R.ERR_PRICE_INVALID, o.errors[R.Field.WHOLESALE])
    }

    @Test fun categoryless_profile_edits_own_price_and_requires_it() {
        val o = R.planCommercial(own.copy(priceMinor = 500), null, R.CommercialInput("7", "")) as R.CommercialOutcome.Ok
        assertEquals(R.PriceTarget.PROFILE, o.target); assertEquals(700L, o.profileFields["price_minor"])
        assertTrue(R.planCommercial(own, null, R.CommercialInput("", "")) is R.CommercialOutcome.Invalid)
    }

    // ── الصلاحية وغير المحدود وسياسة البداية (تعديلها بعد الإنشاء) ──────────────────────

    private fun ops(
        shared: Boolean? = null, vUnlimited: Boolean? = null, vText: String? = null, vUnit: Int = 24, assigned: Boolean? = null,
        sharedText: String = "2", ownShared: Boolean = true
    ) = R.OperationalInput(
        true, "5M", "1M", ownShared, sharedText, true, "2", UptimeUnit.HOURS, true, "", TransferUnit.GB,
        sharedUnlimited = shared, validityUnlimited = vUnlimited, validityText = vText, validityUnitHours = vUnit, startAssigned = assigned
    )

    @Test fun untouched_validity_and_unlimited_leave_the_profile_alone() {
        val p = own.copy(validityHours = 48, validityStart = "assigned")
        val pl = plan(p, ops())
        assertTrue(pl.isEmpty); assertFalse(pl.validityChanged)
    }

    @Test fun validity_text_with_unit_is_converted_to_hours_and_pushed_to_the_router_profile() {
        val pl = plan(own, ops(vText = "3", vUnit = 24))
        assertEquals(72, pl.fields["validity_hours"])
        assertTrue(pl.validityChanged); assertTrue(pl.touchesRouterProfile); assertFalse(pl.touchesUserLimits)
        assertEquals(720 * 2, plan(own, ops(vText = "2", vUnit = 720)).fields["validity_hours"])
    }

    @Test fun empty_validity_text_means_unspecified_zero_hours() {
        assertEquals(0, plan(own.copy(validityHours = 24), ops(vText = "")).fields["validity_hours"])
    }

    @Test fun unlimited_validity_wins_over_text_and_zeroes_the_stored_hours() {
        val pl = plan(own.copy(validityHours = 24), ops(vUnlimited = true, vText = "5"))
        assertEquals(true, pl.fields["validity_unlimited"]); assertEquals(0, pl.fields["validity_hours"])
        // والعودة إلى محدودة بلا نص تُبقي الساعات كما هي (صفر) دون اختراع قيمة
        val back = plan(own.copy(validityUnlimited = true), ops(vUnlimited = false))
        assertEquals(false, back.fields["validity_unlimited"]); assertFalse(back.fields.containsKey("validity_hours"))
    }

    @Test fun invalid_validity_is_rejected() {
        listOf("0", "-1", "abc", "1.5", "99999999999").forEach {
            val o = R.planOperational(own, cls, ops(vText = it)) as R.OperationalOutcome.Invalid
            assertEquals(it, R.ERR_VALIDITY_INVALID, o.errors[R.Field.VALIDITY])
        }
        // لا يتجاوز نطاق Int بعد الضرب بالوحدة
        assertTrue(R.planOperational(own, cls, ops(vText = "4000000", vUnit = 720)) is R.OperationalOutcome.Invalid)
    }

    @Test fun start_policy_changes_only_when_sent_and_differs() {
        assertEquals("assigned", plan(own, ops(assigned = true)).fields["validity_start"])
        assertTrue(plan(own, ops(assigned = false)).isEmpty) // الافتراضي first_auth
        val pl = plan(own.copy(validityStart = "assigned"), ops(assigned = false))
        assertEquals("first_auth", pl.fields["validity_start"]); assertTrue(pl.validityChanged)
    }

    @Test fun shared_unlimited_is_explicit_own_and_skips_number_validation() {
        // حتى مع نص عدد غير صالح وتفعيل الوراثة: غير المحدود قرار الباقة نفسها
        val pl = plan(own.copy(ownSettings = emptySet()), ops(shared = true, sharedText = "abc", ownShared = false))
        assertEquals(true, pl.fields["shared_unlimited"])
        assertTrue(OperationalSetting.SHARED_USERS in pl.changed); assertTrue(pl.touchesRouterProfile)
        assertTrue((pl.fields["own_settings"] as String).contains("shared", ignoreCase = true) || (pl.fields["own_settings"] as String).contains("SHARED", ignoreCase = true))
    }

    @Test fun switching_back_from_unlimited_requires_a_valid_number() {
        val p = own.copy(sharedUnlimited = true)
        val bad = R.planOperational(p, cls, ops(shared = false, sharedText = "0")) as R.OperationalOutcome.Invalid
        assertEquals(R.ERR_SHARED_USERS_INVALID, bad.errors[R.Field.SHARED_USERS])
        val ok = plan(p, ops(shared = false, sharedText = "4"))
        assertEquals(false, ok.fields["shared_unlimited"]); assertEquals(4, ok.fields["shared_users"])
        assertTrue(OperationalSetting.SHARED_USERS in ok.changed)
    }
}
