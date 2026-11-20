package ps.mgtik.hotspot.domain.generation

import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileCategory
import ps.mgtik.hotspot.domain.model.ProfileStatus
import ps.mgtik.hotspot.domain.model.SystemClass

/** وضع التوليد: تسلسلي (عدّاد دائم لكل بروفايل) أو عشوائي. */
enum class GenerationMode {
    SEQUENTIAL, RANDOM;

    companion object {
        fun parse(name: String?): GenerationMode? = values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}

/**
 * GenerationRules - قواعد التحقق النقية (بلا Android) لطلب توليد الكروت.
 * مصدر واحد لحدود الكمية/الطول/البادئة وفضاء المفاتيح، تستخدمه الشاشة (S4) وحالة الاستخدام والساغا.
 */
object GenerationRules {
    const val MAX_COUNT = 5000
    const val MIN_CODE_LENGTH = 4
    const val MAX_CODE_LENGTH = 12
    const val MAX_PREFIX_LENGTH = 8
    const val MIN_PASSWORD_LENGTH = 4
    const val MAX_PASSWORD_LENGTH = 16
    /** تعليق المختص يُكتب كما هو في حقل comment لكل حساب على الراوتر (بلا وسم)؛ سقف معقول للطول. */
    const val MAX_COMMENT_LENGTH = 100

    // رموز الأخطاء المعيارية (تُترجم في الواجهة)
    const val ERR_COUNT_INVALID = "ERR_GEN_COUNT_INVALID"
    const val ERR_COUNT_LIMIT_EXCEEDED = "ERR_GEN_COUNT_LIMIT_EXCEEDED"
    const val ERR_PROFILE_REQUIRED = "ERR_GEN_PROFILE_REQUIRED"
    const val ERR_OWNER_REQUIRED = "ERR_GEN_OWNER_REQUIRED"
    const val ERR_LENGTH_INVALID = "ERR_GEN_LENGTH_INVALID"
    const val ERR_PREFIX_INVALID = "ERR_GEN_PREFIX_INVALID"
    const val ERR_FORMAT_INVALID = "ERR_GEN_FORMAT_INVALID"
    /** الراوتر غير جاهز للتوليد (فحص جاهزية أساسي فاشل). */
    const val ERR_ROUTER_NOT_READY = "ERR_GEN_ROUTER_NOT_READY"
    const val ERR_KEYSPACE_TOO_SMALL = "ERR_GEN_KEYSPACE_TOO_SMALL"
    const val ERR_MODE_INVALID = "ERR_GEN_MODE_INVALID"
    const val ERR_SEQUENTIAL_NUMERIC_ONLY = "ERR_GEN_SEQUENTIAL_NUMERIC_ONLY"
    const val ERR_PASSWORD_TYPE_INVALID = "ERR_GEN_PASSWORD_TYPE_INVALID"
    const val ERR_PASSWORD_LENGTH_INVALID = "ERR_GEN_PASSWORD_LENGTH_INVALID"
    const val ERR_COMMENT_INVALID = "ERR_GEN_COMMENT_INVALID"

    object Field {
        const val COUNT = "count"
        const val LENGTH = "codeLength"
        const val PREFIX = "prefix"
        const val FORMAT = "format"
        const val OWNER = "ownerId"
        const val PROFILE = "profileId"
        const val MODE = "mode"
        const val PASSWORD_TYPE = "passwordType"
        const val PASSWORD_LENGTH = "passwordLength"
        const val COMMENT = "comment"
    }

    /** أعلى رقم يتسع له اسم تسلسلي بطول [codeLength] خانة (999999 لطول 6). */
    fun sequenceCapacity(codeLength: Int): Long = Math.pow(10.0, codeLength.toDouble()).toLong() - 1

    /**
     * هل يتسع العدّاد الدائم للباقة لـ [count] اسماً بعد آخر رقم مستهلك [lastUsed]؟ (الفحص نفسه الذي تجريه السحابة عند الحجز،
     * فيظهر الخطأ في الشاشة قبل أي طلب لا بعد بدء التنفيذ).
     */
    fun sequenceFits(lastUsed: Long, count: Int, codeLength: Int): Boolean =
        lastUsed + count <= sequenceCapacity(codeLength)

    /**
     * هل تصلح [profile] للتوليد؟ فعّالة، لها اسم في User Manager، وفئتها ليست SUBSCRIPTION (التوليد ينتج مستخدمين عاديين فقط؛
     * السحابة ترفض غير ذلك لاحقاً، فلا نعرضها أصلاً). فئة غير معروفة/غير محمّلة ⇒ لا تُمنع (السحابة هي الحاكم).
     */
    fun isProfileGeneratable(profile: Profile, categories: List<ProfileCategory>): Boolean {
        if (profile.status != ProfileStatus.ACTIVE || profile.linkedProfileName.isBlank()) return false
        val category = categories.firstOrNull { it.id == profile.categoryId }
        return category?.systemClass != SystemClass.SUBSCRIPTION
    }

