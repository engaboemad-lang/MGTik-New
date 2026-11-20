package ps.mgtik.hotspot.domain.usecase

import javax.inject.Inject
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.generation.GenerationRules
import ps.mgtik.hotspot.domain.model.GenerationCommandRequest
import ps.mgtik.hotspot.domain.model.GenerationCommandResponse
import ps.mgtik.hotspot.domain.repository.IGenerationRepository

/**
 * StartNewOrderGenerationUseCase - يسجّل طلب توليد دفعة جديدة (المالك إلزامي).
 *
 * skipQueue = false (الافتراضي): يُحفظ التنفيذ على الجهاز ويتولاه عامل Foreground قابل للاستئناف،
 * والعودة فورية بـ executionId حقيقي تراقبه S5.
 *
 * مفتاح التفرّد يجب أن تمرّره الواجهة ثابتاً طوال جلسة النموذج: المعرّفات كلها مشتقة منه،
 * فتكرار الاستدعاء بالمفتاح نفسه (نقرة مزدوجة/إعادة) لا يُنشئ دفعة ولا فاتورة ثانية.
 */
class StartNewOrderGenerationUseCase @Inject constructor(
    private val generationRepository: IGenerationRepository
) {
    suspend operator fun invoke(
        request: GenerationCommandRequest,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey(),
        skipQueue: Boolean = false
    ): AppResult<GenerationCommandResponse> {
        val fieldErrors = GenerationRules.validate(
            request.profileId, request.ownerId, request.count, request.format, request.prefix, request.codeLength,
            request.mode, request.passwordType, request.passwordLength, request.comment
        )
        if (fieldErrors.isNotEmpty()) {
            return AppResult.Error(ApiError.validationError("Voucher generation validation failed", fieldErrors))
        }
        return generationRepository.submitGenerationCommand(request, idempotencyKey, skipQueue)
    }
}
