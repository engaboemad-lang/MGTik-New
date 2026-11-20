package ps.mgtik.hotspot.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ps.mgtik.hotspot.data.cloud.source.CloudSettingsDataSource
import ps.mgtik.hotspot.data.cloud.source.CompanyInfoWire
import ps.mgtik.hotspot.data.mapping.FakeDocFields
import ps.mgtik.hotspot.domain.model.NetworkCompanyInfo

/** مطابقة ما قُرئ من الخادم لما أُرسل: أساس «نجاح الحفظ = قابلية الاسترجاع». مهلات Firestore نفسها تحتاج المحاكي (انظر التقرير). */
class CompanyInfoWireTest {

    private val info = NetworkCompanyInfo(
        companyName = "شبكة", networkName = "n", logoUrl = "https://x/l.png", address = "a", phone = "1", whatsapp = "2",
        email = "a@b.co", website = "https://x.io", loginUrl = "http://login", footerNotes = "f", currencySymbol = "₪"
    )

    @Test
    fun stored_document_with_all_fields_matches() {
        assertTrue(CompanyInfoWire.matches(FakeDocFields("company_info", CompanyInfoWire.fields(info)), info))
    }

    @Test
    fun any_differing_or_missing_field_is_a_mismatch() {
        val stored = CompanyInfoWire.fields(info)
        CompanyInfoWire.fields(info).keys.forEach { k ->
            assertFalse("changed $k", CompanyInfoWire.matches(FakeDocFields("d", stored + (k to "other")), info))
            assertFalse("missing $k", CompanyInfoWire.matches(FakeDocFields("d", stored - k), info))
        }
    }

    @Test
    fun wire_keys_are_the_ones_the_reader_uses_and_unknown_server_fields_are_ignored() {
        assertEquals(
            setOf("company_name", "network_name", "address", "phone", "whatsapp", "email", "website", "logo_url", "footer_notes", "currency_symbol", "login_url"),
            CompanyInfoWire.fields(info).keys
        )
        assertTrue(CompanyInfoWire.matches(FakeDocFields("d", CompanyInfoWire.fields(info) + ("extra" to "kept")), info))
    }

    @Test
    fun one_verify_failure_code_is_shared_by_all_settings_saves() {
        assertEquals("ERR_ROUTER_CONFIG_VERIFY_FAILED", CloudSettingsDataSource.ERR_SETTINGS_VERIFY_FAILED)
    }
}
