package ps.mgtik.hotspot.domain.model

import kotlinx.serialization.Serializable

// --- §6.9 – §6.11 سجلات المستخدمين المحليين والاكتشاف والأرشيف ---

@Serializable
enum class UserRecordStatus {
    ACTIVE, DISABLED, EXPIRED
}

@Serializable
enum class UserOrigin {
    GENERATED, ADOPTED
}

@Serializable
enum class UserSyncStatus {
    SYNCED, PENDING, FAILED
}

/** سجل مستخدم مُدار (جدول local_users / مجموعة Firestore local_users). */
@Serializable
data class LocalUserRecord(
    val recordUuid: String,
    val routerUsername: String,
    val ownerId: String,
    val status: UserRecordStatus = UserRecordStatus.ACTIVE,
    val batchId: String? = null,
    val origin: UserOrigin = UserOrigin.GENERATED,
    val syncStatus: UserSyncStatus = UserSyncStatus.SYNCED,
    val lastAction: String? = null,
    val lastActionResult: String? = null,
    val lastActionAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    /**
     * القيود الفعلية التي أُنشئ الحساب على أساسها (لقطة وقت التوليد). تعديل قيود البروفايل لاحقاً لا يغيّرها،
     * وnull = لم يُفرض قيد. للحسابات المتبنّاة (ADOPTED) لا تُعرف القيود فتبقى null.
     */
    val limitUptimeSeconds: Long? = null,
    val limitTransferBytes: Long? = null,
    /**
     * التصنيف النظامي وقت الإنشاء (لقطة ثابتة: لا يتغير بتعديل الفئة/الباقة لاحقاً). السجلات القديمة بلا الحقل = NORMAL:
     * كل ما سبق من local_users أُنشئ بالتوليد/التبنّي، والاشتراكات لم تكن تكتب سجلاً هنا قط.
     * SUBSCRIPTION ⇒ يقابله دائماً سجل Subscription بالمعرّف نفسه (recordUuid == subscriptionId).
     */
    val systemClass: SystemClass = SystemClass.NORMAL
)

@Serializable
enum class DiscoveryType {
    MISSING, NULL
}

@Serializable
enum class DiscoveryStatus {
    OPEN, RESOLVED
}

/** سجل اكتشاف: مستخدم مفقود من الراوتر (MISSING) أو غير مسجَّل سحابياً (NULL). */
@Serializable
data class DiscoveryRecord(
    val discoveryId: String,
    val type: DiscoveryType,
    val key: String,
    val routerUsername: String,
    val status: DiscoveryStatus = DiscoveryStatus.OPEN,
    val firstDetectedAt: String = "",
    val lastDetectedAt: String = "",
    val resolvedAt: String? = null
)

/** صفحة من الأرشيف؛ nextCursor = null حين لا صفحات بعدها. */
data class ArchivedUsersPage(
    val items: List<ArchivedUserSnapshot>,
    val nextCursor: String?
)

/**
 * نوعا الأرشفة (كلاهما مؤرشف في السحابة):
 *  - DISABLED: كان المستخدم على الراوتر وعُطِّل (disabled=yes) وتحقّقنا من ذلك قبل الأرشفة.
 *  - MISSING: غير موجود على الراوتر (محذوف عنه) وقت الأرشفة.
 */
@Serializable
enum class ArchiveType { DISABLED, MISSING }

/** لقطة مستخدم مؤرشَف. قيم administrativeSnapshot نصوص دائماً (لا فقد لأنواع الأرقام عبر Gson). */
@Serializable
data class ArchivedUserSnapshot(
    val archiveId: String,
    val originalRecordUuid: String,
    val routerUsername: String,
    val administrativeSnapshot: Map<String, String> = emptyMap(),
    val reason: String = "expired",
    val archivedBy: String? = null,
    val archivedAt: String = "",
    val archiveType: ArchiveType = ArchiveType.DISABLED
)

// --- عمليات دورة حياة المستخدم: تعديل وأرشفة ---

/**
 * نتيجة أمر على مستخدم مُدار.
 * COMPLETED = نُفِّذ فعلاً وتأكدت نتيجته. QUEUED = وُضع في طابور الأوامر (بحالة Pending) ولم يُنفَّذ بعد؛
 * يجب ألّا تعرضه الواجهة على أنه مكتمل.
 */
enum class UserOperationOutcome { COMPLETED, QUEUED }

/** حالة المستخدم على الراوتر بعد تعطيل متحقَّق منه: DISABLED = قرأناه مجدداً فوجدناه disabled، ABSENT = لا وجود له على الراوتر. */
enum class RouterDisableState {
    DISABLED, ABSENT;

    /** نوع الأرشفة الناتج عن حالة الراوتر المتحقَّق منها. */
    fun toArchiveType(): ArchiveType = if (this == ABSENT) ArchiveType.MISSING else ArchiveType.DISABLED
}

