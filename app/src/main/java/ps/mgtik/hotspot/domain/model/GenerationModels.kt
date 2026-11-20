package ps.mgtik.hotspot.domain.model

import kotlinx.serialization.Serializable

/**
 * GenerationCommandRequest - طلب توليد دفعة كروت جديدة (§4.9.1).
 */
@Serializable
data class GenerationCommandRequest(
    val profileId: String,
    val profileName: String,
    val count: Int,
    val prefix: String = "",
    val format: String = "numeric",
    val codeLength: Int = 8,
    val ownerId: String? = null,
    /** GenerationMode.name: SEQUENTIAL (عدّاد دائم لكل بروفايل) أو RANDOM. */
    val mode: String = "RANDOM",
    /** PasswordType.name؛ فارغ = السلوك القديم (كلمة السر مشتقة من النمط). */
    val passwordType: String = "",
    val passwordLength: Int = 0,
    /** تعليق اختياري يُطبَّق على كل حسابات العملية. */
    val comment: String = ""
)

/**
 * GenerationCommandResponse - استجابة السحابة لأمر التوليد.
 * تم التدقيق: توحيد الحالات للامتثال لنموذج الـ Execution الموحد.
 */
@Serializable
data class GenerationCommandResponse(
    val executionId: String,
    val batchId: String,
    val batchNumber: String,
    val status: ExecutionStatus,
    val requestedQuantity: Int,
    val successfulQuantity: Int,
    val failedQuantity: Int,
    val totalAmountMinor: Long,
    val vouchers: List<Voucher> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class RetryExecutionRequest(
    val executionId: String
)

@Serializable
data class RetryExecutionResponse(
    val executionId: String,
    val status: ExecutionStatus,
    val requestedQuantity: Int,
    val successfulQuantity: Int,
    val failedQuantity: Int,
    val isRetryable: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

/**
 * VoucherCodeFormat - تنسيق أكواد الكروت المولَّدة، يختاره المستخدم في معالج التوليد (S4).
 * القيمة تصل إلى الساغا كما هي (GenerationSpec.format) وتُخزَّن في وثيقة الدفعة. USER_PASS_SAME قديم: الواجهة لا تعرضه
 * (الخيار الصريح PasswordType.SAME_AS_USERNAME).
 */
@Serializable
enum class VoucherCodeFormat { NUMERIC_ONLY, ALPHANUMERIC, UPPERCASE_ONLY, USER_PASS_SAME }

/** سبب فشل إنشاء حسابات على الراوتر وعددها (نتيجة العملية). */
data class FailureReason(val reason: String, val count: Int)

/**
 * Voucher - كرت واحد ضمن دفعة مولَّدة محلياً (ساغا التوليد على الجهاز: domain.generation.GenerationSaga).
 */
@Serializable
data class Voucher(
    val code: String,
    val password: String,
    val profileName: String,
    val priceMinor: Long = 0L,
    val batchId: String = "",
    val serialNumber: Long = 0L
)

/**
 * ExecutionStatus - الحالة العامة لعملية تنفيذ (توليد أو إنشاء بروفايل) (§4.9/§6.7).
 */
@Serializable
enum class ExecutionStatus { QUEUED, RETRYING, RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * StepStatus - حالة خطوة واحدة ضمن تنفيذ (§6.7).
 * PENDING = لم تبدأ بعد (الحالة الافتراضية عند إنشاء الخطوة).
 */
@Serializable
enum class StepStatus { PENDING, RUNNING, SUCCESS, SKIPPED, FAILED }

/**
 * WorkflowFlowType - نوع سير العمل الذي ينتمي إليه التنفيذ (§6.7).
 */
@Serializable
enum class WorkflowFlowType { GENERATION, PROFILE_CREATION }

/**
 * VoucherBatchStatus - حالة دفعة كروت قيد التوليد (§4.9.6).
 * منفصلة عمداً عن BatchStatus (المعرَّف في DomainEntities.kt ويخص Batch/الطلبات التاريخية فقط)
 * لأن هذه تحتاج قيماً إضافية (QUEUED, RETRYING, RUNNING, CANCELLED) لا يحملها ذلك النوع،
 * وتعديله كان سيؤثر على CloudOrderDataSource الذي يعتمد على القيم الثلاث الحالية فقط.
 */
@Serializable
enum class VoucherBatchStatus { PENDING, QUEUED, RETRYING, RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * ExecutionStepRecord - سجل خطوة واحدة ضمن التنفيذ، يظهر في شاشة تقدم التنفيذ (S5) (§6.7).
 */
@Serializable
data class ExecutionStepRecord(
    val step: String,
    val status: StepStatus = StepStatus.PENDING,
    val detail: Map<String, String> = emptyMap(),
    val errorCode: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null
)

/**
 * Execution - سجل تنفيذ موحَّد (توليد كروت أو إنشاء بروفايل) بكل خطواته (§6.7).
 * هذا هو نموذج الحالة الذي تراقبه شاشتا S5 (التقدم) والمحرك في GenerationViewModel.
 */
@Serializable
data class Execution(
    val executionId: String,
    val contextId: String,
    val flowType: WorkflowFlowType,
    val attemptNumber: Int = 1,
    val status: ExecutionStatus,
    val steps: List<ExecutionStepRecord> = emptyList(),
    val requestedQuantity: Int? = null,
    val successfulQuantity: Int? = null,
    val failedQuantity: Int? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val startedAt: String,
    val completedAt: String? = null
)

/**
 * VoucherBatch - دفعة كروت تم توليدها، تُعرض في نتيجة التوليد (S6) وسجل الدفعات (§4.9.6).
 * الحقول مطابقة لِما يتوقعه VoucherBatchEntity.fromDomain/toDomain في طبقة الكاش المحلي.
 */
@Serializable
data class VoucherBatch(
    val id: String,
    val batchNumber: String,
    val cloudProfileId: String,
    val profileName: String,
    val ownerId: String = "",
    val orderId: String = "",
    val businessId: String = "",
    val requestedQuantity: Int,
    val successfulQuantity: Int,
    val failedQuantity: Int = 0,
    val pricePerVoucherMinor: Long = 0L,
    val totalAmountMinor: Long = 0L,
    val status: VoucherBatchStatus,
    val createdAt: String
)
