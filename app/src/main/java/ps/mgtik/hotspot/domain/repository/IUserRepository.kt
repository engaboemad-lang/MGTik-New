package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.*

/**
 * IUserRepository - عقد دورة حياة المستخدم الكاملة والمزامنة (§4.12 - §4.14)
 * تم التحديث لدعم skipQueue لمنع حلقات التكرار في العمليات غير المتصلة.
 * skipQueue (معنى ثابت في كل المستودعات): false → تمر العملية عبر الطابور/WorkManager، true → تنفيذ مباشر بلا طابور.
 */
interface IUserRepository {

    // --- عمليات القراءة والسرد (§4.13) ---
    fun listManagedUsers(ownerId: String? = null, status: UserRecordStatus? = null): Flow<List<LocalUserRecord>>
    fun listNullUsers(): Flow<List<DiscoveryRecord>>
    /** سجلات MISSING المفتوحة: مستخدمون مُدارون غائبون عن الراوتر (مفتاح السجل = recordUuid). */
    fun listMissingUsers(): Flow<List<DiscoveryRecord>>
    /** صفحة من الأرشيف (الأحدث أولاً) بمؤشّر؛ تعيد الصفحة التالية بتمرير nextCursor السابق. */
    suspend fun getArchivedUsersPage(pageSize: Int, cursor: String? = null): AppResult<ArchivedUsersPage>

    /** عدد المستخدمين المؤرشفين (تجميع على الخادم). */
    suspend fun countArchivedUsers(): AppResult<Long>

    /** أوامر الأرشفة/التعديل غير المكتملة في الطابور، مفهرسة بمعرّف سجل المستخدم (للعرض كحالة Pending/Failed). */
    fun observePendingOperations(): Flow<Map<String, UserPendingOperation>>

    // --- إنشاء مستخدم فردي من S16 ---
    /**
     * مرجع المالك الافتراضي «admin» كما هو في السحابة (لا يُنشأ مالك): خطأ صريح إن غاب أو التبس أو لم يكن نشطاً.
     * يستعمله مساران: إنشاء المستخدم العادي، وإنشاء المشترك الشهري (عبر نظام الاشتراكات).
     */
    suspend fun resolveAdminOwner(): AppResult<OwnerAccount>

    /**
     * إنشاء مستخدم عادي (NORMAL) واحد بلا دفعة: سجل سحابي بمالك admin ثم إنشاء الحساب على الراوتر والتحقق من النتيجة.
     * idempotencyKey = معرّف السجل: إعادة المحاولة بالمفتاح نفسه لا تُنشئ سجلاً مكرراً ولا حساباً مكرراً.
     * نجاح الحفظ دون اكتمال الراوتر يُرجَع نجاحاً بـ syncStatus=PENDING/FAILED (انظر [CreatedUser]) لا نجاحاً كاملاً.
     */
    suspend fun createNormalUser(draft: NewNormalUser, idempotencyKey: String): AppResult<CreatedUser>

    /**
     * اسم الدخول غير محجوز (مُدار/أرشيف/اشتراك/موجود على الراوتر)؟ نجاح = متاح؛ ERR_USER_CREATE_USERNAME_TAKEN = محجوز.
     * سجل [operationKey] نفسه (إعادة محاولة إنشاء سابق) لا يُعدّ تعارضاً. يستعمله مسار المشترك الشهري من S16
     * لأن محرّك الاشتراكات لا يفحص إلا الاشتراكات الأخرى.
     */
    suspend fun ensureUsernameAvailable(username: String, operationKey: String): AppResult<Unit>

