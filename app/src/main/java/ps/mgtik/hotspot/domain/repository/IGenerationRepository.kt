package ps.mgtik.hotspot.domain.repository

import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.*

/**
 * IGenerationRepository - عقد عمليات التوليد وتتبع التنفيذ (§4.9)
 * يدعم التمييز بين الجدولة (Queueing) والتنفيذ الفعلي (Execution).
 */
interface IGenerationRepository {

    /**
     * إرسال طلب توليد (المالك إلزامي). المعرّفات مشتقة من [idempotencyKey]: التكرار بالمفتاح نفسه يعيد التنفيذ نفسه.
     * @param skipQueue false → يُحفظ التنفيذ محلياً ويُجدوَل عامل WorkManager (Foreground) ويُعاد executionId حقيقي فوراً،
     *                  true → تنفيذ الساغا هنا والآن (يستدعيه الاختبار/الحالات التي تحتاج النتيجة قبل العودة).
     */
    suspend fun submitGenerationCommand(
        request: GenerationCommandRequest,
        idempotencyKey: String,
        skipQueue: Boolean = false
    ): AppResult<GenerationCommandResponse>

    // --- محرك تتبع التنفيذ (§4.9.2 - §4.9.5) ---
    suspend fun startExecution(
        contextId: String,
        flowType: WorkflowFlowType,
        steps: List<String>
    ): AppResult<Execution>

    suspend fun reportExecutionStep(
        executionId: String,
        step: String,
        result: StepStatus,
        detail: Map<String, String>? = null,
        errorCode: String? = null
    ): AppResult<Unit>

    fun getExecution(executionId: String): Flow<Execution?>
    fun listExecutions(
        contextId: String? = null,
        flowType: WorkflowFlowType? = null,
        status: ExecutionStatus? = null
    ): Flow<List<Execution>>

    suspend fun retryExecution(
        executionId: String,
        idempotencyKey: String,
        skipQueue: Boolean = false
    ): AppResult<RetryExecutionResponse>

    suspend fun peekNextSequence(cloudProfileId: String): AppResult<Long>
    fun observeBatches(): Flow<List<VoucherBatch>>

    // --- ساغا التوليد على الجهاز (قابلة للاستئناف) ---

    /** طلب إلغاء: قبل الفوترة فقط (يُحذف ما دُفع للراوتر ويُلغى الطلب)، وبعدها خطأ ERR_GEN_CANCEL_AFTER_BILLING. */
    suspend fun cancelExecution(executionId: String): AppResult<Unit>

    /** الدفعة الناتجة كما هي محلياً (تتحدّث مع تقدم الساغا)، للشاشتين S5/S6. */
    fun observeBatch(batchId: String): Flow<VoucherBatch?>
    /** أسباب فشل الحسابات المرفوضة على الراوتر في هذه الدفعة. */
    fun observeFailureReasons(batchId: String): Flow<List<FailureReason>>

    /** كروت دفعة جاهزة للتصدير (الناجحة فقط): من Room أولاً، وإلا من Firestore. */
    suspend fun getBatchVouchers(batchId: String): AppResult<List<Voucher>>

    /** يعيد جدولة كل ساغا لم تنتهِ (يُستدعى عند إقلاع التطبيق). */
    suspend fun resumePendingRuns()

    /** عدد الساغات الجارية أو المعلّقة (QUEUED/RUNNING/RETRYING). */
    suspend fun activeRunCount(): Int
}