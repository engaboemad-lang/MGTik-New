package ps.mgtik.hotspot.data.cloud.source

import ps.mgtik.hotspot.data.mapping.DocFields
import ps.mgtik.hotspot.domain.model.NetworkCompanyInfo

/**
 * ترميز بيانات الشركة في Firestore ومطابقتها بعد الحفظ (نقي: يعمل على [DocFields] فيُختبر دون محاكي).
 * مفاتيح الحقول هنا هي نفسها التي يقرؤها [CloudSettingsDataSource.getCompanyInfo].
 */
object CompanyInfoWire {
    fun fields(info: NetworkCompanyInfo): Map<String, String> = mapOf(
        "company_name" to info.companyName,
        "network_name" to info.networkName,
        "address" to info.address,
        "phone" to info.phone,
        "whatsapp" to info.whatsapp,
        "email" to info.email,
        "website" to info.website,
        "logo_url" to info.logoUrl,
        "footer_notes" to info.footerNotes,
        "currency_symbol" to info.currencySymbol,
        "login_url" to info.loginUrl
    )

    /** كل حقل مخزَّن مطابق لما أُرسل؛ الحقل الغائب في الخادم يُعدّ غير مطابق (لا يُقرأ فراغاً). */
    fun matches(stored: DocFields, info: NetworkCompanyInfo): Boolean =
        fields(info).all { (key, value) -> stored.string(key) == value }
}