    /** أحرف اسم المستخدم المسموحة في البادئة (آمنة لـ RouterOS والطباعة). */
    private val PREFIX_REGEX = Regex("^[A-Za-z0-9_-]*$")

    /**
     * @param passwordTypeName فارغ = السلوك القديم (طول/نوع كلمة السر مشتقان من النمط)، فلا يُتحقق من الطول.
     * @return خريطة حقل ← رمز خطأ؛ فارغة إن كان الطلب صالحاً.
     */
    fun validate(
        profileId: String,
        ownerId: String?,
        count: Int,
        formatName: String,
        prefix: String,
        codeLength: Int,
        modeName: String = GenerationMode.RANDOM.name,
        passwordTypeName: String = "",
        passwordLength: Int = 0,
        comment: String = ""
    ): Map<String, String> {
        val errors = linkedMapOf<String, String>()

        if (profileId.isBlank()) errors[Field.PROFILE] = ERR_PROFILE_REQUIRED
        // لا بيع مباشر: المالك إلزامي
        if (ownerId.isNullOrBlank()) errors[Field.OWNER] = ERR_OWNER_REQUIRED

        if (count <= 0) errors[Field.COUNT] = ERR_COUNT_INVALID
        else if (count > MAX_COUNT) errors[Field.COUNT] = ERR_COUNT_LIMIT_EXCEEDED

        val mode = GenerationMode.parse(modeName)
        if (mode == null) errors[Field.MODE] = ERR_MODE_INVALID

        val format = CodeFormat.parse(formatName)
        if (format == null) errors[Field.FORMAT] = ERR_FORMAT_INVALID
        // التسلسلي أرقام فقط (العدّاد رقمي)؛ لا نخمّن تحويلاً صامتاً
        else if (mode == GenerationMode.SEQUENTIAL && format != CodeFormat.NUMERIC_ONLY) errors[Field.FORMAT] = ERR_SEQUENTIAL_NUMERIC_ONLY

        if (codeLength !in MIN_CODE_LENGTH..MAX_CODE_LENGTH) errors[Field.LENGTH] = ERR_LENGTH_INVALID

        if (prefix.length > MAX_PREFIX_LENGTH || !PREFIX_REGEX.matches(prefix)) errors[Field.PREFIX] = ERR_PREFIX_INVALID

        // فضاء المفاتيح: العشوائي يحتاج ضعف الكمية على الأقل لينتهي بلا تصادم مزمن، والتسلسلي يكفيه أن يتسع للكمية
        // (ما يستهلكه العدّاد فعلاً يُفحص وقت التوليد لأنه حالة دائمة لا يعرفها هذا التحقق النقي).
        if (format != null && Field.LENGTH !in errors && Field.COUNT !in errors && Field.FORMAT !in errors) {
            val factor = if (mode == GenerationMode.SEQUENTIAL) 1.0 else 2.0
            if (VoucherCodeGenerator.keyspace(format, codeLength) < count * factor) errors[Field.LENGTH] = ERR_KEYSPACE_TOO_SMALL
        }

        if (passwordTypeName.isNotBlank()) {
            val pt = PasswordType.parse(passwordTypeName)
            if (pt == null) errors[Field.PASSWORD_TYPE] = ERR_PASSWORD_TYPE_INVALID
            // «مطابقة اسم المستخدم» لا طول لها؛ ما عداها يحتاج طولاً صالحاً (لا قيم فارغة أو غير صالحة)
            else if (pt != PasswordType.SAME_AS_USERNAME && passwordLength !in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
                errors[Field.PASSWORD_LENGTH] = ERR_PASSWORD_LENGTH_INVALID
            }
            // نمط «اسم = كلمة المرور» القديم يتعارض مع نوع كلمة مرور مستقل غير المطابقة: لا نختار صامتاً أحدهما
            else if (format == CodeFormat.USER_PASS_SAME && pt != PasswordType.SAME_AS_USERNAME) {
                errors[Field.PASSWORD_TYPE] = ERR_PASSWORD_TYPE_INVALID
            }
        }

        // التعليق نص حر اختياري؛ يُمنع فقط المحرِّف الذي يكسر وسم الدفعة أو أمر RouterOS (أسطر جديدة/تحكم)
        if (comment.length > MAX_COMMENT_LENGTH || comment.any { it.isISOControl() }) errors[Field.COMMENT] = ERR_COMMENT_INVALID

        return errors
    }
}
