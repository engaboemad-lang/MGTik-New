package ps.mgtik.hotspot.domain.usecase

import javax.inject.Inject
import ps.mgtik.hotspot.core.result.ApiError
import ps.mgtik.hotspot.core.result.AppResult
import ps.mgtik.hotspot.core.result.IdempotencyKeyProvider
import ps.mgtik.hotspot.domain.repository.IActiveSessionRepository

class DisconnectActiveUserUseCase @Inject constructor(
    private val activeSessionRepository: IActiveSessionRepository
) {
    suspend operator fun invoke(
        sessionId: String,
        username: String,
        idempotencyKey: String = IdempotencyKeyProvider.generateKey()
    ): AppResult<Unit> {
        if (sessionId.isBlank()) {
            return AppResult.Error(ApiError.validationError("معرف الجلسة مطلوب"))
        }
        return activeSessionRepository.disconnectActiveUser(sessionId, username, idempotencyKey)
    }
}
