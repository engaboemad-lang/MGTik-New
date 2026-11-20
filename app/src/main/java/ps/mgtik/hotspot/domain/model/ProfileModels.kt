package ps.mgtik.hotspot.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProfileStatus {
    /** DISABLED = إيقاف مؤقت إداري: لا يُستخدم في عمليات جديدة ويبقى ظاهراً للإدارة (يختلف عن ARCHIVED وعن MISSING وهي حالة مزامنة). */
    ACTIVE, ARCHIVED, MISSING, DISABLED
}

/**
 * إعدادات متقدمة لـ Limitation الباقة عند الإنشاء فقط (لا تُحفظ في Room/Firestore؛ المرجع التشغيلي هو الراوتر وتُعدَّل بعدها من شاشة Limitations).
 * rx = رفع، tx = تنزيل. أصفار Burst = بلا Burst؛ null في حدّي التنزيل/الرفع = بلا حد.
 */
@Serializable
data class PackageLimitExtras(
    val downloadBytes: Long? = null,
    val uploadBytes: Long? = null,
    val burstRxKbps: Int = 0,
    val burstRxThresholdKbps: Int = 0,
    val burstRxTimeSeconds: Long = 0L,
    val burstTxKbps: Int = 0,
    val burstTxThresholdKbps: Int = 0,
    val burstTxTimeSeconds: Long = 0L,
    /** أولوية الحركة 1..8؛ null أو 8 = عادية (لا تُرسل). */
    val priority: Int? = null,
    /** فترة التصفير الدوري (wire: hourly/daily/weekly/monthly)؛ null = بلا تصفير (الافتراضي). v7 فقط. */
    val resetInterval: String? = null,
    /** نافذة التشغيل: null = بلا جدولة (طوال الأسبوع وعلى مدار الساعة). الأيام نص wire مفصول بفواصل؛ فارغ = كل الأيام. */
    val scheduleFromSeconds: Int? = null,
    val scheduleTillSeconds: Int? = null,
    val scheduleDays: String = ""
)

@Serializable
data class Profile(
    val id: String = "",
    val linkedProfileName: String = "", // الاسم في ميكروتك
    val displayNameEn: String = "",
    val displayNameAr: String = "",
    val description: String? = null,
    val categoryId: String? = null,
    val classificationKey: String? = null,
    val status: ProfileStatus = ProfileStatus.ACTIVE,
    val createdAt: String = "",
    val updatedAt: String = "",
    val version: Int = 1,
    
    // الخصائص التقنية المستخلصة من الراوتر
    val rateLimitTxKbps: Int = 0,
    val rateLimitRxKbps: Int = 0,
    val validityHours: Int = 0,
    val quotaMb: Int = 0,
    val sharedUsers: Int = 1,
    
    // الخصائص المالية (بالوحدة الصغرى)
    val priceMinor: Long = 0L,

    /**
     * قيود الاستخدام الإدارية (تُحفظ في الباقة وتُدفع إلى الراوتر معها).
     * تُفرض على الراوتر عبر حدود الباقة (Limitation) لكل فترة، لا عبر حساب المستخدم.
     * null = غير مفروض (يختلف عن الصفر: لا يُقبل صفر، انظر UsageLimits). الوحدة الموحدة: ثوانٍ / بايت.
     */
    val limitUptimeSeconds: Long? = null,
    val limitTransferBytes: Long? = null,

    /**
     * الإعدادات التشغيلية التي يحدّدها البروفايل بنفسه (لها الأولوية على [classificationKey]).
     * null = بروفايل قديم لم يُعالَج بعد: كل إعداداته تُعتبر خاصة به ولا يرث شيئاً (لا نخمّن).
     * مجموعة فارغة = يرث كل ما يعرّفه التصنيف. قيم الحقول أعلاه تحمل دائماً القيمة الفعلية المطبَّقة.
     */
    val ownSettings: Set<OperationalSetting>? = null,

    /** متى تبدأ الصلاحية: "first_auth" (أول مصادقة) أو "assigned" (عند الإسناد). القيمة المحفوظة تُمرَّر للراوتر ولا تُثبَّت. */
    val validityStart: String = VALIDITY_START_FIRST_AUTH,
    /** صلاحية غير محدودة بقرار صريح (يختلف عن validityHours=0 الذي يعني «غير محددة: لا تُلمس»). */
    val validityUnlimited: Boolean = false,
    /** عدد أجهزة غير محدود (override-shared-users=unlimited)؛ [sharedUsers] يُهمل حينها. */
    val sharedUnlimited: Boolean = false,
    /**
     * اختيار الإنشاء فقط (لا يُحفظ في Room/Firestore: العلاقة الفعلية تعيش على الراوتر): أسماء Limitations موجودة/مشتركة تُربط بالـ Profile.
     * فارغة = السلوك القديم (Limitation مشتقة باسم الباقة).
     */
    val limitationNames: List<String> = emptyList(),
    /** true = الـ Limitations المختارة مرجعية: تُربط ولا تُعدَّل قيمها إطلاقاً (مكوّن مشترك). */
    val limitationsReference: Boolean = false,
    /** إعدادات Burst وحدّي التنزيل/الرفع المنفصلين للـ Limitation الجديدة (إنشاء فقط). */
    val limitExtras: PackageLimitExtras? = null
) {
    /** حد البيانات الذاتي للباقة: limitTransferBytes أو الحصة القديمة quotaMb (المصدر الوحيد لهذا الاشتقاق في كل المسارات). */
    val ownTransferBytes: Long? get() = limitTransferBytes ?: quotaMb.takeIf { it > 0 }?.let { it * 1_048_576L }
}

