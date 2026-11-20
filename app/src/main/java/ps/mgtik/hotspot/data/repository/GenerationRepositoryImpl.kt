package ps.mgtik.hotspot.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.data.cloud.source.CloudGenerationDataSource
import ps.mgtik.hotspot.data.generation.GenerationSagaFactory
import ps.mgtik.hotspot.data.generation.RoomRunStore
import ps.mgtik.hotspot.data.generation.RunMapping
import ps.mgtik.hotspot.data.local.cache.LocalCacheManager
import ps.mgtik.hotspot.data.local.dao.GenerationDao
import ps.mgtik.hotspot.domain.generation.GenerationRules
import ps.mgtik.hotspot.domain.generation.GenerationSpec
import ps.mgtik.hotspot.domain.generation.RunState
import ps.mgtik.hotspot.domain.generation.RunStatus
import ps.mgtik.hotspot.domain.generation.SagaError
import ps.mgtik.hotspot.domain.generation.SagaOutcome
import ps.mgtik.hotspot.domain.generation.SagaStep
import ps.mgtik.hotspot.domain.generation.StepState
import ps.mgtik.hotspot.domain.generation.VoucherState
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IAuditRepository
import ps.mgtik.hotspot.domain.repository.IGenerationRepository
import ps.mgtik.hotspot.domain.repository.IGenerationScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GenerationRepositoryImpl - مستودع التوليد.
 *
 * التوليد يُنفَّذ على الجهاز كساغا قابلة للاستئناف (domain.generation.GenerationSaga)، وحالتها في Room.
 * skipQueue: false → حفظ التنفيذ + جدولة GenerationWorker (يعيد executionId حقيقياً فوراً)،
 *            true  → تشغيل الساغا هنا. (لا يُستخدم OfflineCommand للتوليد؛ الأوامر القديمة المخزّنة تُحوَّل لهذا المسار.)
 */
