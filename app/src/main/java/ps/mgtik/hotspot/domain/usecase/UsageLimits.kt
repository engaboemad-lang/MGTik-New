package ps.mgtik.hotspot.domain.usecase

import java.math.BigDecimal
import java.math.RoundingMode

/** وحدة مدخلات المدة في الواجهة الإدارية. */
enum class UptimeUnit(val seconds: Long) { MINUTES(60L), HOURS(3_600L), DAYS(86_400L) }

/** وحدة مدخلات حجم البيانات (ثنائية: 1 GB = 1024 MB = 1024³ بايت كما يعرضها RouterOS). */
enum class TransferUnit(val bytes: Long) { MB(1_048_576L), GB(1_073_741_824L) }

/**
 * UsageLimits - تحقق وتحويل نقي (بلا Android) لمدخلات حدود الاستخدام في الباقة (المدة بالثواني، والحجم بالبايت).
 * الحدود ملك الباقة/التصنيف التشغيلي وتُكتب على الراوتر كـ Limitation في User Manager (uptime-limit وtransfer-limit)، لا على المستخدم.
 *
 * قاعدة القيمة: null = بلا حد. الصفر مرفوض عمداً: RouterOS يفسّر 0 بأنه «بلا حد»، فقبوله كقيمة يحوّل نية «لا استخدام»
 * إلى «استخدام مفتوح».
 */
object UsageLimits {

    /** RouterOS يخزّن limit-uptime كعدد صحيح 32 بت بالثواني. */
    const val MAX_UPTIME_SECONDS = 4_294_967_295L

    /** سقف عملي: 1 PiB (RouterOS يخزّن limit-bytes-total بـ 64 بت). */
    const val MAX_TRANSFER_BYTES = 1L shl 50

    fun isUptimeValid(seconds: Long?): Boolean = seconds == null || seconds in 1..MAX_UPTIME_SECONDS

    fun isTransferValid(bytes: Long?): Boolean = bytes == null || bytes in 1..MAX_TRANSFER_BYTES

    /** نتيجة تحليل مدخل اختياري: غير محدد، أو قيمة صالحة، أو خطأ (نص غير رقمي/صفر/سالب/فوق السقف). */
    sealed class Parsed {
        data object Unspecified : Parsed()
        data class Value(val amount: Long) : Parsed()
        data object Invalid : Parsed()
    }

    /** حقل فارغ = غير محدد؛ غير ذلك رقم موجب (يقبل كسوراً عشرية للحجم ولا يقبلها للمدة). */
    fun parseUptime(raw: String, unit: UptimeUnit): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed.Unspecified
        val amount = text.toLongOrNull() ?: return Parsed.Invalid
        if (amount <= 0) return Parsed.Invalid
        val seconds = try { Math.multiplyExact(amount, unit.seconds) } catch (e: ArithmeticException) { return Parsed.Invalid }
        return if (seconds <= MAX_UPTIME_SECONDS) Parsed.Value(seconds) else Parsed.Invalid
    }

    fun parseTransfer(raw: String, unit: TransferUnit): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed.Unspecified
        val amount = text.toBigDecimalOrNull() ?: return Parsed.Invalid
        if (amount.signum() <= 0) return Parsed.Invalid
        val bytes = amount.multiply(BigDecimal(unit.bytes)).setScale(0, RoundingMode.DOWN)
        if (bytes.signum() <= 0 || bytes > BigDecimal(MAX_TRANSFER_BYTES)) return Parsed.Invalid
        return Parsed.Value(bytes.toLong())
    }
}
