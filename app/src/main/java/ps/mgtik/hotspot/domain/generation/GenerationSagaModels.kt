package ps.mgtik.hotspot.domain.generation

import ps.mgtik.hotspot.domain.usecase.ResolvedUserSettings
import ps.mgtik.hotspot.domain.usecase.RouterProfileSettings

/**
 * نماذج وواجهات الساغا (توليد ← راوتر ← سحابة ← طلب/فاتورة).
 * هذا الملف وبقية حزمة domain.generation نقية: لا Android ولا Firebase ولا Room ⇒ تُختبر بمزيّفات.
 */

/** خطوات الساغا بالترتيب؛ المعرّف النصي هو ما يظهر في S5 وما يُخزَّن في سجل الخطوات. */
enum class SagaStep(val id: String) {
    PREPARE("prepare"),
    GENERATE("generate"),
    ROUTER_PUSH("router_push"),
    CLOUD_COMMIT("cloud_commit"),
    BILLING("billing")
}

enum class RunStatus { QUEUED, RUNNING, RETRYING, COMPLETED, FAILED, CANCELLED }

enum class StepState { PENDING, RUNNING, SUCCESS, FAILED }

enum class VoucherState { PENDING, ROUTER_OK, ROUTER_FAILED, CLOUD_OK }

data class GenerationSpec(
    val profileId: String,
    val profileName: String,
    val ownerId: String,
    val count: Int,
    val format: String,
    val prefix: String,
    val codeLength: Int,
    /** GenerationMode.name؛ الافتراضي العشوائي (السلوك القديم). */
    val mode: String = GenerationMode.RANDOM.name,
    /** PasswordType.name؛ فارغ = السلوك القديم (كلمة السر مشتقة من النمط). */
    val passwordType: String = "",
    val passwordLength: Int = 0,
    /** تعليق المستخدم (اختياري) يُطبَّق على كل حسابات العملية مع وسم الدفعة. */
    val comment: String = ""
)

/**
 * قيود الاستخدام المطبَّقة على كل حساب في الدفعة (لقطة تُحسم مرة واحدة في PREPARE من إعدادات البروفايل
 * وتُحفظ مع الساغا). null = قيد غير مفروض. المدة بالثواني والحجم بالبايت.
 */
data class UserLimits(val uptimeSeconds: Long? = null, val transferBytes: Long? = null) {
    companion object { val NONE = UserLimits() }
}

data class RunState(
    val executionId: String,
    val idempotencyKey: String,
    val spec: GenerationSpec,
    val orderId: String,
    val batchId: String,
    val batchNumber: String = "",
    val ownerName: String = "",
    val pricePerVoucherMinor: Long = 0L,
    val status: RunStatus = RunStatus.QUEUED,
    val steps: Map<SagaStep, StepState> = SagaStep.values().associateWith { StepState.PENDING },
    val attempt: Int = 1,
    val successful: Int = 0,
    val failed: Int = 0,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val cancelRequested: Boolean = false,
    val invoiceId: String? = null,
    val totalAmountMinor: Long = 0L,
    val createdAtMillis: Long = 0L,
    val completedAtMillis: Long? = null,
    /** القيود المحسومة في PREPARE؛ تُعاد بها كل محاولات الدفع للراوتر نفسها حتى لو تغيّر البروفايل. */
    val limits: UserLimits = UserLimits.NONE
) {
    val requested: Int get() = spec.count
    /** وسم الدفعة القديم (MGTik:B-…)؛ لم يعد يُكتب على الراوتر، ويبقى فقط لمطابقة حسابات دفعات قديمة عند الإلغاء. */
    val routerTag: String get() = RouterTags.tag(batchNumber)
    /** التعليق المكتوب على كل حساب في الراوتر: تعليق المختص فقط؛ فارغ ⇒ لا يُرسَل أي تعليق. */
    val routerComment: String get() = spec.comment.trim()
    /**
     * هل يجوز إعادة الكروت المرفوضة إلى PENDING عند إعادة المحاولة؟ فقط ما دامت خطوة الدفع للراوتر لم تُحسم.
     * بعد نجاحها (فشل متأخر في CLOUD_COMMIT/BILLING) الرفض نهائي: إعادتها إلى PENDING كانت تتخطاها الساغا (الخطوة SUCCESS)
     * فتكتمل الدفعة بكروت لم تُنشأ أبداً ويختفي عدّاد المرفوض فتظهر النتيجة «ناجحة كلياً» كذباً.
     */
    val canRepushRejected: Boolean get() = step(SagaStep.ROUTER_PUSH) != StepState.SUCCESS

    fun step(step: SagaStep): StepState = steps[step] ?: StepState.PENDING
    fun withStep(step: SagaStep, state: StepState): RunState = copy(steps = steps + (step to state))
    val isTerminal: Boolean get() = status == RunStatus.COMPLETED || status == RunStatus.CANCELLED
}

