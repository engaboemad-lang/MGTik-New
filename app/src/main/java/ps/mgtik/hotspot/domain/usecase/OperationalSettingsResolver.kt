package ps.mgtik.hotspot.domain.usecase

import ps.mgtik.hotspot.domain.generation.UserLimits
import ps.mgtik.hotspot.domain.model.OperationalSetting
import ps.mgtik.hotspot.domain.model.Profile
import ps.mgtik.hotspot.domain.model.ProfileClassification

/**
 * إعدادات التشغيل المحسومة لمستخدم جديد (لقطة تُثبَّت وقت الإنشاء). نقية بلا Android.
 *
 * كلها تعيش في **الباقة** على الراوتر (وليس في حساب المستخدم):
 *  - [limits]: حدا الوقت والبيانات لكل فترة. لا يُرسلان مع المستخدم؛ يتحقق التطبيق بعد الإنشاء أن حدود الباقة المُسندة تطابقها ([limitsMatch]).
 *  - [downloadKbps] / [uploadKbps] / [sharedUsers]: السرعة والجلسات المتزامنة؛ يتحقق التطبيق من تطابق الباقة معها قبل الإنشاء ([mismatches]).
 *  - مصدر القيم الفعلية واحد: [ClassificationRules.effective] (قيم الباقة الذاتية أو الموروثة من التصنيف).
 *
 * null = غير محدد (لا قيمة مفروضة): السرعة 0 تُعامَل كغير محددة، ولا تُخترع قيم افتراضية.
 */
data class ResolvedUserSettings(
    val limits: UserLimits = UserLimits.NONE,
    val downloadKbps: Int? = null,
    val uploadKbps: Int? = null,
    val sharedUsers: Int? = null,
    /** سياسة بداية الصلاحية: "first_auth" (عند أول مصادقة) أو "assigned" (عند الإسناد)؛ null = غير محددة. */
    val validityStart: String? = null,
    /** مدة الصلاحية بالثواني (null = غير محددة، لا تُفرض ولا تُقارَن). */
    val validitySeconds: Long? = null,
    val validityUnlimited: Boolean = false,
    /**
     * true = عدد أجهزة غير محدود (override-shared-users=unlimited) بقرار صريح من البروفايل؛ [sharedUsers] يكون null حينها.
     * يختلف عن sharedUsers = 1 (جهاز واحد): كلاهما يُقارَن بما على الراوتر ولا يُخلطان.
     */
    val sharedUnlimited: Boolean = false
)

/** ما يقرؤه التطبيق من الباقة على الراوتر (kbps؛ 0/null = بلا حد). */
data class RouterProfileSettings(
    val downloadKbps: Int? = null,
    val uploadKbps: Int? = null,
    val sharedUsers: Int? = null,
    /**
     * حدّا الوقت والبيانات كما على Limitation الباقة في User Manager (null = بلا حد). لا تُقارَن إلا إن كان
     * [limitsKnown] = true، أي قرأها المصدر فعلاً من الراوتر؛ الافتراضي false يحفظ سلوك المستدعين القدامى.
     */
    val uptimeSeconds: Long? = null,
    val transferBytes: Long? = null,
    val limitsKnown: Boolean = false,
    /**
     * false = Limitation التطبيق (mgtik-<name>) **موجودة على الراوتر لكنها غير مربوطة** بالباقة (إنشاء ناقص): سرعتها وحدودها
     * غير مفروضة على المستخدمين. غيابها كلياً (باقة يدوية بقيود أخرى أو بلا قيود) ليس «غير مربوطة»، وتُفحص قيمها كما هي.
     * الافتراضي true يحفظ سلوك المستدعين القدامى.
     */
    val limitationLinked: Boolean = true,
    /** سياسة بداية الصلاحية على Profile في UM ("first_auth"/"assigned")؛ null = لم تُقرأ. */
    val startMode: String? = null,
    val validitySeconds: Long? = null,
    val validityUnlimited: Boolean = false,
    /** هل قُرئت الصلاحية وسياسة البداية فعلاً من UM؟ لا مقارنة بدونه. */
    val validityKnown: Boolean = false,
    /** الباقة على الراوتر override-shared-users=unlimited ([sharedUsers] لا يمثلها؛ يبقى 1). */
    val sharedUnlimited: Boolean = false
)

/**
 * OperationalSettingsResolver - المصدر الوحيد لحسم إعدادات المستخدم من البروفايل وتصنيف الباقة.
 * يستعمله الإنشاء الفردي والتوليد الجماعي معاً (بنفس المدخلات ⇒ نفس النتيجة)، عبر [ClassificationRules.effective].
 */
object OperationalSettingsResolver {
    const val ERR_LIMIT_INVALID = "ERR_PROFILE_LIMIT_INVALID"
    const val ERR_CLASSIFICATION_MISSING = "ERR_PROFILE_CLASSIFICATION_MISSING"
    const val ERR_ROUTER_PROFILE_OUT_OF_SYNC = "ERR_PROFILE_ROUTER_OUT_OF_SYNC"
    const val ERR_ROUTER_LIMITS_UNVERIFIED = "ERR_USER_ROUTER_LIMITS_UNVERIFIED"
    /** الباقة غير موجودة في User Manager: إنشاء حسابات عليها ينتج مستخدمين بلا Profile. */
    const val ERR_ROUTER_PROFILE_MISSING = "ERR_PROFILE_ROUTER_MISSING"
    /** بعد الإنشاء: المستخدم غير موجود/بلا الباقة المطلوبة/معطَّل على User Manager. */
    const val ERR_USER_ROUTER_UNVERIFIED = "ERR_USER_ROUTER_UNVERIFIED"

