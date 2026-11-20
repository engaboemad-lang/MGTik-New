package ps.mgtik.hotspot.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.domain.model.*
import ps.mgtik.hotspot.domain.repository.IGenerationRepository

/**
 * GetExecutionStateUseCase - مراقبة حالة تنفيذ عملية معينة (§4.9.4).
 */
class GetExecutionStateUseCase @Inject constructor(private val repository: IGenerationRepository) {
    operator fun invoke(executionId: String): Flow<Execution?> =
        repository.getExecution(executionId)
}

/**
 * RetryFailedExecutionUseCase - إعادة محاولة تنفيذ فاشل أو جزئي (§4.9.5).
 */
class RetryFailedExecutionUseCase @Inject constructor(private val repository: IGenerationRepository) {
    /** يعيد الساغا الفاشلة عبر WorkManager (skipQueue = false)؛ المكتمل جزئياً لا يُعاد. */
    suspend operator fun invoke(executionId: String, idempotencyKey: String): AppResult<RetryExecutionResponse> =
        repository.retryExecution(executionId, idempotencyKey, skipQueue = false)
}

/**
 * CancelExecutionUseCase - طلب إلغاء عملية جارية (§4.9.2).
 */
class CancelExecutionUseCase @Inject constructor(private val repository: IGenerationRepository) {
    suspend operator fun invoke(executionId: String): AppResult<Unit> = repository.cancelExecution(executionId)
}

/**
 * PeekNextSequenceUseCase - معاينة رقم التسلسل القادم للبروفايل (§4.9.6).
 */
class PeekNextSequenceUseCase @Inject constructor(private val repository: IGenerationRepository) {
    suspend operator fun invoke(cloudProfileId: String): AppResult<Long> =
        repository.peekNextSequence(cloudProfileId)
}

/**
 * ObserveBatchesUseCase - مراقبة قائمة الدفعات من الكاش المحلي المزامَن.
 */
class ObserveBatchesUseCase @Inject constructor(private val repository: IGenerationRepository) {
    operator fun invoke(): Flow<List<VoucherBatch>> =
        repository.observeBatches()
}

/** أسباب فشل الحسابات المرفوضة في دفعة (لشاشة النتيجة). */
class ObserveBatchFailureReasonsUseCase @Inject constructor(private val repository: IGenerationRepository) {
    operator fun invoke(batchId: String): Flow<List<FailureReason>> = repository.observeFailureReasons(batchId)
}

/** الدفعة الناتجة من ساغا محلية (تتحدّث مع التقدم) لشاشتي S5/S6. */
class ObserveGeneratedBatchUseCase @Inject constructor(private val repository: IGenerationRepository) {
    operator fun invoke(batchId: String): Flow<VoucherBatch?> = repository.observeBatch(batchId)
}