@Singleton
class GenerationRepositoryImpl @Inject constructor(
    private val cloudDataSource: CloudGenerationDataSource,
    private val localCache: LocalCacheManager,
    private val runStore: RoomRunStore,
    private val dao: GenerationDao,
    private val sagaFactory: GenerationSagaFactory,
    private val scheduler: IGenerationScheduler,
    private val auditRepo: IAuditRepository
) : IGenerationRepository {

    override suspend fun submitGenerationCommand(
        request: GenerationCommandRequest,
        idempotencyKey: String,
        skipQueue: Boolean
    ): AppResult<GenerationCommandResponse> {
        val violations = GenerationRules.validate(
            request.profileId, request.ownerId, request.count, request.format, request.prefix, request.codeLength,
            request.mode, request.passwordType, request.passwordLength, request.comment
        )
        if (violations.isNotEmpty()) {
            return AppResult.Error(ApiError.validationError("Voucher generation validation failed", violations))
        }

        // المعرّفات مشتقة من المفتاح ⇒ النقرة المزدوجة/إعادة الإرسال تعيد التنفيذ نفسه ولا تنشئ دفعة ثانية
        val executionId = "EXEC-$idempotencyKey"
        val existing = runStore.load(executionId)
        val state = existing ?: RunState(
            executionId = executionId,
            idempotencyKey = idempotencyKey,
            spec = GenerationSpec(
                profileId = request.profileId,
                profileName = request.profileName,
                ownerId = request.ownerId.orEmpty(),
                count = request.count,
                format = request.format,
                prefix = request.prefix,
                codeLength = request.codeLength,
                mode = request.mode,
                passwordType = request.passwordType,
                passwordLength = request.passwordLength,
                comment = request.comment.trim()
            ),
            orderId = "ORD-$idempotencyKey",
            batchId = "BATCH-$idempotencyKey",
            status = RunStatus.QUEUED,
            progressTotal = request.count,
            createdAtMillis = System.currentTimeMillis()
        ).also {
            runStore.save(it)
            auditRepo.logAction("GENERATION_START", "Generation of ${request.count} vouchers registered", LogSeverity.INFO)
        }

        if (!skipQueue) {
            if (!state.isTerminal) scheduler.schedule(executionId)
            return AppResult.Success(responseOf(state))
        }

        return when (val outcome = sagaFactory.create().run(executionId)) {
            is SagaOutcome.Completed, SagaOutcome.Cancelled -> AppResult.Success(responseOf(runStore.load(executionId) ?: state))
            is SagaOutcome.Failed -> AppResult.Error(outcome.error.toApiError())
            is SagaOutcome.Suspended -> AppResult.Error(outcome.error.toApiError())
        }
    }

    override suspend fun startExecution(
        contextId: String,
        flowType: WorkflowFlowType,
        steps: List<String>
    ): AppResult<Execution> = cloudDataSource.startExecution(contextId, flowType, steps)

    override suspend fun reportExecutionStep(
        executionId: String,
        step: String,
        result: StepStatus,
        detail: Map<String, String>?,
        errorCode: String?
    ): AppResult<Unit> = cloudDataSource.reportExecutionStep(executionId, step, result, detail, errorCode)

    /** حالة التنفيذ من Room (تتحدّث مع كل نقطة حفظ للساغا) فتعمل الشاشة بلا اتصال وتنجو من إعادة التشغيل. */
    override fun getExecution(executionId: String): Flow<Execution?> = localCache.observeExecution(executionId)

    override fun listExecutions(
        contextId: String?,
        flowType: WorkflowFlowType?,
        status: ExecutionStatus?
    ): Flow<List<Execution>> = cloudDataSource.listExecutionsFlow(contextId, flowType, status)

    override suspend fun retryExecution(
        executionId: String,
        idempotencyKey: String,
        skipQueue: Boolean
    ): AppResult<RetryExecutionResponse> {
        val state = runStore.load(executionId)
            ?: return AppResult.Error(ApiError("NOT_FOUND", "Generation run not found"))
        // إعادة المحاولة للفاشل فقط: المكتمل (حتى الجزئي) فُوتِر ولا يُعاد، والجاري لا يُزاحَم
        if (state.status != RunStatus.FAILED) {
            return AppResult.Error(ApiError(ERR_NOT_RETRYABLE, "Only a failed run can be retried"))
        }

        // الكروت المرفوضة تُجرَّب مجدداً فقط إن لم تُحسم خطوة الدفع (انظر RunState.canRepushRejected)
        if (state.canRepushRejected) dao.resetFailedVouchers(executionId)
        // كروت ما زالت PENDING بعد حسم الدفع (لا يُفترض حدوثه): تُعاد الخطوة ليُدفع ما تبقى بدل إكمال دفعة ناقصة بصمت
        val stillPending = (runStore.counts(executionId)[VoucherState.PENDING] ?: 0) > 0
        val reset = state.copy(
            status = RunStatus.QUEUED,
            attempt = state.attempt + 1,
            errorCode = null,
            errorMessage = null,
            steps = state.steps.mapValues { (step, st) ->
                if (st == StepState.FAILED || (stillPending && step == SagaStep.ROUTER_PUSH)) StepState.PENDING else st
            }
        )
        runStore.save(reset)
        auditRepo.logAction("GENERATION_RETRY", "Retrying execution $executionId (attempt ${reset.attempt})", LogSeverity.WARNING)

        val response = RetryExecutionResponse(
            executionId = executionId,
            status = ExecutionStatus.QUEUED,
            requestedQuantity = reset.requested,
            successfulQuantity = reset.successful,
            failedQuantity = reset.failed,
            isRetryable = true
        )
        if (!skipQueue) {
            scheduler.schedule(executionId)
            return AppResult.Success(response)
        }
        return when (val outcome = sagaFactory.create().run(executionId)) {
            is SagaOutcome.Completed, SagaOutcome.Cancelled -> AppResult.Success(response)
            is SagaOutcome.Failed -> AppResult.Error(outcome.error.toApiError())
            is SagaOutcome.Suspended -> AppResult.Error(outcome.error.toApiError())
        }
    }

    override suspend fun cancelExecution(executionId: String): AppResult<Unit> {
        val state = runStore.load(executionId)
            ?: return AppResult.Error(ApiError("NOT_FOUND", "Generation run not found"))
        return when {
            state.status == RunStatus.CANCELLED -> AppResult.Success(Unit)
            state.status == RunStatus.COMPLETED || state.step(SagaStep.BILLING) == StepState.SUCCESS ->
                AppResult.Error(ApiError(SagaError.CANCEL_AFTER_BILLING, "Cannot cancel after billing"))
            else -> {
                // العلم يملكه RunStore.setCancelRequested وحده (لا يمحوه حفظ الساغا لحالتها)
                runStore.setCancelRequested(executionId, true)
                auditRepo.logAction("GENERATION_CANCEL", "Cancel requested for $executionId", LogSeverity.WARNING)
                scheduler.schedule(executionId) // إن كان عامل يعمل فهو يلتقط العلم بين الدفعات؛ وإلا يبدأ عامل ينفّذ الإلغاء
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun peekNextSequence(cloudProfileId: String): AppResult<Long> =
        cloudDataSource.peekNextSequence(cloudProfileId)

    override fun observeBatches(): Flow<List<VoucherBatch>> =
        cloudDataSource.observeBatchesFlow().onEach { batches -> localCache.saveBatches(batches) }

    override fun observeBatch(batchId: String): Flow<VoucherBatch?> =
        dao.observeRunByBatch(batchId).map { entity -> entity?.toState()?.let(RunMapping::toBatch) }

    override fun observeFailureReasons(batchId: String): Flow<List<FailureReason>> =
        dao.observeFailureReasonsByBatch(batchId).map { rows -> rows.map { FailureReason(it.reason, it.n) } }

    override suspend fun getBatchVouchers(batchId: String): AppResult<List<Voucher>> {
        val run = dao.getRunByBatch(batchId)
        // دفعة ملغاة: كروتها حُذفت من الراوتر عند الإلغاء (ويبقى سجلّها محلياً/سحابياً)، فلا تُسلَّم للتصدير
        // كأنها صالحة ولا يُرجَع إليها من السحابة.
        if (run != null && run.status == RunStatus.CANCELLED.name) {
            return AppResult.Error(ApiError(ERR_BATCH_CANCELLED, "Batch was cancelled; its vouchers no longer exist on the router"))
        }
        // التسليم (تصدير/طباعة) للدفعة المكتملة فقط: الجارية والفاشلة كروتها غير مُفوتَرة، وقد لا تكون محقَّقة بعد
        if (run != null && run.status != RunStatus.COMPLETED.name) {
            return AppResult.Error(ApiError(SagaError.BATCH_NOT_READY, "Batch is not completed yet; its vouchers cannot be exported"))
        }
        if (run != null) {
            val local = dao.getDeliverable(run.executionId)
            if (local.isNotEmpty()) {
                return AppResult.Success(
                    local.map {
                        Voucher(
                            code = it.username,
                            password = it.password,
                            profileName = run.profileName,
                            priceMinor = run.pricePerVoucherMinor,
                            batchId = batchId,
                            serialNumber = it.seq.toLong()
                        )
                    }
                )
            }
        }
        return cloudDataSource.fetchBatchVouchers(batchId)
    }

    override suspend fun resumePendingRuns() {
        dao.getResumableRunIds().forEach { scheduler.schedule(it) }
    }

    override suspend fun activeRunCount(): Int = dao.getResumableRunIds().size

    private fun responseOf(state: RunState) = GenerationCommandResponse(
        executionId = state.executionId,
        batchId = state.batchId,
        batchNumber = state.batchNumber.ifBlank { "PENDING" },
        status = RunMapping.executionStatus(state.status),
        requestedQuantity = state.requested,
        successfulQuantity = state.successful,
        failedQuantity = state.failed,
        totalAmountMinor = state.totalAmountMinor,
        errorCode = state.errorCode,
        errorMessage = state.errorMessage
    )

    private fun SagaError.toApiError() = ApiError(code = code, message = message, retryable = retryable)

    private companion object {
        const val ERR_NOT_RETRYABLE = "ERR_GEN_NOT_RETRYABLE"
        const val ERR_BATCH_CANCELLED = "ERR_GEN_BATCH_CANCELLED"
    }
}