    // --- عمليات المستخدمين المدارين (Managed Users - §4.12) ---
    suspend fun enableUser(recordUuid: String, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    suspend fun disableUser(recordUuid: String, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    /**
     * حذف حساب المستخدم من الراوتر مع الإبقاء على سجله السحابي، فيصبح «مفقوداً» (سجل اكتشاف MISSING مفتوح).
     * الحذف يُتحقَّق منه بقراءة ثانية. skipQueue=false ⇒ QUEUED (معلّق).
     */
    suspend fun deleteUserFromRouter(recordUuid: String, idempotencyKey: String, skipQueue: Boolean = false): AppResult<UserOperationOutcome>

    /**
     * إعادة إنشاء حساب مستخدم مفقود على الراوتر من بيانات كرته المحفوظة في دفعته (كلمة السر والباقة) وقيود سجله.
     * مستخدم متبنّى (لا كلمة سر محفوظة له) ⇒ خطأ صريح ERR_USER_REEXPORT_SOURCE_MISSING. موجود أصلاً ⇒ نجاح دون إنشاء مكرر.
     */
    suspend fun reExportUserToRouter(recordUuid: String, idempotencyKey: String, skipQueue: Boolean = false): AppResult<UserOperationOutcome>
    suspend fun renewUserPackage(recordUuid: String, idempotencyKey: String, skipQueue: Boolean = false): AppResult<Unit>
    /**
     * أرشفة = تعطيل على الراوتر والتحقق منه ثم أرشفة سحابية (انظر UserArchiveFlow). لا تحذف المستخدم من الراوتر.
     * skipQueue=false ⇒ QUEUED (بانتظار التنفيذ، ليست مكتملة)؛ skipQueue=true ⇒ تنفيذ مباشر ونتيجته COMPLETED أو خطأ.
     */
    suspend fun archiveUser(recordUuid: String, reason: String, idempotencyKey: String, skipQueue: Boolean = false): AppResult<UserOperationOutcome>

    /** تعديل الحقول المسموحة في سجل المستخدم (المالك، قيدا الاستخدام) ومزامنة التشغيلي منها مع الراوتر. نفس دلالة skipQueue. */
    suspend fun editUser(request: UserEditRequest, idempotencyKey: String, skipQueue: Boolean = false): AppResult<UserOperationOutcome>

    suspend fun deleteLocalUserRecord(recordUuid: String, idempotencyKey: String): AppResult<Unit>
    /** سجل لم يكتمل إنشاء حسابه على الراوتر: يُعاد إنشاؤه إن غاب عنه، وإلا تُعاد مزامنة حالته. تنفيذ مباشر. */
    suspend fun retryCreationFailedUser(recordUuid: String, idempotencyKey: String): AppResult<Unit>

    /** إعادة فرض حالة السجل (نشط/معطَّل) على حساب الراوتر مع التحقق، ثم تعليم السجل SYNCED. تنفيذ مباشر. */
    suspend fun retryUserSync(recordUuid: String, idempotencyKey: String): AppResult<Unit>

    // --- عمليات المستخدمين Null (§4.12) ---
    /** تعطيل مستخدم راوتر غير معرّف سحابياً مع التحقق (يبقى غير معرّف حتى يُتبنّى). */
    suspend fun disableNullUser(routerUsername: String, idempotencyKey: String): AppResult<Unit>

    /** حذف مستخدم راوتر غير معرّف نهائياً مع التحقق وإغلاق سجل اكتشافه؛ يُرفض إن صار مُداراً. */
    suspend fun deleteNullUser(routerUsername: String, idempotencyKey: String): AppResult<Unit>
    suspend fun adoptNullUser(routerUsername: String, ownerId: String, idempotencyKey: String): AppResult<Unit>

    // --- المزامنة والمطابقة (§4.14) ---
    suspend fun reconcileUserRegistry(): AppResult<Unit>

    // --- تفاصيل المستخدم (قراءة فقط) ---
    /** قراءة حساب المستخدم من الراوتر الآن: Success(null) = غير موجود على الراوتر؛ خطأ = تعذّر الاتصال (لا نخمّن). */
    suspend fun getRouterUserSnapshot(username: String): AppResult<RouterUserSnapshot?>

    /** كلمة المرور المحفوظة سحابياً لحساب مُدار (كرت الدفعة أو مصدر الإنشاء الفردي)؛ Success(null) = لا مصدر محفوظ (مثل المتبنّى). */
    suspend fun getStoredUserPassword(recordUuid: String): AppResult<String?>
}
