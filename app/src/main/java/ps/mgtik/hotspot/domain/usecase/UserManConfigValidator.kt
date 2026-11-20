package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.model.UserManConfig
import ps.mgtik.hotspot.domain.model.UserManMode
import java.security.SecureRandom

/** حقول إعداد User Manager القابلة للتحقق (مفاتيح أخطاء الشاشة). */
enum class UserManField { CUSTOMER, RADIUS_ADDRESS, RADIUS_SECRET, NAS_NAME }

/** سبب رفض حقل؛ الشاشة تترجمه إلى نص. */
enum class UserManFieldError { REQUIRED, INVALID, TOO_SHORT, TOO_LONG }

/**
 * UserManConfigValidator - تحقق نقي (بلا Android) من إعداد User Manager قبل الحفظ أو التهيئة.
 * الحقول التي لا تخص الجيل المختار لا تُفحص (العميل لـ v6 فقط، واسم NAS لـ v7 فقط).
 */
object UserManConfigValidator {

    private val IPV4 = Regex(
        "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$"
    )
    private val HOSTNAME = Regex(
        "^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"
    )
    /** أسماء آمنة داخل أوامر RouterOS: بلا مسافات أو علامات اقتباس أو رموز تحكم. */
    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+$")

    const val MIN_SECRET = 6
    const val MAX_SECRET = 64
    const val MAX_NAME = 32

    /**
     * @param requireSecret الحفظ وحده لا يشترط السر (مثلاً تبديل الجيل فقط)؛ التهيئة تشترطه. السر غير الفارغ يُفحص دائماً.
     */
    fun validate(config: UserManConfig, requireSecret: Boolean = true): Map<UserManField, UserManFieldError> {
        val errors = linkedMapOf<UserManField, UserManFieldError>()

        if (config.mode != UserManMode.V7) nameError(config.customer)?.let { errors[UserManField.CUSTOMER] = it }
        if (config.mode != UserManMode.V6) nameError(config.nasName)?.let { errors[UserManField.NAS_NAME] = it }

        val addr = config.radiusAddress.trim()
        when {
            addr.isEmpty() -> errors[UserManField.RADIUS_ADDRESS] = UserManFieldError.REQUIRED
            !(IPV4.matches(addr) || (!addr.all { it.isDigit() || it == '.' } && HOSTNAME.matches(addr))) ->
                errors[UserManField.RADIUS_ADDRESS] = UserManFieldError.INVALID
        }

        val secret = config.radiusSecret
        when {
            secret.isEmpty() && !requireSecret -> Unit
            secret.isBlank() -> errors[UserManField.RADIUS_SECRET] = UserManFieldError.REQUIRED
            secret.any { it.isWhitespace() || it == '"' || it == '\\' } -> errors[UserManField.RADIUS_SECRET] = UserManFieldError.INVALID
            secret.length < MIN_SECRET -> errors[UserManField.RADIUS_SECRET] = UserManFieldError.TOO_SHORT
            secret.length > MAX_SECRET -> errors[UserManField.RADIUS_SECRET] = UserManFieldError.TOO_LONG
        }
        return errors
    }

    /** الإعداد بعد تقليم المسافات (السر لا يُقلَّم: المسافة فيه مرفوضة أصلاً). */
    fun normalize(config: UserManConfig): UserManConfig = config.copy(
        customer = config.customer.trim(),
        radiusAddress = config.radiusAddress.trim(),
        nasName = config.nasName.trim()
    )

    private fun nameError(raw: String): UserManFieldError? {
        val v = raw.trim()
        return when {
            v.isEmpty() -> UserManFieldError.REQUIRED
            v.length > MAX_NAME -> UserManFieldError.TOO_LONG
            !SAFE_NAME.matches(v) -> UserManFieldError.INVALID
            else -> null
        }
    }

    private const val SECRET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /** سر عشوائي قوي (بلا حروف ملتبسة) يصلح سراً مشتركاً بين `/radius` وUser Manager. */
    fun generateSecret(length: Int = 16, random: java.util.Random = SecureRandom()): String =
        buildString { repeat(length.coerceIn(MIN_SECRET, MAX_SECRET)) { append(SECRET_ALPHABET[random.nextInt(SECRET_ALPHABET.length)]) } }
}