/** نتيجة البحث عن سجل مستخدم قبل عملية عليه. AlreadyArchived = سبقت أرشفته (إعادة تنفيذ بعد نجاح سابق). */
sealed interface UserRecordLookup {
    data class Found(val record: LocalUserRecord) : UserRecordLookup
    data object AlreadyArchived : UserRecordLookup
    data object NotFound : UserRecordLookup
}

/**
 * الحقول القابلة للتعديل في سجل المستخدم المُدار (محصورة بما يحمله [LocalUserRecord] فعلاً):
 *  - ownerId: المالك (إداري، سحابي فقط).
 * حدود الوقت والبيانات والسرعة ملك الباقة فلا تُعدَّل لكل مستخدم (للتغيير: عدّل الباقة أو التصنيف).
 * اسم الدخول والدفعة والمصدر وتاريخ الإنشاء ثابتة لا تُعدَّل.
 * [expectedUpdatedAt] = قيمة updatedAt التي رآها المدير عند فتح التعديل، لكشف التعارض.
 */
@Serializable
data class UserEditRequest(
    val recordUuid: String,
    val expectedUpdatedAt: String = "",
    val ownerId: String
)

/** نوع أمر المستخدم المعلّق في الطابور. */
enum class UserPendingKind { ARCHIVE, EDIT, DELETE_FROM_ROUTER, REEXPORT }

/**
 * أمر أرشفة/تعديل لم يكتمل بعد على مستخدم. failed = استنفد محاولاته (يُعاد من شاشة الطابور)؛ غير ذلك ينتظر التنفيذ.
 * الواجهة تعرضه على أنه «معلّق» لا «مكتمل» إلى أن يختفي الأمر من الطابور بنجاحه.
 */
data class UserPendingOperation(
    val kind: UserPendingKind,
    val failed: Boolean = false,
    val lastError: String? = null
)

// --- إنشاء مستخدم فردي من S16 ---

/**
 * مدخلات إنشاء مستخدم عادي (NORMAL) فردي. الباقة والقيود لقطة منها وقت الإنشاء، لكن المستودع يعيد التحقق من فئة الباقة
 * من السحابة (مصدر الحكم) قبل الكتابة. لا دفعة ولا طلب ولا فاتورة: الدفعة مفهوم توليد جماعي فقط.
 * المالك لا يُمرَّر هنا: يُحسم دائماً إلى مرجع «admin» الفعلي داخل المستودع.
 */
data class NewNormalUser(
    val username: String,
    val password: String,
    val profileId: String,
    /** اسم الباقة على الراوتر (لقطة من الباقة). */
    val routerProfileName: String,
    val comment: String
    // القيود والسرعة والجلسات لا تُمرَّر من الواجهة: المستودع يحسمها من البروفايل وتصنيفه (OperationalSettingsResolver)
)

/**
 * نتيجة إنشاء مستخدم عادي فردي. [syncStatus] != SYNCED ⇒ السجل محفوظ لكن إنشاء الحساب على الراوتر لم يكتمل
 * (PENDING قابل لإعادة المحاولة، FAILED مرفوض)؛ الواجهة تعرضه تحذيراً لا نجاحاً كاملاً.
 */
data class CreatedUser(
    val recordUuid: String,
    val syncStatus: UserSyncStatus,
    /** سبب عدم اكتمال الإنشاء على الراوتر إن وُجد. */
    val syncError: String? = null
)

/**
 * لقطة حساب المستخدم كما قرأها التطبيق للتو من User Manager على الراوتر. مصدرها الراوتر وحده (قراءة فقط).
 * null في أي حقل = لم يُقرأ/غير مضبوط؛ لا تُعرض القيم الافتراضية على أنها فعلية.
 * القيود: 0 في RouterOS تعني «بلا حد» فتُحوَّل إلى null. المستهلَك = عدّادات الراوتر التراكمية للحساب (لا الجلسة الحالية فقط).
 */
data class RouterUserSnapshot(
    val password: String? = null,
    val profile: String? = null,
    val comment: String? = null,
    val disabled: Boolean = false,
    val limitUptimeSeconds: Long? = null,
    val limitTransferBytes: Long? = null,
    /** حدّا التنزيل/الرفع المنفصلان من الباقة (null = بلا حد) والمستهلك في الفترة الحالية لكل اتجاه. */
    val limitDownloadBytes: Long? = null,
    val limitUploadBytes: Long? = null,
    val usedDownloadBytes: Long? = null,
    val usedUploadBytes: Long? = null,
    /** استهلاك الفترة الحالية (ما يُقارَن بحدود الباقة). */
    val usedUptimeSeconds: Long? = null,
    val usedBytes: Long? = null,
    /** الإجمالي التاريخي لكل الفترات (للسجل فقط، لا يدخل في «المتبقي»). */
    val totalUptimeSeconds: Long? = null,
    val totalBytes: Long? = null
)