/**
 * RouterTags - وسم الدفعة على الراوتر. يبقى الوسم بادئة التعليق دائماً، فيُدمج معه تعليق المستخدم
 * ("MGTik:B-000123 | تعليقي") دون أن تفقد المصالحة والإلغاء قدرتهما على إيجاد حسابات الدفعة.
 */
object RouterTags {
    const val SEPARATOR = " | "

    fun tag(batchNumber: String): String = "MGTik:$batchNumber"

    fun comment(batchNumber: String, userComment: String): String {
        val c = userComment.trim()
        return if (c.isEmpty()) tag(batchNumber) else tag(batchNumber) + SEPARATOR + c
    }

    /** هل [routerComment] تعليق حساب من الدفعة ذات الوسم [tag]؟ مطابقة دقيقة (B-000001 لا تطابق B-0000010). */
    fun matches(routerComment: String, tag: String): Boolean =
        routerComment == tag || routerComment.startsWith(tag + SEPARATOR)
}

data class StagedVoucher(
    val executionId: String,
    val seq: Int,
    val username: String,
    val password: String,
    val state: VoucherState = VoucherState.PENDING,
    val error: String? = null
)

data class SagaError(val code: String, val message: String, val retryable: Boolean) {
    companion object {
        const val RUN_NOT_FOUND = "ERR_GEN_RUN_NOT_FOUND"
        const val ALL_FAILED = "ERR_GEN_ROUTER_PUSH_ALL_FAILED"
        const val CANCEL_AFTER_BILLING = "ERR_GEN_CANCEL_AFTER_BILLING"
        const val KEYSPACE_EXHAUSTED = "ERR_GEN_KEYSPACE_EXHAUSTED"
        const val SEQUENCE_EXHAUSTED = "ERR_GEN_SEQUENCE_EXHAUSTED"
        const val ATTEMPTS_EXHAUSTED = "ERR_GEN_ATTEMPTS_EXHAUSTED"
        /** الاسم أُخذ على الراوتر بحساب ليس حسابنا (سباق مع مستخدم آخر): الكرت لا يعمل بكلمة مرورنا فلا يُعدّ ناجحاً ولا يُفوتَر. */
        const val USERNAME_TAKEN = "ERR_GEN_USERNAME_TAKEN"
        const val PROFILE_UNAVAILABLE = "ERR_GEN_PROFILE_UNAVAILABLE"
        const val PENDING_REMAINS = "ERR_GEN_PENDING_REMAINS"
        /** تصدير دفعة لم تكتمل بعد (جارية/فاشلة): كروتها غير مُفوتَرة وقد لا تكون محقَّقة. */
        const val BATCH_NOT_READY = "ERR_GEN_BATCH_NOT_READY"
    }
}

sealed class SagaResult<out T> {
    data class Ok<T>(val value: T) : SagaResult<T>()
    data class Err(val error: SagaError) : SagaResult<Nothing>()
}

sealed class CreateOutcome {
    data object Created : CreateOutcome()
    /** الاسم موجود على الراوتر بحساب **غير** حسابنا (محاولتنا السابقة تُعاد كـ Created) ⇒ يُعدّ فشلاً لهذا الكرت. */
    data object AlreadyExists : CreateOutcome()
    /** رفض نهائي لهذا الكرت فقط (يُعدّ فاشلاً ولا يوقف الدفعة). */
    data class Rejected(val message: String) : CreateOutcome()
    /** عطل مؤقت (شبكة/مهلة/راوتر غير متاح): توقف الدورة وتُعاد لاحقاً. */
    data class Transient(val error: SagaError) : CreateOutcome()
    /** عطل يعمّ كل الكروت (مصادقة/خدمة REST): فشل الساغا. */
    data class Fatal(val error: SagaError) : CreateOutcome()
}

