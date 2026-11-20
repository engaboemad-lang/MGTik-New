package ps.mgtik.hotspot.domain.model

/**
 * طريقة اختيار جيل User Manager.
 * - [AUTO]: من إصدار RouterOS (الرئيسي ≥ 7 ← v7، وإلا v6).
 * - [V6]/[V7]: فرض جيل بعينه (لراوتر يُبلغ عن إصدار غير مفهوم، أو لاختبار).
 */
enum class UserManMode { AUTO, V6, V7 }

/**
 * إعداد اتصال التطبيق بـ User Manager على الراوتر (محفوظ مرة واحدة للشبكة، منفصل عن بيانات اتصال الراوتر نفسه).
 *
 * @property customer v6 فقط: العميل (customer) المالك للمستخدمين والباقات؛ الافتراضي admin كما يُنشئه الراوتر.
 * @property radiusAddress عنوان خادم RADIUS كما يراه الهوتسبوت. User Manager على نفس الراوتر ← 127.0.0.1.
 * @property radiusSecret السر المشترك بين `/radius` وراوتر User Manager (يجب أن يتطابقا).
 * @property nasName v7 فقط: اسم سجل الراوتر (NAS) داخل `/user-manager/router`.
 */
data class UserManConfig(
    val mode: UserManMode = UserManMode.AUTO,
    val customer: String = DEFAULT_CUSTOMER,
    val radiusAddress: String = DEFAULT_RADIUS_ADDRESS,
    val radiusSecret: String = "",
    val nasName: String = DEFAULT_NAS_NAME
) {
    companion object {
        const val DEFAULT_CUSTOMER = "admin"
        const val DEFAULT_RADIUS_ADDRESS = "127.0.0.1"
        const val DEFAULT_NAS_NAME = "mgtik-hotspot"
    }
}
