package ps.mgtik.hotspot.domain.usecase

/**
 * OwnerInputValidator - قواعد نقية (بلا Android) لمدخلات المالك، ومنها تطبيع رقم الهاتف لكشف التكرار.
 */
object OwnerInputValidator {

    /** رمز خطأ رفض إنشاء مالك برقم هاتف مستخدم. */
    const val ERR_PHONE_DUPLICATE = "ERR_OWNER_PHONE_DUPLICATE"

    private const val MIN_NAME_LENGTH = 3
    private const val MIN_PHONE_DIGITS = 7

    /** رموز الدول التي يُكتب بها الرقم نفسه محلياً بصفر بادئ (فلسطين 970 / 972). */
    private val COUNTRY_PREFIXES = listOf("970", "972")

    /**
     * يطبّع الرقم للمقارنة فقط (لا لعرضه): أرقام فقط، بلا 00 الدولية، ورمز الدولة يُستبدل بصفر بادئ.
     * فتتطابق: 0599123456 / 0599-123-456 / +970599123456 / 00970599123456 / +972 59 912 3456.
     */
    fun normalizePhone(raw: String): String {
        var digits = raw.filter { it.isDigit() }
        if (digits.startsWith("00")) digits = digits.removePrefix("00") // بادئة الاتصال الدولي
        val prefix = COUNTRY_PREFIXES.firstOrNull { digits.startsWith(it) && digits.length > it.length + 6 }
        if (prefix != null) {
            val rest = digits.removePrefix(prefix)
            digits = if (rest.startsWith("0")) rest else "0$rest"
        }
        return digits
    }

    /**
     * أرقام الرقم بصيغة دولية بلا + (لروابط واتساب wa.me). الافتراض: رقم محلي بصفر بادئ ⇒ رمز 970؛
     * رقم بـ 970/972 أو 00 دولية يُترك. يُرجع "" إن لم يصلح.
     */
    fun toInternationalDigits(raw: String, defaultCountry: String = "970"): String {
        var digits = raw.filter { it.isDigit() }
        if (digits.startsWith("00")) digits = digits.removePrefix("00")
        if (digits.length < MIN_PHONE_DIGITS) return ""
        val hasCountry = COUNTRY_PREFIXES.any { digits.startsWith(it) && digits.length > it.length + 6 }
        return when {
            hasCountry -> digits
            digits.startsWith("0") -> defaultCountry + digits.removePrefix("0")
            else -> digits
        }
    }

    fun isNameValid(name: String): Boolean = name.trim().length >= MIN_NAME_LENGTH

    fun isPhoneValid(phone: String): Boolean = normalizePhone(phone).length >= MIN_PHONE_DIGITS

    /** هل رقم الهاتف مستخدم لمالك آخر؟ (excludeOwnerId يستثني المالك نفسه عند التعديل). */
    fun isPhoneTaken(phone: String, existing: Collection<Pair<String, String>>, excludeOwnerId: String? = null): Boolean {
        val target = normalizePhone(phone)
        if (target.isEmpty()) return false
        return existing.any { (ownerId, otherPhone) -> ownerId != excludeOwnerId && normalizePhone(otherPhone) == target }
    }

    /**
     * مبلغ موجب بصيغة عشرية ("12.5") إلى وحدات صغرى (1250) بلا خطأ فاصلة عائمة
     * (19.99 * 100 بالـ Double = 1998.99… كان يُقصّ إلى 1998). null إن كان فارغاً/غير صالح/صفراً/سالباً/أكثر من خانتين عشريتين.
     */
    fun parseAmountMinor(raw: String): Long? {
        val value = raw.trim().toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0 || value.scale() > 2 || value > MAX_AMOUNT) return null
        return value.movePointRight(2).toLong()
    }

    /** المبلغ بالوحدات الصغرى كنص عشري إنجليزي قابل للإدخال في الحقل ("1250" → "12.50"). */
    fun minorToInput(minor: Long): String = String.format(java.util.Locale.ENGLISH, "%d.%02d", minor / 100, minor % 100)

    private val MAX_AMOUNT = java.math.BigDecimal("10000000")
}