data class PrepareResult(
    val orderId: String,
    val batchId: String,
    val batchNumber: String,
    val pricePerVoucherMinor: Long,
    val ownerName: String,
    val limits: UserLimits = UserLimits.NONE,
    /** الإعدادات المحسومة كاملة (من لقطة الدفعة)؛ null = غير متوفرة فلا يُجرى فحص بروفايل الراوتر. */
    val settings: ResolvedUserSettings? = null,
    /** اسم الباقة في User Manager كما هو مخزَّن في السحابة (المرجع)، لا ما حملته الواجهة؛ null = استعمل ما في الطلب. */
    val profileName: String? = null
)

data class BillingResult(val invoiceId: String, val totalAmountMinor: Long)

/** التخزين المحلي (Room): مصدر حقيقة الساغا، وكل save يجب أن يكون دائماً قبل الانتقال للخطوة التالية. */
interface RunStore {
    suspend fun load(executionId: String): RunState?
    /**
     * يحفظ الحالة كاملة **عدا** `cancelRequested`: هذا العلم يملكه [setCancelRequested] وحده، كي لا يمحوه
     * حفظ الساغا لحالتها (سباق بين زر الإلغاء ودورة جارية).
     */
    suspend fun save(state: RunState)
    suspend fun setCancelRequested(executionId: String, requested: Boolean)
    /** يقرأ علم الإلغاء فقط (رخيص) لفحصه بين الدفعات. */
    suspend fun isCancelRequested(executionId: String): Boolean

    suspend fun staged(executionId: String, state: VoucherState? = null): List<StagedVoucher>
    /** إدراج الكل في معاملة واحدة، والتعارض على (executionId, seq) يستبدل. */
    suspend fun insertStaged(vouchers: List<StagedVoucher>)
    suspend fun setVoucherStates(executionId: String, seqs: List<Int>, state: VoucherState, error: String? = null)
    suspend fun counts(executionId: String): Map<VoucherState, Int>
}

interface RouterPort {
    /** كل أسماء المستخدمين الحالية (للتحقق من عدم التعارض) بحروف صغيرة. */
    suspend fun existingUsernames(): SagaResult<Set<String>>
    /** (قديم) أسماء المستخدمين التي تحمل وسم دفعة قديمة؛ المصالحة الحالية تعتمد existingUsernames وأسماء الكروت المحفوظة في Room. */
    suspend fun usernamesWithComment(tag: String): SagaResult<Set<String>>
    /**
     * ينشئ مستخدماً في User Manager ويُسند له الباقة [profileName]. الحدود ملك الباقة وتُكتب عليها لا على المستخدم؛
     * [limits] لقطة الحدود المحسومة (للتحقق والتسجيل فقط). [comment] تعليق المختص فقط (قد يكون فارغاً فلا يُرسَل).
     */
    suspend fun createUser(username: String, password: String, profileName: String, comment: String, limits: UserLimits): CreateOutcome
    /**
     * إعدادات الباقة [profileName] على الراوتر (سرعة/جلسات متزامنة) للتحقق من تطابقها مع المحسوم قبل الدفع.
     * Ok(null) = غير متاح للفحص (البروفايل غير موجود أو لا قراءة)؛ الراوتر نفسه يرفض حينها إنشاء الحسابات.
     */
    suspend fun profileSettings(profileName: String): SagaResult<RouterProfileSettings?> = SagaResult.Ok(null)
    /**
     * جاهزية الراوتر للتوليد (قراءة فقط، لا يعدّل الراوتر): مفاتيح الفحوص الأساسية الفاشلة. قائمة فارغة = جاهز.
     * الافتراضي «جاهز» كي لا تتغير المنافذ الوهمية في الاختبارات.
     */
    suspend fun failedReadinessChecks(): SagaResult<List<String>> = SagaResult.Ok(emptyList())
    /**
     * تحقق دفعي بعد الإنشاء من User Manager (مرجع التشغيل): يعيد (اسم ← سبب) لكل حساب غير موجود أو بلا الباقة [profileName] أو معطَّل.
     * فارغ = الكل سليم. الافتراضي «سليم» كي لا تتغير المنافذ الوهمية في الاختبارات.
     */
    suspend fun verifyUsers(usernames: Collection<String>, profileName: String): SagaResult<Map<String, String>> = SagaResult.Ok(emptyMap())
    /**
     * أسماء من [expected] (اسم ← كلمة المرور المحفوظة) لا يطابق حسابها على الراوتر تعليق الدفعة [comment] أو كلمة المرور:
     * حساب غريب بنفس الاسم. لا يُحذف أبداً (ليس حسابنا). الافتراضي «لا تعارض» كي لا تتغير المنافذ الوهمية في الاختبارات.
     */
    suspend fun credentialMismatches(expected: Map<String, String>, comment: String): SagaResult<Set<String>> = SagaResult.Ok(emptySet())
    /** تعويض الإلغاء: حذف كل مستخدمي الدفعة (تعليقهم يبدأ بالوسم [tag] وفق [RouterTags.matches])؛ يعيد عدد المحذوف. */
    suspend fun deleteByComment(tag: String): SagaResult<Int>
    /** تعويض الإلغاء: حذف الحسابات بأسمائها (الكروت المحفوظة في Room للتنفيذ)؛ يعيد عدد المحذوف. */
    suspend fun deleteUsers(usernames: Collection<String>): SagaResult<Int> = SagaResult.Ok(0)
}