const val VALIDITY_START_FIRST_AUTH = "first_auth"
const val VALIDITY_START_ASSIGNED = "assigned"

/** الإعدادات التشغيلية القابلة للوراثة من تصنيف الباقة. */
@Serializable
enum class OperationalSetting {
    /** سرعة التنزيل والرفع معاً (rate-limit). */
    SPEED,
    SHARED_USERS,
    LIMIT_UPTIME,
    LIMIT_TRANSFER;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): OperationalSetting? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** باقة موجود على الراوتر (لخيار «ربط بروفايل موجود»)، بإعداداته المقروءة منه. */
data class RouterProfileOption(
    val name: String,
    val rateLimitRxKbps: Int = 0,
    val rateLimitTxKbps: Int = 0,
    val sharedUsers: Int = 1,
    /** override-shared-users=unlimited: [sharedUsers] لا يمثّله (يبقى 1) فلا يُقارَن ولا يُعرض كعدد. */
    val sharedUnlimited: Boolean = false,
    /** مدة الصلاحية بالثواني؛ null = غير محددة على الراوتر. تُهمل مع [validityUnlimited]. */
    val validitySeconds: Long? = null,
    val validityUnlimited: Boolean = false,
    /** سياسة بداية الصلاحية ("first_auth"/"assigned")؛ null = غير مقروءة من الراوتر. */
    val validityStart: String? = null,
    /** حدّا الوقت/الحجم على Limitation الباقة؛ null = بلا حد. */
    val limitUptimeSeconds: Long? = null,
    val limitTransferBytes: Long? = null
)

/** ما سيحدث عند تغيير المرجع التقني للباقة على الراوتر. */
enum class ReferenceChangeAction { RENAME_ROUTER_PROFILE, REPOINT_TO_EXISTING, BLOCKED }

enum class ReferenceChangeBlock { INVALID_NAME, SAME_NAME, USED_BY_OTHER_PROFILE, NEW_NOT_ON_ROUTER }

/**
 * أثر تغيير المرجع (يُعرض قبل الاعتماد). القيم الفارغة (null) = تعذّرت قراءتها الآن ولا تُعرض كصفر.
 *  - [usersOnOldProfile]: المستخدمون المرتبطون حالياً بالمرجع القديم على الراوتر.
 *  - [batchesCount]: دفعات سابقة للباقة؛ لا تتغير (بطاقاتها السابقة لا تُعدَّل بأثر رجعي).
 *  - [targetSettings]: إعدادات البروفايل الموجود على الراوتر عند الربط بمرجع قائم.
 */
data class ReferenceChangeImpact(
    val profileId: String,
    val oldName: String,
    val newName: String,
    val action: ReferenceChangeAction,
    val block: ReferenceChangeBlock?,
    val usersOnOldProfile: Int?,
    val batchesCount: Int?,
    val currentSettings: RouterProfileOption,
    val targetSettings: RouterProfileOption?,
    val settingsDiffer: Boolean,
    /** أسماء الإعدادات المختلفة بين المخزَّن والمرجع الموجود (فارغة = متطابقة أو لا مرجع). تتبع [settingsDiffer] دائماً. */
    val differingSettings: Set<String> = emptySet(),
    /** رمز خطأ إن كان الربط بالمرجع سيُرفض لأن إعداداته لا تُمثَّل بدقة (مثلاً صلاحية ليست ساعات كاملة)؛ null = قابل للتنفيذ. */
    val importProblem: String? = null
)

@Serializable
enum class CategoryStatus { ACTIVE, DISABLED, ARCHIVED }

/**
 * التصنيف النظامي: جزء من منطق التطبيق (قيمتان ثابتتان لا يضيفهما المدير ولا يحذفهما).
 * NORMAL = المستخدم العادي والكروت، SUBSCRIPTION = المشترك الشهري (له سجل Subscription).
 * يُحدَّد عند إنشاء الفئة ولا يتغير، وترثه الباقات منها، ويُثبَّت في سجل المستخدم عند إنشائه.
 * مستقل عن [ProfileClassification] (تصنيف التسعير القديم المُدار من المدير).
 */