    sealed interface Outcome {
        data class Ok(val settings: ResolvedUserSettings) : Outcome
        data class Invalid(val code: String, val detail: String) : Outcome
    }

    /**
     * @param classification التصنيف المقروء من المصدر المعتمد؛ null يعني «لم يُعثر عليه».
     * بروفايل مرتبط بتصنيف ([Profile.classificationKey]) وتصنيفه غائب ⇒ خطأ صريح (لا رجوع صامت لقيم قديمة).
     */
    fun resolve(profile: Profile, classification: ProfileClassification?): Outcome {
        if (!profile.classificationKey.isNullOrBlank() && classification == null) {
            return Outcome.Invalid(ERR_CLASSIFICATION_MISSING, "Classification '${profile.classificationKey}' not found")
        }
        val e = ClassificationRules.effective(profile, classification)
        if (!UsageLimits.isUptimeValid(e.limitUptimeSeconds) || !UsageLimits.isTransferValid(e.limitTransferBytes)) {
            return Outcome.Invalid(ERR_LIMIT_INVALID, "Usage limits invalid (uptime=${e.limitUptimeSeconds}, transfer=${e.limitTransferBytes})")
        }
        return Outcome.Ok(
            ResolvedUserSettings(
                limits = UserLimits(e.limitUptimeSeconds, e.limitTransferBytes),
                // rx = رفع العميل، tx = تنزيله (اصطلاح RouterOS المعتمد في بقية التطبيق)
                downloadKbps = e.txKbps.takeIf { it > 0 },
                uploadKbps = e.rxKbps.takeIf { it > 0 },
                // غير المحدود قرار صريح للبروفايل (يُضاف إلى ownSettings عند حفظه) ويغلب العدد الموروث/المخزَّن
                sharedUsers = if (profile.sharedUnlimited) null else e.sharedUsers.takeIf { it > 0 },
                validityStart = profile.validityStart,
                validitySeconds = (profile.validityHours.toLong() * 3_600L).takeIf { it > 0 },
                validityUnlimited = profile.validityUnlimited,
                sharedUnlimited = profile.sharedUnlimited
            )
        )
    }

    /** الإعدادات التي يخالف فيها بروفايل الراوتر القيم المحسومة (السرعة بلا حد تقابل 0). فارغة = متطابق. */
    fun mismatches(expected: ResolvedUserSettings, actual: RouterProfileSettings): Set<OperationalSetting> = buildSet {
        if ((expected.downloadKbps ?: 0) != (actual.downloadKbps ?: 0) ||
            (expected.uploadKbps ?: 0) != (actual.uploadKbps ?: 0)
        ) add(OperationalSetting.SPEED)
        // غير محدود ≠ 1: الاختلاف في الجهتين مخالفة (قراءة الراوتر تعيد 1 للباقة غير المحدودة فلا يكفي مقارنة العدد وحده)
        if (expected.sharedUnlimited != actual.sharedUnlimited) add(OperationalSetting.SHARED_USERS)
        else if (!expected.sharedUnlimited && expected.sharedUsers != null && expected.sharedUsers != (actual.sharedUsers ?: 1)) add(OperationalSetting.SHARED_USERS)
        // User Manager هو المرجع التشغيلي: الحدّان المحسومان يجب أن يطابقا ما يفرضه الراوتر فعلاً، لا لقطة السحابة وحدها
        if (actual.limitsKnown) {
            if (expected.limits.uptimeSeconds != actual.uptimeSeconds) add(OperationalSetting.LIMIT_UPTIME)
            if (expected.limits.transferBytes != actual.transferBytes) add(OperationalSetting.LIMIT_TRANSFER)
        }
        // Limitation التطبيق موجودة وغير مربوطة: ما يعتمد عليها (سرعة/وقت/حجم) غير مفروض فعلاً حتى لو تطابقت قيمة ما مع قيد غريب،
        // ولا يجوز أن يمرّ الإنشاء كأن الباقة سليمة. الإصلاح: إعادة مزامنة الباقة ليُربط القيد.
        if (actual.limitsKnown && !actual.limitationLinked) {
            if (expected.downloadKbps != null || expected.uploadKbps != null) add(OperationalSetting.SPEED)
            if (expected.limits.uptimeSeconds != null) add(OperationalSetting.LIMIT_UPTIME)
            if (expected.limits.transferBytes != null) add(OperationalSetting.LIMIT_TRANSFER)
        }
    }

    /**
     * اختلاف الصلاحية وسياسة البداية بين المحسوم وUser Manager (مفاتيح نصية؛ فارغة = متطابق أو غير مقروء).
     * FIRST_AUTH لا تبدأ المدة قبل أول مصادقة، وASSIGNED تبدأ عند الإسناد: خطأ السياسة يغيّر لحظة بدء الاحتساب لكل الحسابات.
     */
    fun validityMismatches(expected: ResolvedUserSettings, actual: RouterProfileSettings): Set<String> = buildSet {
        if (!actual.validityKnown) return@buildSet
        if (expected.validityStart != null && actual.startMode != null && expected.validityStart != actual.startMode) add("validity-start")
        if (expected.validityUnlimited) {
            if (!actual.validityUnlimited) add("validity")
        } else if (expected.validitySeconds != null && (actual.validityUnlimited || expected.validitySeconds != actual.validitySeconds)) {
            add("validity")
        }
    }

    /** هل القيدان الفعليان على حساب الراوتر هما المطلوبان؟ (null = بلا حد، كما يقرؤه getUserSnapshot). */
    fun limitsMatch(expected: UserLimits, uptimeSeconds: Long?, transferBytes: Long?): Boolean =
        expected.uptimeSeconds == uptimeSeconds && expected.transferBytes == transferBytes
}
