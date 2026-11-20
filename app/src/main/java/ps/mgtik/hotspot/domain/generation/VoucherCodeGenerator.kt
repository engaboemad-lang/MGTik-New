package ps.mgtik.hotspot.domain.generation

import java.security.SecureRandom
import java.util.Random

/**
 * CodeFormat - نمط أكواد الكروت. الأسماء مطابقة تماماً لـ VoucherCodeFormat في domain.model
 * (يُحوَّل بالاسم) ليبقى هذا المنطق النقي قابلاً للاختبار دون مكتبات تسلسل.
 */
enum class CodeFormat {
    NUMERIC_ONLY, ALPHANUMERIC, UPPERCASE_ONLY, USER_PASS_SAME;

    companion object {
        /** يقبل "numeric_only" و"NUMERIC_ONLY" معاً؛ null إن لم يُعرف. */
        fun parse(name: String): CodeFormat? = values().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * PasswordType - نوع كلمة المرور المولَّدة (مستقل عن نمط اسم المستخدم).
 * SAME_AS_USERNAME خيار صريح فقط؛ لا يُفترض أبداً أن كلمة المرور تطابق الاسم.
 */
enum class PasswordType {
    NUMERIC_ONLY, LETTERS_ONLY, ALPHANUMERIC, MIXED_CASE_ALNUM, SAME_AS_USERNAME;

    companion object {
        fun parse(name: String?): PasswordType? = values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

data class GeneratedCredential(val username: String, val password: String)

/** نتيجة توليد تسلسلي: [lastNumber] أعلى رقم استُهلك فعلاً (يشمل ما تُخطّي لتعارضه مع اسم موجود). */
data class SequentialBatch(val credentials: List<GeneratedCredential>, val lastNumber: Long, val skipped: Int)

/**
 * VoucherCodeGenerator - مولّد أكواد نقي (SecureRandom افتراضياً).
 * - أبجديات تستبعد الأحرف المتشابهة (0/O، 1/I/l).
 * - لا تكرار داخل الدفعة ولا مع أي اسم موجود (مقارنة غير حساسة لحالة الأحرف).
 * - USER_PASS_SAME / PasswordType.SAME_AS_USERNAME: كلمة السر = اسم المستخدم (تسجيل دخول بالكود وحده).
 */
object VoucherCodeGenerator {

    private const val DIGITS = "0123456789"
    private const val LOWER_ALNUM = "abcdefghjkmnpqrstuvwxyz23456789"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER_LETTERS = "abcdefghjkmnpqrstuvwxyz"
    private const val MIXED_ALNUM = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun charset(format: CodeFormat): String = when (format) {
        CodeFormat.NUMERIC_ONLY, CodeFormat.USER_PASS_SAME -> DIGITS
        CodeFormat.ALPHANUMERIC -> LOWER_ALNUM
        CodeFormat.UPPERCASE_ONLY -> UPPER
    }

    /** أبجدية كلمة المرور؛ SAME_AS_USERNAME لا أبجدية لها (تُنسخ من الاسم). */
    fun passwordCharset(type: PasswordType): String = when (type) {
        PasswordType.NUMERIC_ONLY -> DIGITS
        PasswordType.LETTERS_ONLY -> LOWER_LETTERS
        PasswordType.ALPHANUMERIC -> LOWER_ALNUM
        PasswordType.MIXED_CASE_ALNUM -> MIXED_ALNUM
        PasswordType.SAME_AS_USERNAME -> ""
    }

    /** عدد الأكواد الممكنة (Double كي لا يفيض عند الأطوال الكبيرة). */
    fun keyspace(format: CodeFormat, length: Int): Double = Math.pow(charset(format).length.toDouble(), length.toDouble())

    fun passwordLength(codeLength: Int): Int = codeLength.coerceIn(4, 8)

    /**
     * @param taken أسماء مستخدمين محجوزة (يُفضَّل أن تكون بحروف صغيرة).
     * @param passwordType null = السلوك القديم (كلمة السر من نفس أبجدية الاسم وبطول مشتق، أو مطابقة له في USER_PASS_SAME).
     * @param passwordLength يُستعمل مع [passwordType] فقط.
     * @throws IllegalStateException عند تعذّر إيجاد أسماء فريدة (فضاء ضيّق/ممتلئ).
     */
    fun generate(
        count: Int,
        format: CodeFormat,
        prefix: String,
        codeLength: Int,
        taken: Set<String> = emptySet(),
        random: Random = SecureRandom(),
        passwordType: PasswordType? = null,
        passwordLength: Int = 0
    ): List<GeneratedCredential> {
        require(count > 0) { "count must be positive" }
        val chars = charset(format)
        val used = HashSet<String>(taken.size + count)
        taken.forEach { used.add(it.lowercase()) }

        val result = ArrayList<GeneratedCredential>(count)
        var attempts = 0
        val maxAttempts = count.toLong() * 50 + 1000

        while (result.size < count) {
            check(attempts++ < maxAttempts) { "ERR_GEN_KEYSPACE_EXHAUSTED" }
            val username = prefix + randomString(chars, codeLength, random)
            if (!used.add(username.lowercase())) continue
            result.add(GeneratedCredential(username, passwordFor(username, format, passwordType, passwordLength, codeLength, random)))
        }
        return result
    }

    /**
     * توليد تسلسلي: الأرقام [startNumber] ثم التالية، كل رقم يُصفَّر يساراً إلى [codeLength] خانة بعد [prefix].
     * رقم اسمه محجوز في [taken] يُتخطّى (لا يُعاد استعماله ولا يُفشل الدفعة)، ويُحتسب في [SequentialBatch.skipped]
     * كي يحجز المستدعي أرقاماً بديلة. يتوقف عند [count] اسماً أو عند استهلاك [maxNumbers] رقماً، أيهما أولاً.
     *
     * @throws IllegalStateException إن تجاوز الرقم سعة [codeLength] خانة (فضاء الأسماء ممتلئ).
     */
    fun generateSequential(
        count: Int,
        startNumber: Long,
        maxNumbers: Int,
        prefix: String,
        codeLength: Int,
        taken: Set<String>,
        random: Random = SecureRandom(),
        passwordType: PasswordType? = null,
        passwordLength: Int = 0
    ): SequentialBatch {
        require(count > 0) { "count must be positive" }
        require(startNumber >= 1) { "sequence starts at 1" }
        val capacity = Math.pow(10.0, codeLength.toDouble()).toLong() - 1
        val used = HashSet<String>(taken.size)
        taken.forEach { used.add(it.lowercase()) }

        val result = ArrayList<GeneratedCredential>(count)
        var number = startNumber
        var skipped = 0
        val end = startNumber + maxNumbers - 1
        while (result.size < count && number <= end) {
            check(number <= capacity) { "ERR_GEN_KEYSPACE_EXHAUSTED" }
            val username = prefix + number.toString().padStart(codeLength, '0')
            if (used.add(username.lowercase())) {
                result.add(GeneratedCredential(username, passwordFor(username, CodeFormat.NUMERIC_ONLY, passwordType, passwordLength, codeLength, random)))
            } else {
                skipped++
            }
            number++
        }
        return SequentialBatch(result, lastNumber = number - 1, skipped = skipped)
    }

    private fun passwordFor(
        username: String,
        format: CodeFormat,
        type: PasswordType?,
        passwordLength: Int,
        codeLength: Int,
        random: Random
    ): String = when {
        type == null ->
            if (format == CodeFormat.USER_PASS_SAME) username
            else randomString(charset(format), passwordLength(codeLength), random)
        type == PasswordType.SAME_AS_USERNAME -> username
        else -> randomString(passwordCharset(type), passwordLength, random)
    }

    private fun randomString(chars: String, length: Int, random: Random): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(chars[random.nextInt(chars.length)]) }
        return sb.toString()
    }
}