@Serializable
enum class SystemClass {
    NORMAL, SUBSCRIPTION;

    /** قيمة Firestore (حروف صغيرة). */
    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): SystemClass? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

@Serializable
data class ProfileCategory(
    val id: String = "",
    val nameAr: String = "",
    val nameEn: String = "",
    val iconRes: String? = null,
    val priority: Int = 0,
    val description: String? = null,
    /** غياب الحقل في Firestore = ACTIVE (توافق مع الفئات القديمة). */
    val status: CategoryStatus = CategoryStatus.ACTIVE,
    /**
     * إلزامي عند إنشاء فئة جديدة وثابت بعدها. null = فئة قديمة لم تُصنَّف بعد (لا نخمّن تصنيفها):
     * لا تُستخدم لإنشاء مستخدمين/اشتراكات جدد حتى يعيّن المدير تصنيفها مرة واحدة.
     */
    val systemClass: SystemClass? = null,
    /** عملة الفئة (رمز العرض). تُحدَّد عند الإنشاء وتثبت بعدها؛ null = فئة قديمة بلا بيانات تجارية. */
    val currency: String? = null,
    /** سعر التجزئة (وحدة صغرى)؛ null = غير محدد (لا يُخمَّن ولا يُنسخ من سعر آخر). */
    val retailPriceMinor: Long? = null,
    /** سعر الجملة (وحدة صغرى)؛ null = غير محدد. */
    val wholesalePriceMinor: Long? = null
)

/**
 * تصنيف الباقة (تشغيلي/فني): ما يحدد كيف يعمل البروفايل والمستخدم على MikroTik.
 * لا يحمل أي سعر. null = إعداد غير معرّف في التصنيف (يبقى لدى البروفايل).
 * الوحدات الموحدة: السرعة kbps، المدة ثوانٍ، الحجم بايت (مثل [Profile]).
 * [key] هو معرّف المستند (التصنيفات القديمة تحتفظ بمفاتيحها).
 */
data class ProfileClassification(
    val key: String = "",
    val nameAr: String = "",
    val nameEn: String = "",
    val description: String? = null,
    val status: CategoryStatus = CategoryStatus.ACTIVE,
    val downloadKbps: Int? = null,
    val uploadKbps: Int? = null,
    val sharedUsers: Int? = null,
    val limitUptimeSeconds: Long? = null,
    val limitTransferBytes: Long? = null
) {
    /** هل يعرّف التصنيف هذا الإعداد؟ (السرعة تُعتبر معرّفة إن حُدّد اتجاه واحد على الأقل). */
    fun defines(setting: OperationalSetting): Boolean = when (setting) {
        OperationalSetting.SPEED -> downloadKbps != null || uploadKbps != null
        OperationalSetting.SHARED_USERS -> sharedUsers != null
        OperationalSetting.LIMIT_UPTIME -> limitUptimeSeconds != null
        OperationalSetting.LIMIT_TRANSFER -> limitTransferBytes != null
    }
}

/** سياق العملية التجارية: يحدد أي سعر يُستعمل. توليد كروت لمالك/وكيل = جملة؛ اشتراك أو بيع مباشر = تجزئة. */
@Serializable
enum class SaleContext {
    WHOLESALE, RETAIL;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): SaleContext? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

@Serializable
data class PricingRule(
    val id: String = "",
    val cloudProfileId: String = "",
    val classificationKey: String = "",
    val retailPriceMinor: Long = 0,
    val costPriceMinor: Long = 0,
    val effectiveFrom: String = "",
    /**
     * فارغ = قاعدة قديمة/عامة (لا تُستخدم في الاختيار: السعر الأساسي مصدره الوحيد Profile.priceMinor).
     * غير فارغ = سعر خاص بهذا الوكيل (Owner) لهذا البروفايل؛ معرّف المستند ثابت profileId__ownerId فلا يتكرر.
     */
    val ownerId: String = "",
    /** false = السعر الخاص معطّل: تُستخدم الأسعار الأساسية للعمليات الجديدة. */
    val active: Boolean = true,
    /**
     * غير فارغ = سعر خاص على مستوى الفئة (النموذج الحالي)، معرّف المستند categoryId__ownerId__context.
     * فارغ مع [cloudProfileId] = سعر خاص قديم على مستوى البروفايل: يبقى محترماً (لا يتغير سعر متفق عليه بصمت) ولا يُنشأ بعد الآن.
     */
    val categoryId: String = "",
    /** السياق الذي ينطبق فيه السعر الخاص. القواعد القديمة (بلا الحقل) = جملة لأنها كانت تُستعمل لتوليد المالكين. */
    val context: SaleContext = SaleContext.WHOLESALE,
    /** عملة السعر عند حفظه (نسخة من عملة الفئة)؛ فارغ = قاعدة قديمة بلا عملة. */
    val currency: String = ""
)