interface CloudPort {
    /** معاملة واحدة وidempotent: تنشئ/تعيد الطلب والدفعة ورقم الدفعة والسعر، وترفض مالكاً مؤرشفاً/غائباً أو باقة غير فعّالة. */
    suspend fun prepare(state: RunState): SagaResult<PrepareResult>
    /**
     * يحجز [count] رقماً متتالياً من العدّاد الدائم للبروفايل [profileId] بمعاملة ذرية، ويعيد أول رقم محجوز.
     * الحجز لا يُسترجع: رقم حُجز ولم يُنشأ حسابه يبقى محروقاً، فلا يتكرر رقم بين عمليتين متزامنتين أبداً.
     * إن كان آخر رقم محجوز بعد الحجز سيتجاوز [maxValue] (سعة طول الاسم) يُرفض بخطأ [SagaError.SEQUENCE_EXHAUSTED]
     * **دون كتابة**، فلا يتلف العدّاد.
     */
    suspend fun reserveSequence(profileId: String, count: Int, maxValue: Long): SagaResult<Long>
    /** كتابة آمنة للتكرار لكروت الدفعة (معرّف الوثيقة = seq). */
    suspend fun commitVouchers(state: RunState, vouchers: List<StagedVoucher>): SagaResult<Unit>
    /** تحديث الدفعة بنتيجتها النهائية: status ∈ COMPLETED/CANCELLED/FAILED. */
    suspend fun finalizeBatch(state: RunState, status: String): SagaResult<Unit>
    /** معاملة واحدة وidempotent: إغلاق الطلب وإنشاء الفاتورة (معرّفها مشتق من الطلب). */
    suspend fun bill(state: RunState): SagaResult<BillingResult>
    suspend fun cancelOrder(state: RunState): SagaResult<Unit>
    /** مرآة تنفيذ للرؤية على أجهزة أخرى؛ best-effort ولا يفشل الساغا بسببها. */
    suspend fun mirror(state: RunState)
}

sealed class SagaOutcome {
    data class Completed(val partial: Boolean) : SagaOutcome()
    data object Cancelled : SagaOutcome()
    /** فشل نهائي لهذه المحاولة (يمكن للمستخدم إعادة المحاولة يدوياً إن أراد). */
    data class Failed(val error: SagaError) : SagaOutcome()
    /** عطل مؤقت: الحالة محفوظة ويُعاد التشغيل لاحقاً (Worker.retry). */
    data class Suspended(val error: SagaError) : SagaOutcome()
}