@Serializable
data class ProfileDefaults(
    val profileId: String = "",
    val defaultRateLimit: String = "8M/8M",
    val defaultSharedUsers: Int = 1,
    val defaultSessionTimeout: String = "00:00:00",
    /**
     * العدّاد التسلسلي الدائم للبروفايل: آخر رقم استُهلك في التوليد التسلسلي (0 = لم يُستعمل بعد).
     * يملكه البروفايل (مفتاح الوثيقة = معرّف البروفايل)؛ لا تكتبه upsertProfileDefaults، تديره الساغا وزر التصفير فقط.
     */
    val lastAssignedSequence: Long = 0L
)

/** مصدر القيمة الفعلية لإعداد تشغيلي في البروفايل. */
enum class SettingSource { INHERITED, PROFILE }

/** القيم التشغيلية الفعلية للبروفايل (بعد الوراثة) مع مصدر كل إعداد. */
data class EffectiveSettings(
    val rxKbps: Int,
    val txKbps: Int,
    val sharedUsers: Int,
    val limitUptimeSeconds: Long?,
    val limitTransferBytes: Long?,
    val sources: Map<OperationalSetting, SettingSource>
)

/** بروفايل يتأثر بتطبيق تصنيف، وما يتغير فيه. */
data class ClassificationApplyItem(
    val profile: Profile,
    val updated: Profile,
    val changed: Set<OperationalSetting>,
    /** يتغير إعداد على بروفايل الراوتر (سرعة/جلسات) فيسري على كل مستخدميه تلقائياً. */
    val touchesRouterProfile: Boolean,
    /** يتغير قيد مدة/حجم على حسابات المستخدمين الحاليين. */
    val touchesUserLimits: Boolean,
    /** بروفايل اشتراكات: قيود المستخدمين يملكها الاشتراك فلا تُمسّ هنا. */
    val userLimitsManagedBySubscription: Boolean
)

data class ClassificationApplyPlan(
    val classificationKey: String,
    val items: List<ClassificationApplyItem>,
    /** عدد المستخدمين على الراوتر المرتبطين ببروفايلات الخطة؛ null = تعذّرت قراءته (لا يُعرض كصفر). */
    val affectedUsers: Int? = null
)

/** نتيجة تطبيق تصنيف: ما نجح وما فشل (تُسجَّل في سجل التدقيق). */
data class ClassificationApplyResult(
    val profilesUpdated: Int = 0,
    val profileFailures: List<String> = emptyList(),
    val usersUpdated: Int = 0,
    val userFailures: List<String> = emptyList(),
    val usersSkippedSubscription: Int = 0
) {
    val hasFailures: Boolean get() = profileFailures.isNotEmpty() || userFailures.isNotEmpty()
}

/**
 * عدّ فعلي للسجلات المرتبطة ببروفايل، من Firestore (لا تقدير ولا قيم احتياطية):
 *  - [normalUsers]: سجلات local_users المرتبطة ببروفايل عبر الإنشاء الفردي (creation_profile_id) أو دفعة مولَّدة (batch_id ← batches.cloud_profile_id).
 *  - [subscriptions]: اشتراكات (subscriptions.profile_id)؛ لكل اشتراك سجل مستخدم نظامي SUBSCRIPTION.
 *  - [batches]: عدد الدفعات المولَّدة بالبروفايل.
 * المستخدمون المؤرشفون خارج local_users فلا يُحسبون.
 */
data class ProfileUsage(val normalUsers: Int, val subscriptions: Int, val batches: Int) {
    val users: Int get() = normalUsers + subscriptions
}

/** تغيّر قيمة حقل واحد في سجل تعديلات الفئة (قيم نصية موحدة؛ الأسعار بالوحدة الصغرى؛ null = غير محدد/غير موجود). */
data class FieldChange(val field: String, val oldValue: String?, val newValue: String?)

enum class CategoryHistoryAction {
    CATEGORY_CREATED, CATEGORY_UPDATED, STATUS_CHANGED, OWNER_PRICE_SET, OWNER_PRICE_ACTIVE_CHANGED, OWNER_PRICE_DELETED;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(v: String?): CategoryHistoryAction? = entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
    }
}

/** سطر في سجل التعديلات التجارية للفئة: ثابت بعد كتابته (لا تعديل ولا حذف). */
data class CategoryHistoryEntry(
    val id: String,
    val categoryId: String,
    /** ISO قابل للفرز؛ فارغ إن لم يصل ختم الخادم بعد (كتابة معلّقة). */
    val at: String,
    val action: CategoryHistoryAction,
    val changes: List<FieldChange>,
    val ownerId: String? = null,
    val context: SaleContext? = null
)
